package com.android.rockchip.camera2.activity;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Point;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.Display;
import android.view.Surface;
import android.view.WindowManager;

import java.nio.ByteBuffer;

public class BaseActivity extends Activity {
    protected String TAG = "BaseActivity";

    private final int MEDIA_PROJECTION_REQUEST_CODE = 0x11110001;
    private final int SCREEN_SHOT_TIME_OUT = 300;
    private final int MSG_CAPTURE_SCREEN = 0x11110001;
    private final int MSG_CAPTURE_SCREEN_FINISH = 0x11110002;

    private MediaProjection mMediaProjection;
    private MediaProjectionManager mProjectionManager;
    private ScreenShotCallBack mScreenShotCallback;
    private VirtualDisplay mVirtualDisplay;
    private ImageReader mImageReader;
    private int mScreenShotWidth;
    private int mScreenShotHeight;
    private boolean mEnableCapturePic;
    private boolean mScreenshotPrepared = true;
    private MyThread mThread;

    private Handler mScreenshotHandler = new Handler() {
        @Override
        public void handleMessage(Message msg) {
            super.handleMessage(msg);
            if (isDestroyed()) {
                return;
            }
            if (MSG_CAPTURE_SCREEN == msg.what) {
                if (null != mThread) {
                    mThread.setCancel(true);
                }
                mThread = new MyThread();
                mThread.start();
            } else if (MSG_CAPTURE_SCREEN_FINISH == msg.what) {
                if (null != mScreenShotCallback) {
                    if (null == msg.obj) {
                        mScreenShotCallback.onScreenshotFinish(null);
                    } else {
                        mScreenShotCallback.onScreenshotFinish((Bitmap) msg.obj);
                    }
                    mScreenshotPrepared = true;
                }
            }
        }
    };

    private class MyThread extends Thread {
        private boolean cancel;

        public void setCancel(boolean cancel) {
            this.cancel = cancel;
        }

        @Override
        public void run() {
            Log.v(TAG, "start captureImage");
            captureImage(mImageReader);
            Log.v(TAG, "end captureImage");
        }

        private void captureImage(ImageReader reader) {
            if (null == reader || !mEnableCapturePic) {
                return;
            }
            boolean needRelease = false;
            Bitmap bitmap = null;
            try (Image image = reader.acquireLatestImage()) {
                if (null != image) {
                    Image.Plane[] planes = image.getPlanes();
                    ByteBuffer buffer = planes[0].getBuffer();
                    int pixelStride = planes[0].getPixelStride();
                    int rowStride = planes[0].getRowStride();
                    int rowPadding = rowStride - pixelStride * mScreenShotWidth;
                    int width = mScreenShotWidth + rowPadding / pixelStride;
                    int height = mScreenShotHeight;
                    bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
                    if (cancel) {
                        bitmap.recycle();
                    } else {
                        bitmap.copyPixelsFromBuffer(buffer);
                    }
                    mEnableCapturePic = false;
                    needRelease = true;
                    image.close();
                }
            } catch (Exception e) {
                e.printStackTrace();
                needRelease = true;
            } finally {
                if (needRelease) {
                    releaseRes();
                    Message message = new Message();
                    message.what = MSG_CAPTURE_SCREEN_FINISH;
                    message.obj = bitmap;
                    mScreenshotHandler.sendMessage(message);
                }
            }
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        mProjectionManager = (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        Log.v(TAG, "onActivityResult requestCode=" + requestCode + ", resultCode=" + resultCode + ", " + data);
        if (requestCode == MEDIA_PROJECTION_REQUEST_CODE) {
            if (null == data) {
                if (null != mScreenShotCallback) {
                    mScreenShotCallback.onScreenshotFinish(null);
                    mScreenshotPrepared = true;
                }
            } else {
                mMediaProjection = mProjectionManager.getMediaProjection(resultCode, data);
                createVirtualDisplay();
            }
        }
    }

    private void createVirtualDisplay() {
        DisplayMetrics metrics = getResources().getDisplayMetrics();
        WindowManager wm = (WindowManager) getSystemService(Context.WINDOW_SERVICE);
        Display display = wm.getDefaultDisplay();
        Point size = new Point();
        display.getRealSize(size);
        mScreenShotWidth = size.x;
        mScreenShotHeight = size.y;
        mImageReader = ImageReader.newInstance(mScreenShotWidth, mScreenShotHeight, 0x01, 2);
        Surface mInputSurface = mImageReader.getSurface();
        mVirtualDisplay = mMediaProjection.createVirtualDisplay("hdmi in",
                mScreenShotWidth, mScreenShotHeight, metrics.densityDpi, DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                mInputSurface, null, null);
        if (null == mVirtualDisplay) {
            mScreenShotCallback.onScreenshotFinish(null);
            mScreenshotPrepared = true;
            return;
        }
        mEnableCapturePic = true;
        mScreenshotHandler.removeMessages(MSG_CAPTURE_SCREEN);
        mScreenshotHandler.sendEmptyMessageDelayed(MSG_CAPTURE_SCREEN, SCREEN_SHOT_TIME_OUT);
    }

    @Override
    protected void onPause() {
        super.onPause();
        Log.v(TAG, "onPause()");
        mScreenshotHandler.removeMessages(MSG_CAPTURE_SCREEN);
        releaseRes();
    }

    private synchronized void releaseRes() {
        Log.v(TAG, "releaseRes");
        mEnableCapturePic = false;
        if (null != mImageReader) {
            mImageReader.close();
            mImageReader = null;
        }
        if (null != mVirtualDisplay) {
            mVirtualDisplay.release();
            mVirtualDisplay = null;
        }
        if (null != mMediaProjection) {
            mMediaProjection.stop();
            mMediaProjection = null;
        }
    }

    protected void startScreenShot(ScreenShotCallBack callBack) {
        if (null == callBack) {
            return;
        }
        if (!mScreenshotPrepared) {
            callBack.onScreenshotFinish(null);
            return;
        }
        mScreenshotPrepared = false;
        mScreenShotCallback = callBack;
        Intent intent = new Intent(mProjectionManager.createScreenCaptureIntent());
        startActivityForResult(intent, MEDIA_PROJECTION_REQUEST_CODE);
    }

    public interface ScreenShotCallBack {
        void onScreenshotFinish(Bitmap bitmap);
    }
}
