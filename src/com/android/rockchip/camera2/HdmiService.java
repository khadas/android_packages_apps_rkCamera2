package com.android.rockchip.camera2;

import com.android.rockchip.camera2.util.JniCameraCall;

import android.app.Service;
import android.util.Log;
import android.util.Size;
import android.content.Intent;
import android.os.Binder;
import android.os.IBinder;
import android.os.SystemClock;

public class HdmiService extends Service {

    private static String TAG = "HdmiService";

    private boolean loop = true;
    private boolean isHdmiIn = false;
    private final boolean debug = false;
    private OnHdmiStatusListener mOnHdmiStatusListener;
    private Size curDriverDimension = null;

    Runnable mScanHdmiIn = new Runnable() {

        @Override
        public void run() {
            isHdmiIn = false;
            while (loop) {
                int[] format = JniCameraCall.getFormat();
                if (format != null && format.length > 0) {
                    curDriverDimension = new Size(format[0],format[1]);
                    if (debug)
                        Log.i(TAG, "format != null format[2] = " + format[2]);
                    if (format[2] != 0 && isHdmiIn != true) {
                        Log.i(TAG, "hdmi is plug");
                        isHdmiIn = true;
                        //wait activity bind service success
			while (mOnHdmiStatusListener == null) {
				try {
					Thread.sleep(200);
				} catch(InterruptedException e) {
					e.printStackTrace();
				}
				if (!loop) break;
			}
                        if (mOnHdmiStatusListener != null) {
                            mOnHdmiStatusListener.onHdmiStatusChange(isHdmiIn, curDriverDimension);
                        }
                    } else if (format[2] == 0 && isHdmiIn != false) {
                        Log.i(TAG, "hdmi is unplug");
                        isHdmiIn = false;
                        //wait activity bind service success
			while (mOnHdmiStatusListener == null) {
				try {
					Thread.sleep(200);
				} catch(InterruptedException e) {
					e.printStackTrace();
				}
				if (!loop) break;
			}
                        if (mOnHdmiStatusListener != null) {
                            mOnHdmiStatusListener.onHdmiStatusChange(isHdmiIn, curDriverDimension);
                        }
                    } else {
                        // Log.i(TAG, "hdmi is no change");
                    }
                    curDriverDimension = null;
                }
                SystemClock.sleep(500);
            }
        }
    };

    /**
     * OnHdmiStatusListener
     */
    public interface OnHdmiStatusListener {
        void onHdmiStatusChange(boolean isHdmiIn, Size driverDimension);
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
        Log.i(TAG, "HdmiService onCreate()");
        new Thread(mScanHdmiIn).start();
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        Log.i(TAG, "HdmiService onDestroy()");
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
