package com.softsz.a9soundrecorder;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.ActivityInfo;
import android.content.res.Resources;
import android.nfc.Tag;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Message;
import android.util.Log;
import android.view.KeyEvent;
import android.view.View;
import android.view.Window;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;

public class SeSoundRecorder extends Activity implements View.OnClickListener, SeSoundRecorderService.OnUpdateButtonState
        , SeSoundRecorderService.OnStateChangedListener, SeSoundRecorderService.OnErrorListener, SeSoundRecorderService.OnUpdateTimeViewListener {

    public static String TAG = "SeSoundRecorder";
    private Button recordOrStopButton = null;
    private Button playbackButton = null;
    private TextView fileNameView = null;
    private TextView recordTimerView = null;
    private ImageView recordIndicateView = null;
    private ImageView starView;
    private SeSoundRecorderService mSSRService = null;
    private Resources mResources;

    private String mFileName = "";
    public static final String ERROR_CODE = "errorCode";

    private int currentStatus = SeSoundRecorderService.STATE_IDLE;
    private boolean mIsRecordStarting = false;
    private String mTimerFormat = null;
    private static final int TIME_BASE = 60;
    private boolean mIsStopService = false;

    private String openWithStart = "false";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Intent startIntent = getIntent();
        openWithStart = startIntent.getStringExtra("open_with_start");
        Log.d(TAG, "openWithStart :" + openWithStart);
        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);
        getWindow().requestFeature(Window.FEATURE_NO_TITLE);
        setContentView(R.layout.activity_sound_recorder);
        initView();
        mResources = getResources();
        //initialize the record params
        RecordParamsSetting.initRecordParamsSharedPreference(this);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (mSSRService == null) {
            Intent intent = new Intent(SeSoundRecorder.this, SeSoundRecorderService.class);
            if (null == startService(intent)) {
                finish();
                return;
            }

            if (!bindService(intent, mSConn, BIND_AUTO_CREATE)) {
                finish();
                return;
            }
        } else {
            initWhenHaveService();
        }

        if (SeSoundRecorderService.isStarFile) {
            setAsStarView();
        }

    }

    @Override
    protected void onStop() {
        Log.d(TAG, "<onStop> start, Activity = " + this.toString());
        if (mSSRService != null) {

            boolean stopService = (mSSRService.getCurrentState() == SeSoundRecorderService.STATE_IDLE) && !mSSRService.isCurrentFileWaitToSave();

            // M: if another instance of soundrecorder has been resume,
            // the listener of service has changed to another instance, so we
            // cannot call setAllListenerSelf
            boolean isListener = mSSRService.isListener(SeSoundRecorder.this);
            Log.d(TAG, "<onStop> isListener = " + isListener);
            if (isListener) {
                // set listener of service as default,
                // so when error occurs, service can show error info in toast
                mSSRService.setAllListenerSelf();
            }

            Log.i(TAG, "<onStop> unbind service");
            unbindService(mSConn);

            mIsStopService = stopService && isListener;
            mSSRService = null;
        }
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        Log.d(TAG, "<onDestroy> start, Activity = " + this.toString());
        if (mIsStopService) {
            Log.d(TAG, "<onDestroy> stop service");
            stopService(new Intent(SeSoundRecorder.this, SeSoundRecorderService.class));
        }
        super.onDestroy();
    }

    public void initView() {
        recordOrStopButton = (Button) findViewById(R.id.record_or_stop);
        recordOrStopButton.setOnClickListener(this);

        playbackButton = (Button) findViewById(R.id.record_palyback);
        playbackButton.setOnClickListener(this);

        fileNameView = (TextView) findViewById(R.id.record_name);
        recordTimerView = (TextView) findViewById(R.id.record_duration);
        recordIndicateView = (ImageView) findViewById(R.id.indicate_view);
        starView = (ImageView) findViewById(R.id.important_mark);

        mTimerFormat = getResources().getString(R.string.timer_format);

    }

    private ServiceConnection mSConn = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            SeSoundRecorderService.SSRBinder mSSRBinder = (SeSoundRecorderService.SSRBinder) service;
            mSSRService = mSSRBinder.getService();
            currentStatus = mSSRService.getCurrentState();
            initWhenHaveService();
            if (openWithStart != null) {
                if (openWithStart.equals("true")) {
                    openWithStart = "false";
                    onClickToRecord();
                }
            }
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            mSSRService = null;
        }
    };

    private void initWhenHaveService() {
        Log.d(TAG, "<initWhenHaveService> start");
        mSSRService.setErrorListener(SeSoundRecorder.this);
        mSSRService.setStateChangedListener(SeSoundRecorder.this);
        mSSRService.setUpdateTimeViewListener(SeSoundRecorder.this);
        mHandler.sendEmptyMessage(mSSRService.getCurrentState());
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == 131) {
            if (mSSRService.getCurrentState() != SeSoundRecorderService.STATE_RECORDING){
                onClickToRecord();
            }else if (mSSRService.getCurrentState() == SeSoundRecorderService.STATE_RECORDING){
                onClickToStop();
            }
            return true;
        }
        if (keyCode == 132) {
            if (mSSRService.getCurrentState() == SeSoundRecorderService.STATE_RECORDING && (starView.getVisibility() != View.VISIBLE)) {
                setAsStarView();
            } else if (mSSRService.getCurrentState() != SeSoundRecorderService.STATE_RECORDING){
                goPlayback();
            }
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    private void setAsStarView() {
        starView.setVisibility(View.VISIBLE);
        SeSoundRecorderService.isStarFile = true;
        fileNameView.setText("IMP_" + mFileName);
    }

    @Override
    public void onClick(View v) {
        int id = v.getId();
        if (isFinishing()) {
            return;
        }

        if (!v.isEnabled()) {
            return;
        }

        switch (id) {
            case R.id.record_or_stop:
                if (currentStatus == SeSoundRecorderService.STATE_IDLE) {
                    Log.d(TAG, "onclick to onClickToRecord()");
                    onClickToRecord();
                } else if (currentStatus == SeSoundRecorderService.STATE_RECORDING) {
                    Log.d(TAG, "onclick to onClickToStop()");
                    onClickToStop();
                }
                break;
            case R.id.record_palyback:
                goPlayback();
                break;
        }
    }

    private void goPlayback() {
        Intent intent = new Intent();
        intent.setComponent(new ComponentName("com.softsz.a9palyback", "com.softsz.a9palyback.PasswordActivity"));
        try {
            startActivity(intent);
        } catch (ActivityNotFoundException e) {
            e.printStackTrace();
        }
    }

    /**
     * process after click record button
     */
    private void onClickToStop() {
        if (null == mSSRService) return;
        mSSRService.doStop(this);

    }

    /**
     * process after click stop button
     */
    private void onClickToRecord() {
        if (null != mSSRService) {
            mIsRecordStarting = true;
            mSSRService.startRecordingAsync(RecordParamsSetting.getRecordParams(), this);
        }
    }

    public void setCurrentStatus(int status) {
        currentStatus = status;
    }

    public int getCurrentStatus() {
        return currentStatus;
    }

    @Override
    public void updateButtonState(int recordState) {
        mHandler.removeMessages(recordState);
        mHandler.sendEmptyMessage(recordState);
    }

    @Override
    public void onError(int errorCode) {
        Log.d(TAG, "<onError> errorCode = " + errorCode);
        Bundle bundle = new Bundle(1);
        bundle.putInt(ERROR_CODE, errorCode);
        Message msg = mHandler.obtainMessage(SeSoundRecorderService.STATE_ERROR);
        msg.setData(bundle);
        mHandler.sendMessage(msg);
    }

    @Override
    public void onStateChanged(int stateCode) {
        setCurrentStatus(stateCode);
        mHandler.removeMessages(stateCode);
        mHandler.sendEmptyMessage(stateCode);
    }

    @Override
    public void updateTimerView(int time) {
        setTimerTextView(time);
    }

    private void setTimerTextView(int time) {
        int h = time / (TIME_BASE * TIME_BASE);
        int m = (time / TIME_BASE) - (h * TIME_BASE);
        String timerString = String.format(mTimerFormat, h, m, time % TIME_BASE);
        recordTimerView.setText(timerString);
    }


    /**
     * Shows/hides the appropriate child views for the new state. M: use
     * different function in different state to update UI
     */
    private Handler mHandler = new Handler() {
        @Override
        public void handleMessage(Message msg) {
            Log.d(TAG, "<handleMessage> start with msg.what-" + msg.what);
            if (null == mSSRService || SeSoundRecorder.this.isFinishing()) {
                return;
            }
            String filePath = mSSRService.getCurrentFilePath();
            Log.d(TAG, "<handleMessage> mService.getCurrentFilePath() = " + filePath);
            if (null != filePath) {
                mFileName = filePath.substring(filePath.lastIndexOf(File.separator) + 1, filePath
                        .length());
                mFileName = (mFileName.endsWith(SeRecorder.SAMPLE_SUFFIX)) ? mFileName.substring(0, mFileName.lastIndexOf(SeRecorder.SAMPLE_SUFFIX)) : mFileName;
            }
            if (SeSoundRecorderService.isStarFile) {
                mFileName = "IMP_" + mFileName;
            }
            Log.d(TAG, "<updateUi> mRecordingFileNameTextView.setText : " + mFileName);
            fileNameView.setText(mFileName);
            switch (msg.what) {
                case SeSoundRecorderService.STATE_IDLE:
                    updateUiOnIdleState();
                    break;

                case SeSoundRecorderService.STATE_RECORDING:
                    updateUiOnRecordingState();
                    break;
                case SeSoundRecorderService.STATE_ERROR:
                    Bundle bundle = msg.getData();
                    int errorCode = bundle.getInt(ERROR_CODE);
                    ErrorHandle.showErrorInfo(SeSoundRecorder.this, errorCode);
                    if (mSSRService != null) {
                        updateUiAccordingState(mSSRService.getCurrentState());
                    }
                    break;
                case SeSoundRecorderService.STATE_SAVE_SUCESS:
                    updateUiOnSaveSuccessState();
                    Toast.makeText(SeSoundRecorder.this, getResources().getString(R.string.tell_save_record_success), Toast.LENGTH_LONG).show();
                    break;
                default:
                    break;
            }
        }
    };

    private void updateUiOnSaveSuccessState() {

    }

    private void updateUiAccordingState(int currentState) {
        if (currentState == SeSoundRecorderService.STATE_IDLE) {
            updateUiOnIdleState();
        } else if (currentState == SeSoundRecorderService.STATE_RECORDING) {
            updateUiOnRecordingState();
        } else {
            updateUiOnIdleState();
            recordOrStopButton.setEnabled(false);
        }
    }

    private void updateUiOnIdleState() {
        recordOrStopButton.setText(mResources.getString(R.string.start));
        recordIndicateView.setImageDrawable(mResources.getDrawable(R.drawable.stop));
        setTimerTextView(0);
        fileNameView.setText("");
        starView.setVisibility(View.GONE);
    }

    private void updateUiOnRecordingState() {
        recordOrStopButton.setText(mResources.getString(R.string.stop));
        recordIndicateView.setImageDrawable(mResources.getDrawable(R.drawable.recording));
    }

    public String getOpenWithStart() {
        return openWithStart;
    }
}
