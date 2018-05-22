package com.softsz.a9soundrecorder;

import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.Resources;
import android.media.AudioManager;
import android.net.Uri;
import android.os.Binder;
import android.os.FileObserver;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.StatFs;
import android.os.storage.StorageManager;
import android.provider.MediaStore;
import android.util.Log;
import android.widget.Toast;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.Date;

public class SeSoundRecorderService extends Service implements SeRecorder.SeRecorderListener {

    private static final String TAG = "SeSoundRecorderService";

    public static final int STATE_IDLE = 1;
    public static final int STATE_RECORDING = 2;
    public static final int STATE_ERROR = 3;
    public static final int STATE_ERROR_CODE = 100;
    public static final int STATE_SAVE_SUCESS = 4;
    private static final int ONE_SECOND = 1000;

    private int mCurrentState = STATE_IDLE;

    private RecordParamsSetting.RecordParams mParams;

    private SoundRecorderServiceHandler mSoundRecorderServiceHandler;

    private SSRBinder mSSRBinder = new SSRBinder();

    private HandlerThread mHandlerThread = null;
    private SeRecorder mSeRecorder = null;
    private AudioManager mAudioManager = null;
    private StorageManager mStorageManager = null;
    private AudioManager.OnAudioFocusChangeListener mFocusChangeListener = null;

    private boolean mGetFocus = false;
    private String mCurrentFilePath = null;
    private boolean mRecordSaving = false;
    public static final String HANDLER_THREAD_NAME = "SoundRecorderServiceHandler";
    private static final String ACTION_STOP = "stop";
    private static final String RECORDING = "Recording";
    public static final long LOW_STORAGE_THRESHOLD = 2048 * 1024L;
    public static final int ERROR_PATH_NOT_EXIST = -100;
    private RemainingTimeCalculator mRemainingTimeCalculator = null;
    private OnErrorListener mOnErrorListener = null;
    private OnStateChangedListener mOnStateChangedListener = null;
    private RecordingFileObserver mFileObserver = null;
    private Handler mFileObserverHandler = null;

    /**
     * M: To make sure the toast is displayed in UI thread.@{
     **/
    private final int NOT_AVILABLE = 1;
    private final int SAVE_SUCCESS = 2;
    private Handler mToastHandler = null;

    public static final long WAIT_TIME = 100;

    private long mCurrentFileDuration = -1;
    private long mRemainingTime = 0;
    private long mTotalRecordingDuration = -1;
    private OnUpdateTimeViewListener mOnUpdateTimeViewListener = null;
    private Handler mHandler = new Handler();
    private Runnable mUpdateTimer = new Runnable() {
        @Override
        public void run() {
            Log.d(TAG, "run()-mUpdateTimer running");

            if (STATE_RECORDING == mCurrentState) {
                mRemainingTime = mRemainingTimeCalculator.timeRemaining();
                if (mRemainingTime == ERROR_PATH_NOT_EXIST) {
                    reset();
                    return;
                }
                Log.d(TAG, "run()-mRemainingTime is:" + mRemainingTime);
            }

            if ((mRemainingTime <= 0) && (STATE_RECORDING == mCurrentState)) {
                Log.d(TAG, "run()-stopRecordingAsync case1");
                stopRecordingAsync();
            } else if (mCurrentState == STATE_IDLE) {
                Log.d(TAG, "run()-stopRecordingAsync case2");
                stopRecordingAsync();
            } else {
                if (null != mOnUpdateTimeViewListener) {
                    // Added to resolve the problem the timing problem(runnable can't stop as expected)
                    try {
                        int time = getCurrentProgressInSecond();
                        mOnUpdateTimeViewListener.updateTimerView(time);
                    } catch (IllegalStateException e) {
                        Log.d(TAG, "run()-IllegalStateException");
                        return;
                    }
                }
                mHandler.postDelayed(mUpdateTimer, WAIT_TIME);
            }
        }
    };

    private BroadcastReceiver mThutdownBroastReceiver = null;
    private static final String ACTION_SHUTDOWN_IPO = "android.intent.action.ACTION_SHUTDOWN_IPO";
    private Uri mUri = null;
    public static final String AUTHORITY ="com.soft.recordermediaprovider.contentprovider";
    public static final Uri AUDIO_CONTENT_URI = Uri.parse("content://"+AUTHORITY+"/audio");
    public ContentResolver contentResolver = null;

    public static boolean isStarFile = false;

    @Override
    public IBinder onBind(Intent intent) {
        return mSSRBinder;
    }

    @Override
    public void onStateChanged(SeRecorder recorder, int stateCode) {
        /**
         * M: Modified to avoid the mUpdateTimer start while the mCurrentState not
         * yet changed which will cause 1 second auto saving problem. @{
         **/
        if (stateCode != STATE_IDLE) {
            setCurrentFilePath(mSeRecorder.getSampleFilePath());
        }
        int preState = mCurrentState;
        setState(stateCode);
        Log.d(TAG, "onStateChanged(Recorder,int) preState = " + preState + ", mCurrentState = " + mCurrentState);

        if (STATE_IDLE == mCurrentState) {
            if (STATE_RECORDING == preState) {
                abandonAudioFocus();
                getRecordInfoAfterStopRecord();
            }
            return;
        } else {
            if (STATE_RECORDING == mCurrentState) {
                // Refresh the mRemainingTime
                mRemainingTime = mRemainingTimeCalculator.timeRemaining();
                mHandler.post(mUpdateTimer);
                Log.d(TAG, "onStateChanged(Recorder,int) post mUpdateTimer.");
            }
        }
    }

    @Override
    public void onError(SeRecorder recorder, int errorCode) {

    }

    public class SSRBinder extends Binder {
        public SeSoundRecorderService getService() {
            return SeSoundRecorderService.this;
        }
    }

    public interface OnUpdateButtonState {
        void updateButtonState(int recordState);
    }

    public interface OnErrorListener {
        void onError(int errorCode);
    }

    public interface OnStateChangedListener {
        void onStateChanged(int stateCode);
    }

    public interface OnUpdateTimeViewListener {
        void updateTimerView(int time);
    }

    public void setErrorListener(OnErrorListener listener) {
        mOnErrorListener = listener;
    }

    public void setStateChangedListener(OnStateChangedListener listener) {
        mOnStateChangedListener = listener;
    }

    public void setUpdateTimeViewListener(OnUpdateTimeViewListener listener) {
        mOnUpdateTimeViewListener = listener;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        Log.d(TAG, "SeSoundRecorder onCreate");
        mAudioManager = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        mStorageManager = (StorageManager) getSystemService(Context.STORAGE_SERVICE);
        mSeRecorder = new SeRecorder(mStorageManager, this);
        mFocusChangeListener = new MyOnAudioFocusChangeListener();
        mHandlerThread = new HandlerThread(HANDLER_THREAD_NAME);
        mHandlerThread.start();
        mSoundRecorderServiceHandler = new SoundRecorderServiceHandler(mHandlerThread.getLooper());
        contentResolver = getContentResolver();
        /**
         * M: To make sure the toast is displayed in UI thread. Handler construct in the UI thread,
         * then uses the main looper for the mToastHandler. @{
         **/
        mToastHandler = new Handler() {
            public void handleMessage(android.os.Message msg) {
                switch (msg.what) {
                    case NOT_AVILABLE:
                        Toast.makeText(getApplicationContext(), getResources().getString(R.string.not_available), Toast.LENGTH_LONG).show();
                        break;
                    case SAVE_SUCCESS:
                        Toast.makeText(getApplicationContext(), getResources().getString(R.string.tell_save_record_success), Toast.LENGTH_LONG).show();
                        break;
                    default:
                        break;
                }
            }

            ;
        };

        registerBroadcastReceivcer();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Log.d(TAG, "<onStartCommand> start");
        if (null == intent) {
            return START_NOT_STICKY;
        }

        String action = intent.getAction();
        Log.d(TAG, "<onStartCommand> action = " + action);

        if (null == action) {
            return START_NOT_STICKY;
        }

        if (action.equals(ACTION_STOP)) {
            Log.d(TAG, "<onStartCommand> ACTION_STOP");
            if (mCurrentState == STATE_RECORDING) {
                stopRecordingAsync();
            }
        }
        return START_NOT_STICKY;
    }

    @Override
    public void onDestroy() {
        Log.i(TAG, "<onDestroy>");
        unregisterBroadcastReceivcer();
        if (null != mSoundRecorderServiceHandler) {
            mSoundRecorderServiceHandler.getLooper().quit();
        }
        super.onDestroy();
    }

    public void startRecordingAsync(RecordParamsSetting.RecordParams recordParams, OnUpdateButtonState callback) {
        Log.d(TAG, "<startRecordingAsync> mCurrentState = " + mCurrentState);
        if (mCurrentState == STATE_RECORDING) {
            return;
        }
        if (callback != null) {
            callback.updateButtonState(STATE_RECORDING);
        }
        mParams = recordParams;
        sendThreadHandlerMessage(SoundRecorderServiceHandler.START_REOCRD);
    }

    public void doStop(OnUpdateButtonState callback) {
        if ((SeSoundRecorderService.STATE_RECORDING == mCurrentState)) {
            Log.d(TAG, "<onClickStopButton> mService.stopRecord()");
            if (callback != null) {
                callback.updateButtonState(STATE_IDLE);
            }
            stopRecordingAsync();
        }
    }

    public void doSaveRecord() {
        if (mRecordSaving) {
            return;
        }
        saveRecordAsync();
    }

    public void stopRecordingAsync() {
        Log.d(TAG, "<stopRecordingAsync> mCurrentState = " + mCurrentState);
        sendThreadHandlerMessage(SoundRecorderServiceHandler.STOP_REOCRD);
    }

    public void saveRecordAsync() {
        sendThreadHandlerMessage(SoundRecorderServiceHandler.SAVE_RECORD);
    }

    private void sendThreadHandlerMessage(int what) {
        mSoundRecorderServiceHandler.removeCallbacks(mHandlerThread);
        mSoundRecorderServiceHandler.sendEmptyMessage(what);
    }

    public class SoundRecorderServiceHandler extends Handler {
        public SoundRecorderServiceHandler(Looper looper) {
            super(looper);
        }

        public static final int START_REOCRD = 0;
        public static final int STOP_REOCRD = 1;
        public static final int SAVE_RECORD = 2;

        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case START_REOCRD:
                    record(mParams);
                    break;
                case STOP_REOCRD:
                    stopRecord();
                    break;
                case SAVE_RECORD:
                    saveRecord();
                    break;
                default:
                    break;
            }
        }
    }

    public boolean isCurrentFileWaitToSave() {
        if (null != mCurrentFilePath && !mRecordSaving) {
            return mCurrentFilePath.endsWith(SeRecorder.SAMPLE_SUFFIX);
        }
        return false;
    }

    private boolean record(RecordParamsSetting.RecordParams mParams) {
        if (STATE_RECORDING == mCurrentState) {
            Log.d(TAG, "<record> still in STATE_RECORDING, do nothing");
            return true;
        } else {
            if (isStorageFull(mParams)) {
                Log.d(TAG, "<record> storage is full");
                mOnErrorListener.onError(ErrorHandle.ERROR_STORAGE_FULL_WHEN_LAUNCH);
                reset();
                return false;
            } else {
                mRemainingTimeCalculator = new RemainingTimeCalculator(mStorageManager, this);
                mRemainingTimeCalculator.setBitRate(mParams.mRemainingTimeCalculatorBitRate);
                Log.d(TAG, "<record> start record");
                boolean res = false;
                if (requestAudioFocus()) {
                    res = mSeRecorder.startRecording(getApplicationContext(), mParams, -1);
                    Log.d(TAG, "<record> mRecorder.startRecording res = " + res);
                    if (res) {
                        mRemainingTimeCalculator.setFileSizeLimit(mSeRecorder.getSampFile(), -1);
                    } else if (!res) {
                        abandonAudioFocus();
                    }
                } else {
                    displayToast(NOT_AVILABLE);
                }
                return res;
            }
        }
    }

    private boolean stopRecord() {
        Log.d(TAG, "<stopRecord> ..........");
        if (STATE_RECORDING != mCurrentState) {
            mOnErrorListener.onError(STATE_ERROR_CODE);
            return false;
        }
        abandonAudioFocus();
        boolean result = mSeRecorder.stopRecording();
        mHandler.removeCallbacks(mUpdateTimer);
        if (result) {
            doSaveRecord();
        } else {
            displayToast(NOT_AVILABLE);
        }
        return result;
    }

    private boolean saveRecord() {
        if ((null == mCurrentFilePath) || !mCurrentFilePath.endsWith(SeRecorder.SAMPLE_SUFFIX)) {
            Log.i(TAG, "<saveRecord> no file need to be saved");
            mOnErrorListener.onError(STATE_ERROR_CODE);
            return false;
        }

        mRecordSaving = true;
        String currentFilePath = deleteRecordingFileTmpSuffix();

        if (null != currentFilePath) {
            mUri = addToRecordDB(new File(currentFilePath));
            mCurrentFileDuration = 0;
            setCurrentFilePath(null);
            mTotalRecordingDuration = 0;
            if (null != mUri) {
                displayToast(SAVE_SUCCESS);
                mRecordSaving = false;
                return true;
            }
        } else {
            Log.d(TAG, "currentFilePath is null...");
            reset();
            mOnErrorListener.onError(ErrorHandle.ERROR_SAVE_FILE_FAILED);
            mRecordSaving = false;
            return false;
        }
        mOnErrorListener.onError(STATE_ERROR_CODE);
        mRecordSaving = false;
        displayToast(SAVE_SUCCESS);
        return true;
    }

    private Uri addToRecordDB(File file) {
        Log.d(TAG, "<addToMediaDB> begin");
        if (null == file) {
            Log.d(TAG, "<addToMediaDB> file is null, return null");
            return null;
        }
        ContentValues cv = new ContentValues();

        String db_fileName = "";
        String currentFilePath = getCurrentFilePath();
        db_fileName = currentFilePath.substring(currentFilePath.lastIndexOf(File.separator) + 1, currentFilePath.length());
        db_fileName = (db_fileName.endsWith(SeRecorder.SAMPLE_SUFFIX)) ? db_fileName.substring(0, db_fileName.lastIndexOf(SeRecorder.SAMPLE_SUFFIX)) : db_fileName;
        Log.d(TAG,"addToRecordDB = "+db_fileName+",important = "+isStarFile+",currentFilePath"+currentFilePath);
        int db_fileNameLenth = db_fileName.length();

        cv.put("_data",currentFilePath);
        cv.put("date_added",mSeRecorder.getCreateFileTime());
        cv.put("_display_name",db_fileName);
        cv.put("mime_type","audio/3gpp");
        cv.put("title",db_fileName.substring(0,db_fileNameLenth-5));
        cv.put("duration",mTotalRecordingDuration / 1000L);
        if (isStarFile){
            isStarFile = false;
            cv.put("important",1);
        }else{
            cv.put("important",0);
        }
        return contentResolver.insert(AUDIO_CONTENT_URI,cv);
    }

    private String deleteRecordingFileTmpSuffix() {
        Log.i(TAG, "<deleteRecordingFileTmpSuffix>");
        if (!mCurrentFilePath.endsWith(SeRecorder.SAMPLE_SUFFIX)) {
            return null;
        }
        File file = new File(mCurrentFilePath);
        if (!file.exists()) {
            Log.i(TAG, "<deleteRecordingFileTmpSuffix> file is not exist.");
            return null;
        }
        String newPath = mCurrentFilePath.substring(0, mCurrentFilePath
                .lastIndexOf(SeRecorder.SAMPLE_SUFFIX));
        if (SeSoundRecorderService.isStarFile){
            int lastP = newPath.lastIndexOf("/");
            String name = newPath.substring(lastP+1);
            String IMPName= getResources().getString(R.string.important)+name;
            newPath = newPath.substring(0,lastP+1) + IMPName;
        }
        setCurrentFilePath(newPath);
        stopWatching();
        File newFile = new File(newPath);
        boolean result = file.renameTo(newFile);
        if (result) {
            return newFile.getAbsolutePath();
        } else {
            return null;
        }
    }

    private void stopWatching() {
        if (null != mFileObserver) {
            mFileObserver.stopWatching();
            mFileObserver = null;
        }
    }

    private void displayToast(int code) {
        mToastHandler.removeMessages(code);
        mToastHandler.sendEmptyMessage(code);
        mCurrentFileDuration = 0;
        setCurrentFilePath(null);
        setState(STATE_IDLE);
    }

    /**
     * M: new private function for abandon audio focus, it will be called when
     * stop or pause play back
     */
    private void abandonAudioFocus() {
        if (mGetFocus && (null != mAudioManager) && (null != mFocusChangeListener)) {
            if (AudioManager.AUDIOFOCUS_REQUEST_GRANTED == mAudioManager.abandonAudioFocus(mFocusChangeListener)) {
                Log.d(TAG, "<abandonAudioFocus()> abandon audio focus success");
                mGetFocus = false;
            } else {
                Log.e(TAG, "<abandonAudioFocus()> abandon audio focus failed");
                mGetFocus = true;
            }
        }
    }

    public boolean reset() {
        if (!mSeRecorder.reset()) {
            mOnErrorListener.onError(ErrorHandle.ERROR_RECORDING_FAILED);
        }
        if ((null != mCurrentFilePath) && mCurrentFilePath.endsWith(mSeRecorder.SAMPLE_SUFFIX)) {
            File file = new File(mCurrentFilePath);
            file.delete();
        }
        setCurrentFilePath(null);
        mCurrentFileDuration = 0;
        setState(STATE_IDLE);
        return true;
    }

    private void setCurrentFilePath(String path) {
        mCurrentFilePath = path;
        if (null != mFileObserver) {
            mFileObserver.stopWatching();
            mFileObserver = null;
            // M: remove message that has not been processed
            mFileObserverHandler.removeMessages(0);
        }
        if (null != mCurrentFilePath) {
            mFileObserver = new RecordingFileObserver(mCurrentFilePath, FileObserver.DELETE_SELF
                    | FileObserver.ATTRIB | FileObserver.MOVE_SELF);
            if (null == mFileObserverHandler) {
                mFileObserverHandler = new Handler(getMainLooper()) {
                    public void handleMessage(android.os.Message msg) {
                        Log.d(TAG, "<mFileObserverHandler handleMessage> reset()");
                        reset();
                    }

                    ;
                };
            }
            Log.d(TAG, "<setCurrentFilePath> start watching file <" + mCurrentFilePath + ">");
            mFileObserver.startWatching();
        }
    }

    private boolean requestAudioFocus() {
        if (!mGetFocus) {
            int result = mAudioManager.requestAudioFocus(mFocusChangeListener, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN);
            if (result != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
                Log.d(TAG, "<requestAudioFocus> request audio focus fail");
                mGetFocus = false;
            } else {
                Log.d(TAG, "<requestAudioFocus> request audio focus success");
                mGetFocus = true;
            }
        }
        return mGetFocus;
    }

    private void setState(int stateCode) {
        mCurrentState = stateCode;
        if (mOnStateChangedListener != null) {
            mOnStateChangedListener.onStateChanged(stateCode);
        } else {
            Log.d(TAG, "<setState> mOnStateChangedListener = null, mCurrentState = "
                    + mCurrentState);
        }
    }

    public boolean isStorageFull(RecordParamsSetting.RecordParams params) {
        RemainingTimeCalculator remainingTimeCalculator = new RemainingTimeCalculator(
                mStorageManager, this);
        remainingTimeCalculator.setBitRate(params.mAudioEncodingBitRate);
        return remainingTimeCalculator.timeRemaining() < 2;
    }

    class MyOnAudioFocusChangeListener implements AudioManager.OnAudioFocusChangeListener {

        @Override
        public void onAudioFocusChange(int focusChange) {
            Log.d(TAG, "<onAudioFocusChange> audio focus changed to " + focusChange);
            if (focusChange == AudioManager.AUDIOFOCUS_GAIN) {
                Log.d(TAG, "<onAudioFocusChange> audio focus changed to AUDIOFOCUS_GAIN");
                mGetFocus = true;
            } else if (focusChange == AudioManager.AUDIOFOCUS_LOSS || focusChange == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) {
                Log.d(TAG, "<onAudioFocusChange> audio focus loss, stop recording");
                mGetFocus = false;
                if (mCurrentState == STATE_RECORDING) {
                    stopRecordingAsync();
                }

                if (isCurrentFileWaitToSave()) {
                    saveRecordAsync();
                }
            }
        }
    }

    private class RecordingFileObserver extends FileObserver {
        private String mWatchingPath = null;
        // use this to be sure mFileObserverHandler.sendEmptyMessage(0) will be
        // run only once
        private boolean mHasSendMessage = false;

        public RecordingFileObserver(String path, int mask) {
            super(path, mask);
            mWatchingPath = path;
        }

        @Override
        public void onEvent(int event, String path) {
            Log.d(TAG, "<RecordingFileObserver.onEvent> event = " + event);
            if (!mHasSendMessage) {
                if ((FileObserver.DELETE_SELF == event) || (FileObserver.ATTRIB == event)
                        || (FileObserver.MOVE_SELF == event)) {
                    Log.d(TAG, "<RecordingFileObserver.onEvent> " + mWatchingPath
                            + " has been deleted/renamed/moved");
                    mFileObserverHandler.sendEmptyMessage(0);
                    mHasSendMessage = true;
                }
            }
        }
    }

    public String getCurrentFilePath() {
        return mCurrentFilePath;
    }

    public int getCurrentState() {
        return mCurrentState;
    }

    private void getRecordInfoAfterStopRecord() {
        mTotalRecordingDuration = mSeRecorder.getSampleLength();
        mCurrentFileDuration = mSeRecorder.getSampleLength();
        setCurrentFilePath(mSeRecorder.getSampleFilePath());
        // M:Add for stop fail case.
        if (!mSeRecorder.reset()) {
            mOnErrorListener.onError(ErrorHandle.ERROR_RECORDING_FAILED);
        }
    }

    private int getCurrentProgressInSecond() {
        int progress = (int) (getCurrentProgressInMillSecond() / 1000L);
        Log.d(TAG, "<getCurrentProgressInSecond> progress = " + progress);
        return progress;
    }

    public long getCurrentProgressInMillSecond() {
        Log.d(TAG, "<getCurrentProgressInMillSecond> called");
        if (mCurrentState == STATE_RECORDING) {
            return mSeRecorder.getCurrentProgress();
        }
        return 0;
    }

    public boolean isListener(OnStateChangedListener listener) {
        return mOnStateChangedListener.equals(listener);
    }

    public void setAllListenerSelf() {
        // set mOnErrorListener as a new listener when activity stopped/killed,
        // when error occours, show error info in toast
        Log.d(TAG, "<setAllListenerSelf> set new mOnErrorListener");
        mOnErrorListener = new OnErrorListener() {
            public void onError(int errorCode) {
                final int errCode = errorCode;
                mHandler.post(new Runnable() {
                    public void run() {
                        ErrorHandle.showErrorInfoInToast(getApplicationContext(), errCode);
                    }
                });
            }

            ;
        };
        mOnStateChangedListener = null;
        mOnUpdateTimeViewListener = null;
    }

    private void registerBroadcastReceivcer() {
        if (null == mThutdownBroastReceiver) {
            mThutdownBroastReceiver = new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    receiveBroadcast(context, intent);
                }
            };
            IntentFilter iFilter = new IntentFilter();
            iFilter.addAction(Intent.ACTION_SHUTDOWN);

            registerReceiver(mThutdownBroastReceiver, iFilter);
            Log.d(TAG, "<registerExternalStorageListener> register mThutdownBroastReceiver");
        }
    }

    private void receiveBroadcast(Context context, Intent intent) {
        String action = intent.getAction();
        Log.d(TAG, "<onReceive> action = " + action);

        if (isCurrentFileWaitToSave()) {
            saveRecordAsync();
        } else if (Intent.ACTION_SHUTDOWN.equals(action) || ACTION_SHUTDOWN_IPO.equals(action)) {
            stopRecordingAsync();
        }
    }

    private void unregisterBroadcastReceivcer() {
        if (null != mThutdownBroastReceiver) {
            unregisterReceiver(mThutdownBroastReceiver);
        }
    }
}
