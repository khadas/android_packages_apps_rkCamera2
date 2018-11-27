package com.android.rockchip.camera2;

import com.android.rockchip.camera2.util.JniCameraCall;

import android.app.Service;
import android.util.Log;
import android.content.Intent;
import android.os.Binder;
import android.os.IBinder;
import android.os.SystemClock;

public class HdmiService extends Service {

    private static String TAG = "HdmiService";

    private boolean loop = true;
    private boolean isHdmiIn = false;
    private OnHdmiStatusListener mOnHdmiStatusListener;

    Runnable mScanHdmiIn = new Runnable() {

        @Override
        public void run() {
            isHdmiIn = false;
            while (loop) {
                int[] format = JniCameraCall.getFormat();
                if (format != null && format.length > 0) {
                    Log.i(TAG, "format != null format[2] = " + format[2]);
                    if (format[2] != 0 && isHdmiIn != true) {
                        Log.i(TAG, "hdmi is plug");
                        isHdmiIn = true;
                        if (mOnHdmiStatusListener != null) {
                            mOnHdmiStatusListener.onHdmiStatusChange(isHdmiIn);
                        }
                    } else if (format[2] == 0 && isHdmiIn != false) {
                        Log.i(TAG, "hdmi is unplug");
                        isHdmiIn = false;
                        if (mOnHdmiStatusListener != null) {
                            mOnHdmiStatusListener.onHdmiStatusChange(isHdmiIn);
                        }
                    } else {
                        // Log.i(TAG, "hdmi is no change");
                    }
                }
                SystemClock.sleep(500);
            }
        }
    };

    /**
     * OnHdmiStatusListener
     */
    public interface OnHdmiStatusListener {
        void onHdmiStatusChange(boolean isHdmiIn);
    }

    /**
     * HdmiBinder
     */
    public class HdmiBinder extends Binder {
        /**
         * 获取当前Service的实例
         *
         * @return
         */
        public HdmiService getService() {
            return HdmiService.this;
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        Log.e(TAG, "HdmiService onCreate()");
        new Thread(mScanHdmiIn).start();
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        Log.e(TAG, "HdmiService onDestroy()");
        loop = false;
        this.mOnHdmiStatusListener = null;
    }

    /**
     * 返回一个Binder对象
     */
    @Override
    public IBinder onBind(Intent intent) {
        return new HdmiBinder();
    }

    @Override
    public boolean onUnbind(Intent intent) {
        Log.i(TAG, "onUnbind");
        loop = false;
        return super.onUnbind(intent);
    }

    public void setOnHdmiStatusListener(OnHdmiStatusListener hdmiStatusListener) {
        this.mOnHdmiStatusListener = hdmiStatusListener;
        if (hdmiStatusListener == null) {
            stopSelf();
        }
    }
}
