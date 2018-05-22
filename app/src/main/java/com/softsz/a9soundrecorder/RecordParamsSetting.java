package com.softsz.a9soundrecorder;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Resources;
import android.util.Log;

/**
 * M: We use this class to do operations related with record params.
 * When recording, we can get all record params according to input params.
 */
public class RecordParamsSetting {

    private static Resources sResources = null;
    private static SharedPreferences sPreferences = null;
    public static final String RECORD_PARAM = "record_params";
    private static String INIT_VALUES = "init_values";
    private static final  String TAG = "RecordParamsSetting";

    public static final String SAMPLE_RATE = "sample_rate";
    public static final String ENCODE_BITRATE = "encode_bitrate";
    public static final String ENCODER = "encoder";
    public static final String AUDIO_CHANNELS = "audio_channels";
    public static final String OUTPUT_FORMAT = "output_format";

    public static int defaultParams[] = null;

    public static final String AUDIO_3GPP = "audio/3gpp";

    //M: All params will be used when record
    static public class RecordParams {
        public int mRemainingTimeCalculatorBitRate = -1;
        public int mAudioChannels = 0;
        public int mAudioEncoder = -1;
        public int mAudioEncodingBitRate = -1;
        public int mAudioSamplingRate = -1;
        public String mExtension = "";
        public String mMimeType = "";
        public int mOutputFormat = -1;
    }

    static RecordParams getRecordParams(){
        RecordParams recordParams = new RecordParams();
        recordParams.mAudioEncoder = sPreferences.getInt(ENCODER,defaultParams[0]);
        recordParams.mAudioChannels = sPreferences.getInt(AUDIO_CHANNELS,defaultParams[1]);
        recordParams.mAudioEncodingBitRate = sPreferences.getInt(ENCODE_BITRATE,defaultParams[2]);
        recordParams.mAudioSamplingRate = sPreferences.getInt(SAMPLE_RATE,defaultParams[3]);
        recordParams.mOutputFormat = sPreferences.getInt(OUTPUT_FORMAT,defaultParams[4]);
        recordParams.mExtension = ".3gpp";
        recordParams.mMimeType = RecordParamsSetting.AUDIO_3GPP;
        recordParams.mRemainingTimeCalculatorBitRate = recordParams.mAudioEncodingBitRate;
        return recordParams;
    }

    /**
     * M: If is the first time to use the "record_params" preference, we will
     * set the default values to initialize the preference.
     *
     * @param context
     */
    static void initRecordParamsSharedPreference(Context context) {
        sResources = context.getResources();
        sPreferences = context.getSharedPreferences(RECORD_PARAM, 0);
        defaultParams = sResources.getIntArray(R.array.default_params);
        boolean isFirstSet = sPreferences.getBoolean(INIT_VALUES, true);
        Log.d(TAG, "isFirstSet is:" + isFirstSet);
        if (isFirstSet) {
            SharedPreferences.Editor editor = sPreferences.edit();
            editor.putBoolean(INIT_VALUES, false);
            editor.putInt(ENCODER, defaultParams[0]);
            editor.putInt(AUDIO_CHANNELS, defaultParams[1]);
            editor.putInt(ENCODE_BITRATE, defaultParams[2]);
            editor.putInt(SAMPLE_RATE, defaultParams[3]);
            editor.putInt(OUTPUT_FORMAT, defaultParams[4]);
            editor.commit();
        }
    }
}
