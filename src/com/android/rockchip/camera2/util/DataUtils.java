package com.android.rockchip.camera2.util;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

public class DataUtils {
    public static final String INPUT_ID = "com.example.partnersupportsampletvinput/.SampleTvInputService/HW0";
    public static final long LIMIT_DOUBLE_CLICK_TIME = 1000;
    public static final long START_TV_REVIEW_DELAY = 500;
    public static final long MAIN_REQUEST_SCREENSHOT_START_DELAYED = 100;
    public static final long MAIN_REQUEST_SCREENSHOT_DELAYED = 200;
    public static final long MAIN_ENABLE_SETTINGS_DEALY = 1000;

    public static final String EXTRA_ASSIGN_CAMERA_ID = "extra_assign_cameraid";
    public static final String EXTRA_ASSIGN_CAMERA_TYPR = "extra_assign_cameratype";//0:mipi 1:rx

    public static final int VIDEO_RECORD_BIT_RATE = 6000000;
    public static final int VIDEO_RECORD_FRAME_RATE = 30;
    public static final int AUDIO_RECORD_TOTAL_NUM_TRACKS = 1;
    public static final int AUDIO_RECORD_BIT_RATE = 16;
    public static final int AUDIO_RECORD_SAMPLE_RATE = 44100;

    public static final String HDMIIN_AUDIO_PACKAGE_NAME = "com.rockchip.rkhdmiinaudio";
    public static final String HDMIIN_AUDIO_CLS_NAME = "com.rockchip.rkhdmiinaudio.HdmiInAudioService";

    public static final String STORAGE_PATH_NAME = "hdmiin";

    public static final String PERSIST_HDMIIN_TYPE = "vendor.tvinput.hdmiin.type";

    public static void startHdmiAudioService(Context context) {
        Log.v("HdmiIn", "startHdmiAudioService");
        SystemPropertiesProxy.set("vendor.hdmiin.audiorate", "48KHZ");
        Intent intent = new Intent();
        ComponentName cn = new ComponentName(DataUtils.HDMIIN_AUDIO_PACKAGE_NAME, DataUtils.HDMIIN_AUDIO_CLS_NAME);
        intent.setComponent(cn);
        context.startForegroundService(intent);
    }

    public static void stopHdmiAudioService(Context context) {
        Log.v("HdmiIn", "stopHdmiAudioService");
        Intent intent = new Intent();
        ComponentName cn = new ComponentName(DataUtils.HDMIIN_AUDIO_PACKAGE_NAME, DataUtils.HDMIIN_AUDIO_CLS_NAME);
        intent.setComponent(cn);
        context.stopService(intent);
    }
}
