package com.android.rockchip.camera2.activity;

import android.app.Activity;
import android.app.PictureInPictureParams;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Point;
import android.graphics.SurfaceTexture;
import android.graphics.drawable.ColorDrawable;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CameraMetadata;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.MediaMetadataRetriever;
import android.media.tv.TvContract;
import android.media.tv.TvView;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Message;
import android.os.RemoteException;
import android.support.annotation.NonNull;
import android.text.TextUtils;
import android.util.Log;
import android.util.Rational;
import android.util.Size;
import android.view.Display;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.PopupWindow;
import android.widget.RelativeLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.android.rockchip.camera2.R;
import com.android.rockchip.camera2.util.BitmapUtil;
import com.android.rockchip.camera2.util.DataUtils;
import com.android.rockchip.camera2.util.SystemPropertiesProxy;

import java.io.DataOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.concurrent.TimeUnit;

import rockchip.hardware.hdmi.V1_0.IHdmi;
import rockchip.hardware.hdmi.V1_0.IHdmiCallback;

public class MainActivity extends Activity implements
        View.OnAttachStateChangeListener, View.OnClickListener {
    protected String TAG = "MainActivity";

    private final String HDMI_OUT_ACTION = "android.intent.action.HDMI_PLUGGED";
    private final String DP_OUT_ACTION = "android.intent.action.DP_PLUGGED";
    private final int MODE_TV = 0;
    private final int MODE_CAMERA = 1;

    private final int MSG_START_TV = 0;
    private final int MSG_ENABLE_SETTINGS = 1;
    private final int MSG_SCREENSHOT_START = 2;
    private final int MSG_SCREENSHOT_FINISH = 3;
    private final int MSG_SWITCH_MODE = 4;

    private RelativeLayout rootView;
    private TvView tvView;
    private TextureView textureView;
    private PopupWindow mPopSettings;
    private Button btn_screenshot;
    private Button btn_pip;

    private Object mLock = new Object();
    private Uri mChannelUri;
    private MyBroadCastReceiver mBroadCastReceiver;
    private boolean mPopSettingsPrepared;
    private boolean mResumePrepared;
    private boolean mTvSurfacePrepared;
    private boolean mAlreadyTvTune;
    private boolean mIsDestory;
    private long mLastClickTime;
    private String mCurrentSavePath;
    private WindowManager mWindowManager;
    private PictureInPictureParams.Builder mPictureInPictureParamsBuilder;
    private HdmiCallback mHdmiCallback;
    private int mCameraSensorOrientation;
    private CameraDevice cameraDevice;
    private CameraCaptureSession cameraCaptureSessions;
    private CaptureRequest.Builder captureRequestBuilder;
    private Size imageDimension = new Size(1920, 1080);
    private Handler mBackgroundHandler;
    private HandlerThread mBackgroundThread;
    private boolean mExitAppWithPip;

    private class MyBitmapSaveThread extends Thread {
        private Bitmap bitmap;

        MyBitmapSaveThread(Bitmap bitmap) {
            this.bitmap = bitmap;
        }

        @Override
        public void run() {
            String path = getSavePath(".jpg");
            Log.v(TAG, "start save to " + path);
            BitmapUtil.saveBitmap2file(bitmap, path);
            if (null != bitmap && !bitmap.isRecycled()) {
                bitmap.recycle();
            }
            addSaveFileToDb(path);
            Message message = new Message();
            message.what = MSG_SCREENSHOT_FINISH;
            message.obj = path;
            mHandler.sendMessageDelayed(message, DataUtils.MAIN_REQUEST_SCREENSHOT_DELAYED);
        }
    }

    class HdmiCallback extends IHdmiCallback.Stub {
        public HdmiCallback() {
        }

        public void onConnect(String cameraId) throws RemoteException {
            Log.e(TAG, "onConnect" + cameraId);
            openCamera();
        }

        public void onFormatChange(String cameraId, int width, int height) throws RemoteException {
            Log.e(TAG, "onFormatChange" + cameraId);
            closeCamera();
            imageDimension = new Size(width, height);
            openCamera();
        }

        public void onDisconnect(String cameraId) throws RemoteException {
            Log.e(TAG, "onDisconnect" + cameraId);
            closeCamera();
        }
    }

    private Handler mHandler = new Handler() {
        @Override
        public void handleMessage(Message msg) {
            super.handleMessage(msg);
            if (mIsDestory) {
                return;
            }
            synchronized (mLock) {
                if (mIsDestory) {
                    return;
                }
                Log.v(TAG, "deal message " + msg.what);
                if (MSG_START_TV == msg.what) {
                    if (mResumePrepared && mTvSurfacePrepared
                            && !mAlreadyTvTune) {
                        mAlreadyTvTune = true;
                        tvView.tune(DataUtils.INPUT_ID, mChannelUri);
                    }
                } else if (MSG_ENABLE_SETTINGS == msg.what) {
                    mPopSettingsPrepared = true;
                } else if (MSG_SCREENSHOT_START == msg.what) {
                    Bitmap bitmap = startScreenShot(getSaveDir() + "hdmiin.temp");
                    if (null == bitmap) {
                        btn_screenshot.setEnabled(true);
                        showToast(R.string.screenshot_failed);
                    } else {
                        new MyBitmapSaveThread(bitmap).start();
                    }
                } else if (MSG_SCREENSHOT_FINISH == msg.what) {
                    showToast(String.valueOf(msg.obj));
                    btn_screenshot.setEnabled(true);
                } else if (MSG_SWITCH_MODE == msg.what) {
                    Log.v(TAG, "switch mode to " + (msg.arg1 == MODE_TV ? "tv" : "camera"));
                    if (msg.arg1 == MODE_CAMERA) {
                        pauseSideband();
                        resumeCamera();
                    } else if (msg.arg1 == MODE_TV) {
                        pauseCamera();
                        resumeSideband();
                    }
                }
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        fullScreen();
        rootView = (RelativeLayout) findViewById(R.id.root_view);
        rootView.setOnClickListener(this);

        mChannelUri = TvContract.buildChannelUriForPassthroughInput(DataUtils.INPUT_ID);
        registerReceiver();

        initPopSettingsWindow();
        mWindowManager = (WindowManager) getSystemService(Context.WINDOW_SERVICE);
        mExitAppWithPip = false;
    }

    private void fullScreen() {
        getWindow().getDecorView().getRootView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                        | View.SYSTEM_UI_FLAG_LOW_PROFILE);
    }

    private void initPopSettingsWindow() {
        View view = LayoutInflater.from(this).inflate(R.layout.layout_main_pop_settings, null, false);
        mPopSettings = new PopupWindow(view,
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, true);
        mPopSettings.setTouchable(true);
        mPopSettings.setBackgroundDrawable(new ColorDrawable(0x00000000));
        mPopSettings.setClippingEnabled(false);
        //normal ui
        btn_screenshot = view.findViewById(R.id.btn_screenshot);
        btn_screenshot.setOnClickListener(this);
        btn_pip = view.findViewById(R.id.btn_pip);
        btn_pip.setOnClickListener(this);
        mPopSettingsPrepared = false;
    }

    private void initSideband() {
        mResumePrepared = false;
        mTvSurfacePrepared = false;

        if (null != tvView) {
            rootView.removeView(tvView);
        }
        tvView = new TvView(this);
        tvView.addOnAttachStateChangeListener(this);
        tvView.setOnClickListener(this);
        rootView.addView(tvView);
    }

    private void resumeSideband() {
        initSideband();
        mResumePrepared = true;
        mHandler.removeMessages(MSG_START_TV);
        mHandler.sendEmptyMessageDelayed(MSG_START_TV, DataUtils.START_TV_REVIEW_DELAY);
        Log.v(TAG, "resumeSideband");
    }

    private void resumeCamera() {
        if (textureView == null) {
            registerHdmiCallback();
            createTextureView();
        }
        startBackgroundThread();
        Log.v(TAG, "resumeCamera");
    }

    private void pauseSideband() {
        mResumePrepared = false;
        mAlreadyTvTune = false;
        if (null != tvView) {
            tvView.reset();
            rootView.removeView(tvView);
        }
        Log.v(TAG, "pauseSideband");
    }

    private void pauseCamera() {
        Log.v(TAG, "pauseCamera start");
        unregisterHdmiCallback();
        closeCamera();
        // JniCameraCall.closeDevice();
        stopBackgroundThread();
        if (textureView != null) {
            rootView.removeView(textureView);
            textureView = null;
        }
        Log.v(TAG, "pauseCamera end");
    }

    private void registerReceiver() {
        mBroadCastReceiver = new MyBroadCastReceiver();
        IntentFilter intentFilter = new IntentFilter();
        intentFilter.addAction(Intent.ACTION_CLOSE_SYSTEM_DIALOGS);
        intentFilter.addAction(HDMI_OUT_ACTION);
        intentFilter.addAction(DP_OUT_ACTION);
        intentFilter.addAction(Intent.ACTION_HDMIIN_RK_PRIV_CMD);
        registerReceiver(mBroadCastReceiver, intentFilter);
    }

    private void unregisterReceiver() {
        if (null != mBroadCastReceiver) {
            unregisterReceiver(mBroadCastReceiver);
        }
    }

    private void showToast(int resId) {
        Toast.makeText(this, resId, Toast.LENGTH_SHORT).show();
    }

    private void showToast(String ss) {
        Toast.makeText(this, ss, Toast.LENGTH_SHORT).show();
    }

    private void exitApp() {
        try {
            Thread.sleep(50);
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
        finish();
        try {
            Thread.sleep(100);
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
        System.exit(0);
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (event.getKeyCode() == KeyEvent.KEYCODE_BACK) {
            if (event.getAction() == KeyEvent.ACTION_DOWN) {
                return true;
            }
            if (event.getAction() == KeyEvent.ACTION_UP) {
                showToast(R.string.back_key_warn);
            }
        }
        return super.dispatchKeyEvent(event);
    }

    @Override
    public void onViewAttachedToWindow(View v) {
        Log.v(TAG, "onViewAttachedToWindow");
        mTvSurfacePrepared = true;
        mHandler.removeMessages(MSG_START_TV);
        mHandler.sendEmptyMessageDelayed(MSG_START_TV, DataUtils.START_TV_REVIEW_DELAY);
    }

    @Override
    public void onViewDetachedFromWindow(View v) {
        Log.v(TAG, "onViewDetachedFromWindow");
        mTvSurfacePrepared = false;
        if (null != tvView) {
            tvView.reset();//?
        }
    }

    @Override
    public void onClick(View v) {
        long clickTime = System.currentTimeMillis();
        if (clickTime - mLastClickTime < DataUtils.LIMIT_DOUBLE_CLICK_TIME) {
            return;
        }
        Log.v(TAG, "onClick " + v);
        mLastClickTime = clickTime;
        if (btn_screenshot.getId() == v.getId()) {
            mPopSettings.dismiss();
            btn_screenshot.setEnabled(false);
            mHandler.removeMessages(MSG_SCREENSHOT_START);
            mHandler.sendEmptyMessageDelayed(MSG_SCREENSHOT_START,
                    DataUtils.MAIN_REQUEST_SCREENSHOT_START_DELAYED);
        } else if (btn_pip.getId() == v.getId()) {
            /* already do in onPause
              pauseSideband();
              stopSidebandRecord(true);
            */
            enterPiPMode();
        } else if (mPopSettingsPrepared) {
            //mPopSettings.showAtLocation(rootView, Gravity.CENTER, 0, 0);
        }
    }

    private Bitmap startScreenShot(String tempPath) {
        Bitmap bitmap = null;
        MediaMetadataRetriever mediaMetadataRetriever = null;
        try {
            Log.v(TAG, "==========start capture=======");
            long timeLimit = 1;
            int milliTime = 500;
            String cmd = "screenrecord --time-limit " + timeLimit + " --capture-hdmiin " + milliTime + " " + tempPath;
            Process p = Runtime.getRuntime().exec(cmd);
            p.waitFor(timeLimit + 1, TimeUnit.SECONDS);
            mediaMetadataRetriever = new MediaMetadataRetriever();
            mediaMetadataRetriever.setDataSource(tempPath);
            String duration = mediaMetadataRetriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
            bitmap = mediaMetadataRetriever.getFrameAtTime(Long.parseLong(duration) * 1000, MediaMetadataRetriever.OPTION_CLOSEST);
            File file = new File(tempPath);
            boolean result = file.delete();
            Log.v(TAG, "==========end capture=======" + result + "=" + duration);
        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            if (mediaMetadataRetriever != null) {
                try {
                    mediaMetadataRetriever.release();
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
        }
        return bitmap;
    }

    private boolean execCmd(String cmd) {
        Log.d(TAG, "execCmd " + cmd);
        OutputStream outputStream = null;
        DataOutputStream dataOutputStream = null;
        try {
            Process p = Runtime.getRuntime().exec("sh");
            outputStream = p.getOutputStream();
            dataOutputStream = new DataOutputStream(outputStream);
            dataOutputStream.writeBytes(cmd);
            dataOutputStream.flush();
        } catch (Exception e) {
            e.printStackTrace();
            return false;
        } finally {
            if (null != dataOutputStream) {
                try {
                    dataOutputStream.close();
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
            if (null != outputStream) {
                try {
                    outputStream.close();
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
        }
        return true;
    }

    private String getSavePath(String suffix) {
        String storagePath = getSaveDir();
        SimpleDateFormat format = new SimpleDateFormat("yyyyMMdd_HHmmss");//yyyyMMdd_HHmmssSSS
        return storagePath + format.format(new Date(System.currentTimeMillis()))
                + suffix;
    }

    private String getSaveDir() {
        File rootPath = Environment.getExternalStorageDirectory();
        File storagePath = new File(rootPath, DataUtils.STORAGE_PATH_NAME);
        if (null != storagePath && !storagePath.exists()) {
            boolean ret = storagePath.mkdirs();
            Log.v(TAG, "create " + storagePath.getAbsolutePath() + " " + ret);
        }
        return storagePath.getAbsolutePath() + "/";
    }

    private void switchPreviewMode(int previewMode, long delayMillis) {
        if (null != mPopSettings && mPopSettings.isShowing()) {
            mPopSettings.dismiss();
        }
        Message msg = new Message();
        msg.what = MSG_SWITCH_MODE;
        msg.arg1 = previewMode;
        mHandler.sendMessageDelayed(msg, delayMillis);
    }

    private void addSaveFileToDb(String filePath) {
        if (TextUtils.isEmpty(filePath)) {
            return;
        }
        Intent intent = new Intent(Intent.ACTION_MEDIA_SCANNER_SCAN_FILE);
        intent.setData(Uri.fromFile(new File(filePath)));
        sendBroadcast(intent);
    }

    private void registerHdmiCallback() {
        if (null == mHdmiCallback) {
            mHdmiCallback = new HdmiCallback();
        }
        try {
            IHdmi service = IHdmi.getService(true);
            service.registerListener((IHdmiCallback) mHdmiCallback);
        } catch (RemoteException e) {
            e.printStackTrace();
        }
    }

    private void unregisterHdmiCallback() {
        if (null == mHdmiCallback) {
            return;
        }
        try {
            IHdmi service = IHdmi.getService(true);
            service.unregisterListener((IHdmiCallback) mHdmiCallback);
        } catch (RemoteException e) {
            e.printStackTrace();
        }
    }

    private void createTextureView() {
        Log.d(TAG, "recreatTextureview");
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                Log.i(TAG, "textureView remove");
                if (textureView != null) {
                    rootView.removeView(textureView);
                    textureView = null;
                }
                textureView = new TextureView(MainActivity.this);
                textureView.setOnClickListener(MainActivity.this);
                RelativeLayout.LayoutParams layoutParams = new RelativeLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                layoutParams.addRule(RelativeLayout.ALIGN_PARENT_TOP);
                textureView.setLayoutParams(layoutParams);
                rootView.addView(textureView, 0);
                textureView.setSurfaceTextureListener(textureListener);
            }
        });
    }

    TextureView.SurfaceTextureListener textureListener = new TextureView.SurfaceTextureListener() {
        @Override
        public void onSurfaceTextureAvailable(SurfaceTexture surface, int width, int height) {
            // open your camera here
            Log.d(TAG, "onSurfaceTextureAvailable");
            openCamera();
            // Intent hdmiService = new Intent(RockchipCamera2.this, HdmiService.class);
            // hdmiService.setPackage(getPackageName());
            // bindService(hdmiService, conn, Context.BIND_AUTO_CREATE);
        }

        @Override
        public void onSurfaceTextureSizeChanged(SurfaceTexture surface, int width, int height) {
            Log.d(TAG, "onSurfaceTextureSizeChanged");
            // Transform you image captured size according to the surface width and height
        }

        @Override
        public boolean onSurfaceTextureDestroyed(SurfaceTexture surface) {
            Log.d(TAG, "onSurfaceTextureDestroyed");
            return true;
        }

        @Override
        public void onSurfaceTextureUpdated(SurfaceTexture surface) {
            // Log.d(TAG,"onSurfaceTextureUpdated");
            /*
             * int width = 0; int height = 0; int[] format = JniCameraCall.getFormat(); if
             * (format != null && format.length > 0) { width = format[0]; height =
             * format[1]; } Log.d(TAG,"width = "+width+",height = "+height);
             */

        }
    };

    private final CameraDevice.StateCallback stateCallback = new CameraDevice.StateCallback() {
        @Override
        public void onOpened(CameraDevice camera) {
            // This is called when the camera is open
            Log.d(TAG, "onOpened");
            cameraDevice = camera;
            createCameraPreview();
        }

        @Override
        public void onDisconnected(CameraDevice camera) {
            Log.d(TAG, "onDisconnected");
            cameraDevice.close();
        }

        @Override
        public void onError(CameraDevice camera, int error) {
            Log.i(TAG, "onError");
            cameraDevice.close();
            cameraDevice = null;
        }
    };

    protected void startBackgroundThread() {
        mBackgroundThread = new HandlerThread("Camera Background");
        mBackgroundThread.start();
        mBackgroundHandler = new Handler(mBackgroundThread.getLooper());
    }

    protected void stopBackgroundThread() {
        if (null == mBackgroundThread) {
            return;
        }
        mBackgroundThread.quitSafely();
        try {
            mBackgroundThread.join();
            mBackgroundThread = null;
            mBackgroundHandler = null;
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
    }

    private void createCameraPreview() {
        try {
            Log.d(TAG, "createCameraPreview");
            SurfaceTexture texture = textureView.getSurfaceTexture();
            assert texture != null;
            Log.d(TAG, "imageDimension.getWidth()=" + imageDimension.getWidth() + ",imageDimension.getHeight()="
                    + imageDimension.getHeight());
            texture.setDefaultBufferSize(imageDimension.getWidth(), imageDimension.getHeight());
            Surface surface = new Surface(texture);
            captureRequestBuilder = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
            captureRequestBuilder.addTarget(surface);
            cameraDevice.createCaptureSession(Arrays.asList(surface), new CameraCaptureSession.StateCallback() {
                @Override
                public void onConfigured(@NonNull CameraCaptureSession cameraCaptureSession) {
                    // The camera is already closed
                    if (null == cameraDevice) {
                        return;
                    }
                    Log.d(TAG, "onConfigured");
                    // When the session is ready, we start displaying the preview.
                    cameraCaptureSessions = cameraCaptureSession;
                    updatePreview();
                }

                @Override
                public void onConfigureFailed(@NonNull CameraCaptureSession cameraCaptureSession) {
                    Log.i(TAG, "onConfigureFailed");
                    Toast.makeText(MainActivity.this, "Configuration failed", Toast.LENGTH_SHORT).show();
                }
            }, null);
        } catch (CameraAccessException e) {
            e.printStackTrace();
        }
    }

    private void openCamera() {
        String getHdmiDeviceId = "";
        try {
            IHdmi service = IHdmi.getService(true);
            getHdmiDeviceId = service.getHdmiDeviceId();
            service.registerListener((IHdmiCallback) mHdmiCallback);
        } catch (RemoteException e) {
            e.printStackTrace();
        }

        CameraManager manager = (CameraManager) getSystemService(Context.CAMERA_SERVICE);
        Log.i(TAG, "openCamera start");
        try {
            if (manager.getCameraIdList().length == 0) {
                Log.i(TAG, "openCamera length == 0");
                return;
            }
            boolean haveHDMI = false;
            String hdmiCameraId = "";
            for (String cameraId : manager.getCameraIdList()) {
                Log.i(TAG, "cameraId:" + cameraId);
                if (cameraId.equals(getHdmiDeviceId)) {
                    haveHDMI = true;
                    hdmiCameraId = cameraId;
                    Log.i(TAG, "haveHDMI cameraId:" + cameraId);
                }
            }
            if (!haveHDMI) {
                return;
            }

            CameraCharacteristics characteristics = manager.getCameraCharacteristics(hdmiCameraId);
            StreamConfigurationMap map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
            assert map != null;
            //imageDimension = map.getOutputSizes(SurfaceTexture.class)[0];
            for (Size size : map.getOutputSizes(SurfaceTexture.class)) {
                Log.d(TAG, "supported stream size: " + size.toString());
                imageDimension = size;
            }
            mCameraSensorOrientation = characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION);
            Log.d(TAG, "current hdmi input size:" + imageDimension.toString() + ", orientation=" + mCameraSensorOrientation);
            //configureTextureViewTransform(180, textureView.getWidth(), textureView.getHeight());*/
            manager.openCamera(hdmiCameraId, stateCallback, mBackgroundHandler);
            Log.v(TAG, "open camera end");
        } catch (Exception e) {
            e.printStackTrace();
        }
        Log.i(TAG, "openCamera end");
    }

    private void updatePreview() {
        if (null == cameraDevice) {
            Log.e(TAG, "updatePreview error, return");
        }
        Log.d(TAG, "updatePreview");
        captureRequestBuilder.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO);
        try {
            cameraCaptureSessions.setRepeatingRequest(captureRequestBuilder.build(), null, mBackgroundHandler);
        } catch (CameraAccessException e) {
            e.printStackTrace();
        }
    }

    private void closeCamera() {
        Log.v(TAG, "closeCamera start");
        if (null != cameraDevice) {
            cameraDevice.close();
            cameraDevice = null;
        }
        Log.v(TAG, "closeCamera end");
    }

    @Override
    protected void onResume() {
        super.onResume();
        Log.d(TAG, "onResume");
        resumeSideband();
        if (!mPopSettingsPrepared) {
            mHandler.sendEmptyMessageDelayed(MSG_ENABLE_SETTINGS, DataUtils.MAIN_ENABLE_SETTINGS_DEALY);
        }
    }

    @Override
    protected void onPause() {
        Log.d(TAG, "onPause");
        super.onPause();
        pauseCamera();
        pauseSideband();
    }

    @Override
    protected void onStop() {
        super.onStop();
        Log.d(TAG, "onStop");
        if (isInPictureInPictureMode()) {
            Log.d(TAG, "onStop inpip");
            mExitAppWithPip = true;
        }
    }

    @Override
    public void onPictureInPictureModeChanged(boolean isInPictureInPictureMode, Configuration newConfig) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig);
        Log.i(TAG, "onPictureInPictureModeChanged: " + isInPictureInPictureMode);
        if (mExitAppWithPip) {
            return;
        }
        if (isInPictureInPictureMode) {
            switchPreviewMode(MODE_CAMERA, 0);
        } else {
            switchPreviewMode(MODE_TV, 0);
        }
    }

    private void enterPiPMode() {
        Display display = mWindowManager.getDefaultDisplay();
        Point size = new Point();
        display.getRealSize(size);
        int screenWidth = size.x;
        int screenHeight = size.y;
        PictureInPictureParams.Builder mPictureInPictureParamsBuilder = new PictureInPictureParams.Builder();
        Rational aspectRatio = new Rational(screenWidth, screenHeight);
        mPictureInPictureParamsBuilder.setAspectRatio(aspectRatio).build();
        enterPictureInPictureMode(mPictureInPictureParamsBuilder.build());
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        Log.i(TAG, "onDestroy");
        mIsDestory = true;
        mHandler.removeMessages(MSG_START_TV);
        mHandler.removeMessages(MSG_ENABLE_SETTINGS);
        mHandler.removeMessages(MSG_SCREENSHOT_START);
        mHandler.removeMessages(MSG_SCREENSHOT_FINISH);
        unregisterReceiver();
    }

    private final class MyBroadCastReceiver extends BroadcastReceiver {

        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            Log.v(TAG, action);
            if (Intent.ACTION_CLOSE_SYSTEM_DIALOGS.equals(action)) {
                if (!isInPictureInPictureMode()) {
                    exitApp();
                }
            } else if (HDMI_OUT_ACTION.equals(action) || DP_OUT_ACTION.equals(action)) {
                if (null != mPopSettings && mPopSettings.isShowing()) {
                    mPopSettings.dismiss();
                }
                mAlreadyTvTune = false;
                resumeSideband();
            } else if (Intent.ACTION_HDMIIN_RK_PRIV_CMD.equals(action)) {
                action = intent.getStringExtra("action");
                Log.v(TAG, "receiver: " + action);
                if (null != mPopSettings && mPopSettings.isShowing()) {
                    mPopSettings.dismiss();
                }
            }
        }
    }
}
