package com.android.rockchip.camera2.activity;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.ImageFormat;
import android.graphics.Matrix;
import android.graphics.RectF;
import android.graphics.SurfaceTexture;
import android.graphics.drawable.ColorDrawable;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CameraMetadata;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.TotalCaptureResult;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.Image;
import android.media.ImageReader;
import android.media.MediaRecorder;
import android.media.projection.MediaProjectionManager;
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
import android.util.Log;
import android.util.Size;
import android.util.SparseIntArray;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.PopupWindow;
import android.widget.RelativeLayout;
import android.widget.Toast;

import com.android.rockchip.camera2.HdmiService;
import com.android.rockchip.camera2.R;
import com.android.rockchip.camera2.ScreenRecordService;
import com.android.rockchip.camera2.util.DataUtils;
import com.android.rockchip.camera2.util.SystemPropertiesProxy;

import java.io.DataOutputStream;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

import rockchip.hardware.hdmi.V1_0.IHdmi;
import rockchip.hardware.hdmi.V1_0.IHdmiCallback;

public class MainActivity extends Activity implements View.OnAttachStateChangeListener, View.OnClickListener {

    private static final String TAG = "RockchipCamera2";
    private TextureView textureView;
    private static final SparseIntArray ORIENTATIONS = new SparseIntArray();

    private final int MEDIA_PROJECTION_REQUEST_CODE = 0x001;
    private final int MSG_START_TV = 0;
    private final int MSG_SWITCH_MODE = 1;
    private final int MSG_CAMERA_RECORD = 2;
    private final int MSG_REQUEST_SCREEN_SHOT = 3;

    static {
        ORIENTATIONS.append(Surface.ROTATION_0, 90);
        ORIENTATIONS.append(Surface.ROTATION_90, 0);
        ORIENTATIONS.append(Surface.ROTATION_180, 270);
        ORIENTATIONS.append(Surface.ROTATION_270, 180);
    }

    private TvView tvView;
    private PopupWindow mPopView;
    private Button btn_switch;
    private Button btn_screenshot;
    private Button btn_record;

    private Object mLock = new Object();
    private boolean mIsSideband = true;
    private Uri mChannelUri;
    private MyBroadCastReceiver mBroadCastReceiver;
    private boolean mPopViewPrepared;
    private boolean mResumePrepared;
    private boolean mTvSurfacePrepared;
    private boolean mCameraFree;
    private boolean mAlreadyTvTune;
    private boolean mIsDestory;
    private long mLastClickTime;
    private boolean mSidebandScreenShot;
    private boolean mSidebandRecord;
    private boolean mIsRecord;
    private final boolean USED_SCREEN_RECORD = false;
    private MediaRecorder mMediaRecorder;
    private String mCurrentSavePath;

    protected CameraDevice cameraDevice;
    protected CameraCaptureSession cameraCaptureSessions;
    protected CaptureRequest captureRequest;
    protected CaptureRequest.Builder captureRequestBuilder;
    private Size imageDimension = new Size(1920, 1080);
    private ImageReader imageReader;
    private File file;
    private Handler mBackgroundHandler;
    private HandlerThread mBackgroundThread;
    private HdmiService mHdmiService;
    private RelativeLayout rootView;
    private boolean mPaused = false;

    class HdmiCallback extends IHdmiCallback.Stub {
        public HdmiCallback() {
        }

        public void onConnect() throws RemoteException {
            Log.e(TAG, "onConnect");
            openCamera();
        }

        public void onDisconnect() throws RemoteException {
            Log.e(TAG, "onDisconnect");
            closeCamera();
        }
    }

    HdmiCallback mHdmiCallback;

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
                            && mCameraFree
                            && !mAlreadyTvTune) {
                        mAlreadyTvTune = true;
                        tvView.tune(DataUtils.INPUT_ID, mChannelUri);
                        startHdmiAudioService();
                        mPopViewPrepared = true;
                    }
                } else if (MSG_SWITCH_MODE == msg.what) {
                    Log.v(TAG, "switch mode to " + (mIsSideband ? "camera" : "sideband"));
                    if (mIsSideband) {
                        pauseSideband();
                        resumeCamera();
                    } else {
                        resumeSideband();
                        pauseCamera();
                    }
                    mIsSideband = !mIsSideband;
                } else if (MSG_CAMERA_RECORD == msg.what) {
                    Log.v(TAG, "MediaRecorder.start()");
                    btn_record.setText(R.string.btn_record_stop);
                    mMediaRecorder.start();
                } else if (MSG_REQUEST_SCREEN_SHOT == msg.what) {
                    mSidebandScreenShot = false;
                    if (!mIsSideband) {
                        screenShot();
                        switchPreviewMode();
                    } else {
                        mPopViewPrepared = true;
                    }
                }
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        rootView = (RelativeLayout) findViewById(R.id.root_view);
        rootView.setOnClickListener(this);

        mChannelUri = TvContract.buildChannelUriForPassthroughInput(DataUtils.INPUT_ID);
        registerReceiver();

        if (USED_SCREEN_RECORD) {
            Intent intent = ScreenRecordService.getCreateIntent(this);
            startForegroundService(intent);
        }
        initPopWindow();
        mCameraFree = true;
    }

    private void initPopWindow() {
        View view = LayoutInflater.from(this).inflate(R.layout.layout_main_pop, null, false);
        btn_switch = (Button) view.findViewById(R.id.btn_switch);
        btn_switch.setOnClickListener(this);
        btn_screenshot = (Button) view.findViewById(R.id.btn_screenshot);
        btn_screenshot.setOnClickListener(this);
        btn_record = (Button) view.findViewById(R.id.btn_record);
        btn_record.setText(R.string.btn_record_start);
        btn_record.setOnClickListener(this);
        mPopView = new PopupWindow(view,
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, true);
        mPopView.setTouchable(true);
        mPopView.setBackgroundDrawable(new ColorDrawable(0x00000000));
        mPopViewPrepared = false;
    }

    private void initSideband() {
        mPopViewPrepared = false;
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
        mPopViewPrepared = false;
        mCameraFree = false;
        if (textureView == null) {
            registerHdmiCallback();
            createTextureView();
        }
        startBackgroundThread();
        Log.v(TAG, "resumeCamera");
    }

    private void pauseSideband() {
        stopHdmiAudioService();
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
        stopHdmiAudioService();
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

    private void startHdmiAudioService() {
        Log.v(TAG, "startHdmiAudioService");
        SystemPropertiesProxy.set("vendor.hdmiin.audiorate", "48KHZ");
        Intent intent = new Intent();
        ComponentName cn = new ComponentName(DataUtils.HDMIIN_AUDIO_PACKAGE_NAME, DataUtils.HDMIIN_AUDIO_CLS_NAME);
        intent.setComponent(cn);
        startForegroundService(intent);
    }

    private void stopHdmiAudioService() {
        Log.v(TAG, "stopHdmiAudioService");
        Intent intent = new Intent();
        ComponentName cn = new ComponentName(DataUtils.HDMIIN_AUDIO_PACKAGE_NAME, DataUtils.HDMIIN_AUDIO_CLS_NAME);
        intent.setComponent(cn);
        stopService(intent);
    }

    private void exitApp() {
        try {
            Thread.sleep(50);
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
        stopHdmiAudioService();
        stopCameraRecord();
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
        if (btn_switch.getId() == v.getId()) {
            switchPreviewMode();
        } else if (btn_screenshot.getId() == v.getId()) {
            mPopViewPrepared = false;
            mPopView.dismiss();
            if (mIsSideband) {
                mSidebandScreenShot = true;
                switchPreviewMode();
            } else {
                screenShot();
            }
        } else if (btn_record.getId() == v.getId()) {
            mPopViewPrepared = false;
            mPopView.dismiss();
            if (mIsRecord) {
                if (USED_SCREEN_RECORD) {
                    stopScreenRecord();
                } else {
                    stopCameraRecord();
                    if (mIsSideband) {
                        mPopViewPrepared = true;
                    } else {
                        switchPreviewMode();
                    }
                }
            } else {
                mSidebandRecord = mIsSideband;
                switchPreviewMode();
            }
        } else if (mPopViewPrepared) {
            //mPopView.showAtLocation(rootView, Gravity.LEFT, 50, 50);
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        Log.v(TAG, "onActivityResult requestCode=" + requestCode + ", resultCode=" + resultCode + ", " + data);
        if (requestCode == MEDIA_PROJECTION_REQUEST_CODE) {
            if (null == data) {
                mIsRecord = false;
            } else {
                Intent intent = ScreenRecordService.getStartIntent(this, resultCode, data, getSavePath(".mp4"));
                btn_record.setText(R.string.btn_record_stop);
                startForegroundService(intent);
            }
            mPopViewPrepared = true;
        }
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
        File rootPath = Environment.getExternalStorageDirectory();
        File storagePath = new File(rootPath, DataUtils.STORAGE_PATH_NAME);
        if (null != storagePath && !storagePath.exists()) {
            boolean ret = storagePath.mkdirs();
            Log.v(TAG, "create " + storagePath.getAbsolutePath() + " " + ret);
        }
        SimpleDateFormat format = new SimpleDateFormat("yyyyMMdd_HHmmss");
        return storagePath.getAbsolutePath() + "/"
                + format.format(new Date(System.currentTimeMillis()))
                + suffix;
    }

    private void switchPreviewMode() {
        mPopViewPrepared = false;
        mPopView.dismiss();
        mHandler.sendEmptyMessage(MSG_SWITCH_MODE);
    }

    private void screenShot() {
        boolean ret = execCmd("screencap -p " + getSavePath(".png"));
        if (ret) {
            //showToast(path);
        } else {
            showToast(R.string.save_failed);
        }
    }

    private void startScreenRecord() {
        if (!mIsRecord) {
            Log.v(TAG, "startScreenRecord");
            mIsRecord = true;
            MediaProjectionManager manager = (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);
            Intent intent = new Intent(manager.createScreenCaptureIntent());
            startActivityForResult(intent, MEDIA_PROJECTION_REQUEST_CODE);
        }
    }

    private void stopScreenRecord() {
        Log.v(TAG, "stopScreenRecord");
        Intent intent = ScreenRecordService.getStopIntent(this);
        startForegroundService(intent);
        mIsRecord = false;
        btn_record.setText(R.string.btn_record_start);
        if (mSidebandRecord) {
            mSidebandRecord = false;
            if (!mIsSideband) {
                switchPreviewMode();
            } else {
                mPopViewPrepared = true;
            }
        } else {
            mPopViewPrepared = true;
        }
    }

    private void stopCameraRecord() {
        Log.v(TAG, "stopCameraRecord start");
        mIsRecord = false;
        btn_record.setText(R.string.btn_record_start);
        if (null != mMediaRecorder) {
            mMediaRecorder.stop();
            mMediaRecorder.reset();
            mMediaRecorder = null;
            Intent intent = new Intent(Intent.ACTION_MEDIA_SCANNER_SCAN_FILE);
            intent.setData(Uri.fromFile(new File(mCurrentSavePath)));
            sendBroadcast(intent);
        }
        Log.v(TAG, "stopCameraRecord end");
    }
//
//    private void addRecordFileToDb() {
//        ContentValues values = new ContentValues();
////        values.put(MediaStore.Video.Media.DISPLAY_NAME, mCurrentSavePath);
//        values.put(MediaStore.Video.Media.MIME_TYPE, "video/mp4");
//        values.put(MediaStore.Video.Media.DATE_ADDED, System.currentTimeMillis());
//        values.put(MediaStore.Video.Media.DATE_TAKEN, System.currentTimeMillis());
//        Uri collectionUri = MediaStore.Video.Media.getContentUri(
//                MediaStore.VOLUME_EXTERNAL_PRIMARY);
//        Uri uri = getContentResolver().insert(collectionUri, values);
//        if (null != uri) {
//            Log.v(TAG, mCurrentSavePath + ", " + uri);
//        } else {
//            Log.v(TAG, mCurrentSavePath + " add to db failed");
//        }
//    }

    private void setUpMediaRecorder(String savePath) throws Exception {
        mMediaRecorder.setAudioSource(MediaRecorder.AudioSource.MIC);
        mMediaRecorder.setVideoSource(MediaRecorder.VideoSource.SURFACE);
        mMediaRecorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
        mMediaRecorder.setOutputFile(savePath);//设置输出文件的路径
        mMediaRecorder.setVideoEncodingBitRate(DataUtils.VIDEO_RECORD_BIT_RATE);
        mMediaRecorder.setVideoFrameRate(DataUtils.VIDEO_RECORD_FRAME_RATE);
        mMediaRecorder.setVideoSize(imageDimension.getWidth(), imageDimension.getHeight());
        mMediaRecorder.setVideoEncoder(MediaRecorder.VideoEncoder.H264);
//        mMediaRecorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);
        mMediaRecorder.setAudioEncoder(MediaRecorder.AudioEncoder.AMR_NB);
        mMediaRecorder.setAudioChannels(DataUtils.AUDIO_RECORD_TOTAL_NUM_TRACKS);
        mMediaRecorder.setAudioEncodingBitRate(DataUtils.AUDIO_RECORD_BIT_RATE);
        mMediaRecorder.setAudioSamplingRate(DataUtils.AUDIO_RECORD_SAMPLE_RATE);
        int rotation = getWindowManager().getDefaultDisplay().getRotation();
        Log.v(TAG, "setUpMediaRecorder: " + rotation);
        //mMediaRecorder.setOrientationHint(ORIENTATIONS.get(rotation));
        mMediaRecorder.prepare();
    }

    private void initCamera() {
        mCameraFree = false;
        registerHdmiCallback();
        createTextureView();
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

    // ServiceConnection conn = new ServiceConnection() {
    //     @Override
    //     public void onServiceDisconnected(ComponentName name) {
    //         Log.i(TAG, "onServiceDisconnected");
    //     }

    //     @Override
    //     public void onServiceConnected(ComponentName name, IBinder service) {
    //         Log.i(TAG, "onServiceConnected");
    //         // 返回一个HdmiService对象
    //         mHdmiService = ((HdmiService.HdmiBinder) service).getService();

    //         // 注册回调接口来接收HDMI的变化
    //         mHdmiService.setOnHdmiStatusListener(new HdmiService.OnHdmiStatusListener() {

    //             @Override
    //             public void onHdmiStatusChange(boolean isHdmiIn, Size driverDimension) {
    //                 if (mPaused) return;
    //                 Log.i(TAG, "onHdmiStatusChange isHdmiIn = " + isHdmiIn + ",mPaused:" + mPaused);
    //                 imageDimension = driverDimension;
    //                 if (isHdmiIn) {
    //                     openCamera();
    //                 } else {
    //                     closeCamera();
    //                 }
    //             }
    //         });

    //     }
    // };

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

    final CameraCaptureSession.CaptureCallback captureCallbackListener = new CameraCaptureSession.CaptureCallback() {
        @Override
        public void onCaptureCompleted(CameraCaptureSession session, CaptureRequest request,
                                       TotalCaptureResult result) {
            super.onCaptureCompleted(session, request, result);
            Toast.makeText(MainActivity.this, "Saved:" + file, Toast.LENGTH_SHORT).show();
            createCameraPreview();
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

    protected void takePicture() {
        if (null == cameraDevice) {
            Log.e(TAG, "cameraDevice is null");
            return;
        }
        CameraManager manager = (CameraManager) getSystemService(Context.CAMERA_SERVICE);
        try {
            CameraCharacteristics characteristics = manager.getCameraCharacteristics(cameraDevice.getId());
            Size[] jpegSizes = null;
            if (characteristics != null) {
                jpegSizes = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                        .getOutputSizes(ImageFormat.JPEG);
            }
            int width = 640;
            int height = 480;
            if (jpegSizes != null && 0 < jpegSizes.length) {
                width = jpegSizes[0].getWidth();
                height = jpegSizes[0].getHeight();
            }
            width = imageDimension.getWidth();
            height = imageDimension.getHeight();
            Log.d(TAG, "pic size W=" + width + ",H=" + height);
            ImageReader reader = ImageReader.newInstance(width, height, ImageFormat.JPEG, 1);
            List<Surface> outputSurfaces = new ArrayList<Surface>(2);
            outputSurfaces.add(reader.getSurface());
            outputSurfaces.add(new Surface(textureView.getSurfaceTexture()));
            final CaptureRequest.Builder captureBuilder = cameraDevice
                    .createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE);
            captureBuilder.addTarget(reader.getSurface());
            captureBuilder.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO);
            // Orientation
            int rotation = getWindowManager().getDefaultDisplay().getRotation();
            captureBuilder.set(CaptureRequest.JPEG_ORIENTATION, ORIENTATIONS.get(rotation));
            final File file = new File(Environment.getExternalStorageDirectory() + "/pic.jpg");
            ImageReader.OnImageAvailableListener readerListener = new ImageReader.OnImageAvailableListener() {
                @Override
                public void onImageAvailable(ImageReader reader) {
                    Image image = null;
                    try {
                        image = reader.acquireLatestImage();
                        ByteBuffer buffer = image.getPlanes()[0].getBuffer();
                        byte[] bytes = new byte[buffer.capacity()];
                        buffer.get(bytes);
                        save(bytes);
                    } catch (FileNotFoundException e) {
                        e.printStackTrace();
                    } catch (IOException e) {
                        e.printStackTrace();
                    } finally {
                        if (image != null) {
                            image.close();
                        }
                    }
                }

                private void save(byte[] bytes) throws IOException {
                    OutputStream output = null;
                    try {
                        output = new FileOutputStream(file);
                        output.write(bytes);
                    } finally {
                        if (null != output) {
                            output.close();
                        }
                    }
                }
            };
            reader.setOnImageAvailableListener(readerListener, mBackgroundHandler);
            final CameraCaptureSession.CaptureCallback captureListener = new CameraCaptureSession.CaptureCallback() {
                @Override
                public void onCaptureCompleted(CameraCaptureSession session, CaptureRequest request,
                                               TotalCaptureResult result) {
                    super.onCaptureCompleted(session, request, result);
                    Toast.makeText(MainActivity.this, "Saved:" + file, Toast.LENGTH_SHORT).show();
                    createCameraPreview();
                }
            };
            cameraDevice.createCaptureSession(outputSurfaces, new CameraCaptureSession.StateCallback() {
                @Override
                public void onConfigured(CameraCaptureSession session) {
                    try {
                        session.capture(captureBuilder.build(), captureListener, mBackgroundHandler);
                    } catch (CameraAccessException e) {
                        e.printStackTrace();
                    }
                }

                @Override
                public void onConfigureFailed(CameraCaptureSession session) {
                }
            }, mBackgroundHandler);
        } catch (CameraAccessException e) {
            e.printStackTrace();
        }
    }

    protected void createCameraPreview() {
        try {
            Log.v(TAG, "createCameraPreview");
            SurfaceTexture texture = textureView.getSurfaceTexture();
            assert texture != null;
            Log.v(TAG, "imageDimension.getWidth()=" + imageDimension.getWidth() + ",imageDimension.getHeight()="
                    + imageDimension.getHeight());
            texture.setDefaultBufferSize(imageDimension.getWidth(), imageDimension.getHeight());
            Surface surface = new Surface(texture);
            captureRequestBuilder = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
            List<Surface> surfacesList = new ArrayList<>();
            surfacesList.add(surface);
            captureRequestBuilder.addTarget(surface);

            if (mSidebandRecord) {
                stopCameraRecord();
                mMediaRecorder = new MediaRecorder();
                mCurrentSavePath = getSavePath(".mp4");
                setUpMediaRecorder(mCurrentSavePath);
                Surface recorderSurface = mMediaRecorder.getSurface();
                surfacesList.add(recorderSurface);
                captureRequestBuilder.addTarget(recorderSurface);
            }

            cameraDevice.createCaptureSession(surfacesList, new CameraCaptureSession.StateCallback() {
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
                    if (mSidebandRecord) {
//                        mHandler.sendEmptyMessage(MSG_CAMERA_RECORD);
                        Log.v(TAG, "MediaRecorder.start()");
                        mIsRecord = true;
                        btn_record.setText(R.string.btn_record_stop);
                        mMediaRecorder.start();
                        mPopViewPrepared = true;
                    }
                }

                @Override
                public void onConfigureFailed(@NonNull CameraCaptureSession cameraCaptureSession) {
                    Log.i(TAG, "onConfigureFailed");
                    Toast.makeText(MainActivity.this, "Configuration failed", Toast.LENGTH_SHORT).show();
                }
            }, null);
        } catch (Exception e) {
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
            Log.d(TAG, "current hdmi input size:" + imageDimension.toString());
            int sensorOrientation = characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION);
            Log.v(TAG, "sensorOrientation=" + sensorOrientation);
            configureTextureViewTransform(180, textureView.getWidth(), textureView.getHeight());
            manager.openCamera(hdmiCameraId, stateCallback, mBackgroundHandler);
            Log.v(TAG, "open camera end");
            startHdmiAudioService();
            if (mSidebandScreenShot) {
                mHandler.sendEmptyMessageDelayed(MSG_REQUEST_SCREEN_SHOT, DataUtils.MAIN_REQUEST_SCREENSHOT_DELAYED);
            } else if (mSidebandRecord) {
                if (USED_SCREEN_RECORD) {
                    startScreenRecord();
                }
            } else {
                mPopViewPrepared = true;
            }
        } catch (CameraAccessException e) {
            e.printStackTrace();
        } catch (Exception e) {
            e.printStackTrace();
        }
        Log.i(TAG, "openCamera end");
    }

    private void configureTextureViewTransform(int rotation, int viewWidth, int viewHeight) {
        if (rotation == 0) {
            return;
        }
        Matrix matrix = new Matrix();
        RectF viewRect = new RectF(0, 0, viewWidth, viewHeight);
        RectF bufferRect = new RectF(0, 0, imageDimension.getHeight(), imageDimension.getWidth());
        float centerX = viewRect.centerX();
        float centerY = viewRect.centerY();
        if (Surface.ROTATION_90 == rotation || Surface.ROTATION_270 == rotation) {
            bufferRect.offset(centerX - bufferRect.centerX(), centerY - bufferRect.centerY());
            matrix.setRectToRect(viewRect, bufferRect, Matrix.ScaleToFit.FILL);
            float scale = Math.max(
                    (float) viewHeight / imageDimension.getHeight(),
                    (float) viewWidth / imageDimension.getWidth());
            matrix.postScale(scale, scale, centerX, centerY);
            matrix.postRotate(90 * (rotation - 2), centerX, centerY);
        } else if (Surface.ROTATION_180 == rotation) {
            matrix.postRotate(180, centerX, centerY);
        }
        textureView.setTransform(matrix);
    }

    protected void updatePreview() {
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
        mCameraFree = true;
        mHandler.removeMessages(MSG_START_TV);
        mHandler.sendEmptyMessage(MSG_START_TV);
        if (null != imageReader) {
            imageReader.close();
            imageReader = null;
        }
        Log.v(TAG, "closeCamera end");
    }

    @Override
    protected void onResume() {
        mPaused = false;
        super.onResume();
        Log.d(TAG, "onResume");
        if (mIsSideband) {
            resumeSideband();
        } else {
            resumeCamera();
        }
    }

    @Override
    protected void onPause() {
        Log.d(TAG, "onPause");
        mPaused = true;
        super.onPause();
        pauseSideband();
        pauseCamera();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        Log.i(TAG, "onDestroy");
        mIsDestory = true;
        mHandler.removeMessages(MSG_START_TV);
        mHandler.removeMessages(MSG_REQUEST_SCREEN_SHOT);
        unregisterReceiver();
    }

    private final class MyBroadCastReceiver extends BroadcastReceiver {

        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            Log.v(TAG, action);
            if (Intent.ACTION_CLOSE_SYSTEM_DIALOGS.equals(action)) {
                exitApp();
            }
        }
    }
}
