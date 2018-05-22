package com.softsz.a9soundrecorder;

import android.content.Context;
import android.media.MediaRecorder;
import android.os.SystemClock;
import android.os.storage.StorageManager;
import android.util.Log;

import java.io.File;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;

public class SeRecorder implements MediaRecorder.OnErrorListener{
    public static final String RECORD_FOLDER = "SoundRecord";
    public static final String SAMPLE_SUFFIX = ".tmp";
    public static final String SAMPLE_PREFIX = "record";

    public static final String STORAGE_PATH_SHARE_SD = "/storage/emulated/0";

    private static final String TAG = "SeRecorder";

    private long mSampleLength = 0;
    private long mSampleStart = 0;
    private long mCreateFileTime = 0;

    private File mSampleFile = null;

    private final StorageManager mStorageManager;

    private MediaRecorder mRecorder = null;
    private int mCurrentState = SeSoundRecorderService.STATE_IDLE;
    private SeRecorderListener recorderListener = null;

    @Override
    public void onError(MediaRecorder mr, int errorType, int extraCode) {
        Log.d(TAG, "<onError> errorType = " + errorType + "; extraCode = " + extraCode);
        stopRecording();
        recorderListener.onError(this, ErrorHandle.ERROR_RECORDING_FAILED);
    }

    // M: the listener when error occurs and state changes
    public interface SeRecorderListener {
        // M: when state changes, we will notify listener the new state code
        void onStateChanged(SeRecorder recorder, int stateCode);

        // M: when error occurs, we will notify listener the error code
        void onError(SeRecorder recorder, int errorCode);
    }

    public SeRecorder(StorageManager storageManager,SeRecorderListener recorderListener){
        mStorageManager = storageManager;
        this.recorderListener = recorderListener;
    }

    /**
     * M: set Recorder to initial state
     */
    public boolean reset(){
        Log.d(TAG,"seRecorder reset()");
        boolean result = true;
        synchronized (this){
            if (mRecorder != null){
                try{
                    if (mCurrentState == SeSoundRecorderService.STATE_RECORDING){
                        mRecorder.stop();
                    }
                }catch (RuntimeException exception){
                    exception.printStackTrace();
                    result = false;
                }finally {
                    mRecorder.reset();
                    mRecorder.release();
                    mRecorder = null;
                }
            }
        }
        mSampleFile = null;
        mSampleLength = 0;
        mSampleStart = 0;

        mCurrentState = SeSoundRecorderService.STATE_IDLE;
        return result;
    }

    public boolean startRecording(Context context, RecordParamsSetting.RecordParams recordParams,int fileSizeLimit){
        Log.d(TAG,"seRecorder startRecording()");
        if (mCurrentState != SeSoundRecorderService.STATE_IDLE){
            return false;
        }
        reset();
        if(!createRecordingFile(recordParams.mExtension)){
            Log.d(TAG, "<startRecording> createRecordingFile return false");
            return false;
        }

        if (!initAndStartMediaRecorder(context, recordParams, fileSizeLimit)) {
            Log.d(TAG, "<startRecording> initAndStartMediaRecorder return false");
            return false;
        }

        mSampleStart = SystemClock.elapsedRealtime();
        setState(SeSoundRecorderService.STATE_RECORDING);
        return true;
    }

    public boolean stopRecording(){
        Log.d(TAG, "<stopRecording> start");
        if (SeSoundRecorderService.STATE_RECORDING != mCurrentState || (null == mRecorder)) {
            recorderListener.onError(this,SeSoundRecorderService.STATE_ERROR_CODE);
            return false;
        }
        synchronized (this) {
            try {
                if (mCurrentState != SeSoundRecorderService.STATE_IDLE) {
                    mRecorder.stop();
                }
            } catch (RuntimeException exception) {
                /** M:modified for stop recording failed. @{ */
                handleException(false, exception);
                recorderListener.onError(this, ErrorHandle.ERROR_RECORDING_FAILED);
                Log.e(TAG, "<stopRecording> recorder illegalstate exception in recorder.stop()");
            } finally {
                if (null != mRecorder) {
                    mRecorder.reset();
                    mRecorder.release();
                    mRecorder = null;
                }
                mSampleLength  = SystemClock.elapsedRealtime() - mSampleStart;
                Log.d(TAG, "<stopRecording> mSampleLength in s is = " + mSampleLength);
                setState(SeSoundRecorderService.STATE_IDLE);
            }
            /** @} */
        }
        return true;
    }

    private boolean initAndStartMediaRecorder(Context context, RecordParamsSetting.RecordParams recordParams, int fileSizeLimit) {
        Log.d(TAG, "<initAndStartMediaRecorder> start");
        try {
            mRecorder = new MediaRecorder();
            mRecorder.setAudioSource(MediaRecorder.AudioSource.MIC);
            mRecorder.setOutputFormat(recordParams.mOutputFormat);
            mRecorder.setOutputFile(mSampleFile.getAbsolutePath());
            mRecorder.setAudioEncoder(recordParams.mAudioEncoder);
            mRecorder.setAudioChannels(recordParams.mAudioChannels);
            mRecorder.setAudioEncodingBitRate(recordParams.mAudioEncodingBitRate);
            mRecorder.setAudioSamplingRate(recordParams.mAudioSamplingRate);
            if (fileSizeLimit > 0)
                mRecorder.setMaxFileSize(fileSizeLimit);
            mRecorder.setOnErrorListener(this);
            mRecorder.prepare();
            mRecorder.start();
        }catch (IOException exception){
            Log.e(TAG, "<initAndStartMediaRecorder> IO exception");
            // M:Add for when error ,the tmp file should been delete.
            handleException(true, exception);
            recorderListener.onError(this, ErrorHandle.ERROR_RECORDING_FAILED);
        } catch (NullPointerException exception) {
            handleException(true, exception);
            return false;
        }catch (RuntimeException exception) {
            Log.e(TAG, "<initAndStartMediaRecorder> RuntimeException");
            // M:Add for when error ,the tmp file should been delete.
            handleException(true, exception);
            recorderListener.onError(this, ErrorHandle.ERROR_RECORDER_OCCUPIED);
            return false;
        }
        return true;
    }

    private void handleException(boolean isDeleteSample, Exception exception) {
        Log.d(TAG, "<handleException> the exception is: " + exception);
        exception.printStackTrace();
        if (isDeleteSample && mSampleFile != null) {
            mSampleFile.delete();
        }
        if (mRecorder != null) {
            mRecorder.reset();
            mRecorder.release();
            mRecorder = null;
        }
    }

    private void setState(int state) {
        mCurrentState = state;
        recorderListener.onStateChanged(this, state);
    }

    private boolean createRecordingFile(String mExtension) {
        Log.d(TAG,"seRecorder createRecordingFile");
        String myExtension = mExtension + SAMPLE_SUFFIX;
        File sampleDir = null;
        if(null == mStorageManager){
            return false;
        }
        String sampleDirPath = STORAGE_PATH_SHARE_SD + File.separator + RECORD_FOLDER;
        sampleDir = new File(sampleDirPath);

        // find a available name of recording folder,
        // Recording/Recording(1)/Recording(2)
        int dirID = 1;
        while ((null != sampleDir) && sampleDir.exists() && !sampleDir.isDirectory()) {
            sampleDir = new File(sampleDirPath + '(' + dirID + ')');
            dirID++;
        }

        if ((null != sampleDir) && !sampleDir.exists() && !sampleDir.mkdirs()) {
            Log.d(TAG, "<createRecordingFile> make directory [" + sampleDir.getAbsolutePath()
                    + "] fail");
        }

        boolean isCreateSuccess = true;
        try {
            if (null != sampleDir) {
                Log.d(TAG, "<createRecordingFile> sample directory  is:"
                        + sampleDir.toString());
            }
            SimpleDateFormat simpleDateFormat = new SimpleDateFormat("yyyyMMddHHmmss");
            mCreateFileTime = System.currentTimeMillis();
            String time = simpleDateFormat.format(new Date(mCreateFileTime));
            StringBuilder stringBuilder = new StringBuilder();
            stringBuilder.append(SAMPLE_PREFIX).append(time).append(myExtension);
            String name = stringBuilder.toString();
            mSampleFile = new File(sampleDir, name);
            isCreateSuccess = mSampleFile.createNewFile();
            Log.d(TAG, "<createRecordingFile> creat file success is " + isCreateSuccess);
            Log.d(TAG, "<createRecordingFile> mSampleFile.getAbsolutePath() is: "
                    + mSampleFile.getAbsolutePath());
        } catch (IOException e) {
            recorderListener.onError(this, ErrorHandle.ERROR_CREATE_FILE_FAILED);
            Log.e(TAG, "<createRecordingFile> io exception happens");
            e.printStackTrace();
            isCreateSuccess = false;
        } finally {
            Log.d(TAG, "<createRecordingFile> end");
            return isCreateSuccess;
        }
    }


    /**
     * M: get how long time we has recorded
     * @return the record length, in millseconds
     */
    public long getCurrentProgress() {
        if (SeSoundRecorderService.STATE_RECORDING == mCurrentState) {
            long current = SystemClock.elapsedRealtime();
            return (long) (current - mSampleStart);
        }
        return 0;
    }

    public String getSampleFilePath() {
        return (null == mSampleFile) ? null : mSampleFile.getAbsolutePath();
    }

    public long getSampleLength() {
        return mSampleLength;
    }

    public File getSampFile() {
        return mSampleFile;
    }

    public long getCreateFileTime(){return mCreateFileTime;}

}
