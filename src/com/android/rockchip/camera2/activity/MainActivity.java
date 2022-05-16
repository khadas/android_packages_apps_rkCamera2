package com.android.rockchip.camera2.activity;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.ImageFormat;
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
import com.android.rockchip.camera2.util.DataUtils;
import com.android.rockchip.camera2.util.SystemPropertiesProxy;

import java.io.DataOutputStream;
import java.io.File;
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

public class MainActivity extends Activity implements View.OnAttachStateChangeListener,
        View.OnClickListener, ImageReader.OnImageAvailableListener {

    private static final String TAG = "RockchipCamera2";
    private TextureView textureView;
    private static final SparseIntArray ORIENTATIONS = new SparseIntArray();

    private final int MSG_START_TV = 0;
    private final int MSG_SWITCH_MODE = 1;
    private final int MSG_CAMERA_RECORD = 2;
    private final int MSG_CAMERA_CAPTURE = 3;
    private final int MSG_REQUEST_SCREEN_SHOT = 4;

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
    private boolean mSidebandCameraCapture;
    private boolean mSidebandCameraRecord;
    private boolean mIsRecord;
    private MediaRecorder mMediaRecorder;
    private String mCurrentSavePath;
    private int mCameraSensorOrientation;
    private ImageReader mImageReader;
    private boolean mCanCapturePic;

    protected CameraDevice cameraDevice;
    protected CameraCaptureSession mCameraCaptureSessions;
    protected CaptureRequest captureRequest;
    protected CaptureRequest.Builder mCaptureRequestBuilder;
    private Size imageDimension = new Size(1920, 1080);
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
                        btn_screenshot.setEnabled(true);
                        mPopViewPrepared = true;
                    }
                } else if (MSG_SWITCH_MODE == msg.what) {
                    Log.v(TAG, "switch mode to " + (mIsSideband ? "camera" : "sideband"));
                    if (mIsSideband) {
                        pauseSideband();
                        resumeCamera();
                    } else {
                        pauseCamera();
                        resumeSideband();
                    }
                    mIsSideband = !mIsSideband;
                } else if (MSG_CAMERA_RECORD == msg.what) {
                    Log.v(TAG, "MediaRecorder.start()");
                    btn_record.setText(R.string.btn_record_stop);
                    mMediaRecorder.start();
                } else if (MSG_CAMERA_CAPTURE == msg.what) {
                    Log.v(TAG, "do capture pic");
                    mCanCapturePic = true;
                    try {
                        CaptureRequest.Builder captureRequestBuilder = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
                        captureRequestBuilder.addTarget(mImageReader.getSurface());
                        captureRequestBuilder.set(CaptureRequest.JPEG_ORIENTATION, mCameraSensorOrientation);
                        mCameraCaptureSessions.capture(captureRequestBuilder.build(), null, mBackgroundHandler);
                        Log.v(TAG, "do capture pic end");
                    } catch (CameraAccessException e) {
                        e.printStackTrace();
                    }
                } else if (MSG_REQUEST_SCREEN_SHOT == msg.what) {
                    mSidebandCameraCapture = false;
                    if (!mIsSideband) {
                        String savePath = getSavePath(".png");
                        execCmd("screencap -p " + savePath);
                        addSaveFileToDb(savePath);
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
        stopCameraRecord();
        stopCameraCapture();
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
        stopCameraCapture();
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
                mSidebandCameraCapture = true;
                switchPreviewMode();
            } else {
                mPopViewPrepared = true;
            }
        } else if (btn_record.getId() == v.getId()) {
            mPopViewPrepared = false;
            mPopView.dismiss();
            btn_screenshot.setEnabled(false);
            if (mIsRecord) {
                stopCameraRecord();
                if (mIsSideband) {
                    mPopViewPrepared = true;
                } else {
                    switchPreviewMode();
                }
            } else {
                if (mIsSideband) {
                    mSidebandCameraRecord = mIsSideband;
                    switchPreviewMode();
                } else {
                    mPopViewPrepared = true;
                }
            }
        } else if (mPopViewPrepared) {
            //mPopView.showAtLocation(rootView, Gravity.LEFT, 50, 50);
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
        SimpleDateFormat format = new SimpleDateFormat("yyyyMMdd_HHmmss");//yyyyMMdd_HHmmssSSS
        return storagePath.getAbsolutePath() + "/"
                + format.format(new Date(System.currentTimeMillis()))
                + suffix;
    }

    private void switchPreviewMode() {
        mPopViewPrepared = false;
        mPopView.dismiss();
        mHandler.sendEmptyMessage(MSG_SWITCH_MODE);
    }

    private void addSaveFileToDb(String filePath) {
        Intent intent = new Intent(Intent.ACTION_MEDIA_SCANNER_SCAN_FILE);
        intent.setData(Uri.fromFile(new File(filePath)));
        sendBroadcast(intent);
    }

    private void stopCameraRecord() {
        mIsRecord = false;
        btn_record.setText(R.string.btn_record_start);
        if (null != mMediaRecorder) {
            Log.v(TAG, "stopCameraRecord start");
            mMediaRecorder.stop();
            mMediaRecorder.reset();
            mMediaRecorder = null;
            addSaveFileToDb(mCurrentSavePath);
            Log.v(TAG, "stopCameraRecord end");
        }
    }

    private void stopCameraCapture() {
        if (null != mImageReader) {
            Log.v(TAG, "stopCameraCapture start");
            mImageReader.close();
            mImageReader = null;
            Log.v(TAG, "stopCameraCapture start");
        }
    }

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
            Log.v(TAG, "onCaptureCompleted");
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

    @Override
    public void onImageAvailable(ImageReader reader) {
        Log.v(TAG, "onImageAvailable");
        if (!mCanCapturePic || mIsDestory) {
            return;
        }
        mCanCapturePic = false;
        Image image = null;
        FileOutputStream output = null;
        try {
            image = reader.acquireNextImage();
            ByteBuffer buffer = image.getPlanes()[0].getBuffer();
            byte[] bytes = new byte[buffer.remaining()];
            buffer.get(bytes);
            String savePath = getSavePath(".jpg");
            output = new FileOutputStream(savePath);
            output.write(bytes);
            addSaveFileToDb(savePath);
        } catch (IOException e) {
            e.printStackTrace();
        } finally {
            mSidebandCameraCapture = false;
            switchPreviewMode();
            if (null != image) {
                image.close();
            }
            if (null != output) {
                try {
                    output.close();
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
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
            List<Surface> surfacesList = new ArrayList<>();
            mCaptureRequestBuilder = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
            //captureRequestBuilder = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE);
            if (mSidebandCameraCapture) {
                stopCameraCapture();
                mImageReader = ImageReader.newInstance(imageDimension.getWidth(), imageDimension.getHeight(), ImageFormat.JPEG, 1);
                mImageReader.setOnImageAvailableListener(this, mBackgroundHandler);
                Surface captureSurface = mImageReader.getSurface();
                surfacesList.add(captureSurface);
                //mCaptureRequestBuilder.addTarget(captureSurface);
            } else if (mSidebandCameraRecord) {
                stopCameraRecord();
                mMediaRecorder = new MediaRecorder();
                mCurrentSavePath = getSavePath(".mp4");
                setUpMediaRecorder(mCurrentSavePath);
                Surface recorderSurface = mMediaRecorder.getSurface();
                surfacesList.add(recorderSurface);
                mCaptureRequestBuilder.addTarget(recorderSurface);
            }
            surfacesList.add(surface);
            mCaptureRequestBuilder.addTarget(surface);

            cameraDevice.createCaptureSession(surfacesList, new CameraCaptureSession.StateCallback() {
                @Override
                public void onConfigured(@NonNull CameraCaptureSession cameraCaptureSession) {
                    // The camera is already closed
                    if (null == cameraDevice) {
                        return;
                    }
                    Log.d(TAG, "onConfigured");
                    // When the session is ready, we start displaying the preview.
                    mCameraCaptureSessions = cameraCaptureSession;
                    updatePreview();
                    if (mSidebandCameraRecord) {
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
            mCameraSensorOrientation = characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION);
            Log.d(TAG, "current hdmi input size:" + imageDimension.toString() + ", orientation=" + mCameraSensorOrientation);
            //configureTextureViewTransform(180, textureView.getWidth(), textureView.getHeight());*/
            manager.openCamera(hdmiCameraId, stateCallback, mBackgroundHandler);
            Log.v(TAG, "open camera end");
            startHdmiAudioService();
            if (mSidebandCameraRecord || mSidebandCameraCapture) {
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

    protected void updatePreview() {
        if (null == cameraDevice) {
            Log.e(TAG, "updatePreview error, return");
        }
        Log.d(TAG, "updatePreview");
        mCaptureRequestBuilder.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO);
        try {
            if (mSidebandCameraCapture) {
                mHandler.removeMessages(MSG_CAMERA_CAPTURE);
                mHandler.sendEmptyMessageDelayed(MSG_CAMERA_CAPTURE, DataUtils.MAIN_REQUEST_SCREENSHOT_DELAYED);
            }
            mCameraCaptureSessions.setRepeatingRequest(mCaptureRequestBuilder.build(), null, mBackgroundHandler);
        } catch (CameraAccessException e) {
            e.printStackTrace();
        }
    }

    private void closeCamera() {
        stopCameraRecord();
        stopCameraCapture();
        Log.v(TAG, "closeCamera start");
        if (null != cameraDevice) {
            cameraDevice.close();
            cameraDevice = null;
        }
        mCameraFree = true;
        mHandler.removeMessages(MSG_START_TV);
        mHandler.sendEmptyMessage(MSG_START_TV);
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
        mHandler.removeMessages(MSG_CAMERA_CAPTURE);
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