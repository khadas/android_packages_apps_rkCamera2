package com.android.rockchip.camera2;

import android.Manifest;
import android.content.Context;
import android.content.ComponentName;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.graphics.ImageFormat;
import android.graphics.SurfaceTexture;
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
import android.os.Bundle;
import android.os.IBinder;
import android.os.Environment;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.RemoteException;
import android.os.SystemClock;
import android.support.annotation.NonNull;
import android.support.v4.app.ActivityCompat;
//import android.support.v7.app.AppCompatActivity;
import android.app.Activity;
import android.text.TextUtils;
import android.util.Log;
import android.util.Size;
import android.util.SparseIntArray;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.Toast;
import android.widget.RelativeLayout;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import com.android.rockchip.camera2.util.DataUtils;
import com.android.rockchip.camera2.util.JniCameraCall;
import rockchip.hardware.hdmi.V1_0.IHdmi;
import rockchip.hardware.hdmi.V1_0.IHdmiCallback;
import rockchip.hardware.hdmi.V1_0.HdmiStatus;

public class RockchipCamera2 extends Activity {

    private static final String TAG = "RockchipCamera2";
    private TextureView textureView;
    private static final SparseIntArray ORIENTATIONS = new SparseIntArray();
    static {
        ORIENTATIONS.append(Surface.ROTATION_0, 90);
        ORIENTATIONS.append(Surface.ROTATION_90, 0);
        ORIENTATIONS.append(Surface.ROTATION_180, 270);
        ORIENTATIONS.append(Surface.ROTATION_270, 180);
    }
    protected CameraDevice cameraDevice;
    protected CameraCaptureSession cameraCaptureSessions;
    protected CaptureRequest captureRequest;
    protected CaptureRequest.Builder captureRequestBuilder;
    private Size imageDimension= new Size(1920,1080);
    private ImageReader imageReader;
    private File file;
    private static final int REQUEST_CAMERA_PERMISSION = 200;
    private Handler mBackgroundHandler;
    private HandlerThread mBackgroundThread;
    private HdmiService mHdmiService;
    private RelativeLayout rootView;
    private boolean mPaused = false;
    private String mAssignCameraId;
    private String mAssignCameraType;
    private boolean mIsDisconnect;

    class HdmiCallback extends IHdmiCallback.Stub{
        public  HdmiCallback(){
        }

        public void onConnect(String cameraId) throws RemoteException {
            Log.e(TAG,"onConnect"+cameraId);
            mIsDisconnect = false;
            if (null == textureView) {
                createTextureView();
            } else {
                openCamera();
            }
        }

        public void onFormatChange(String cameraId,int width,int height) throws RemoteException {
            Log.e(TAG,"onFormatChange"+cameraId);
            closeCamera();
	    imageDimension = new Size(width, height);
            if (!mIsDisconnect && null != textureView){
                openCamera();
            }
        }

        public void onDisconnect(String cameraId) throws RemoteException {
            Log.e(TAG,"onDisconnect"+cameraId);
            mIsDisconnect = true;
            removeTextureView();
            closeCamera();
        }
    }
    HdmiCallback mHdmiCallback;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_rockchip_camera2);
        mAssignCameraId = getIntent().getStringExtra(DataUtils.EXTRA_ASSIGN_CAMERA_ID);
        mAssignCameraType = getIntent().getStringExtra(DataUtils.EXTRA_ASSIGN_CAMERA_TYPR);
        rootView = (RelativeLayout) findViewById(R.id.root_view);
        mHdmiCallback= new HdmiCallback();
            try {
                IHdmi service = IHdmi.getService(true);

                service.registerListener((IHdmiCallback)mHdmiCallback);
            } catch (RemoteException e) {
                e.printStackTrace();
            }
        //JniCameraCall.openDevice();
        // try {
        //     IHdmi service = IHdmi.getService(IHdmi.kInterfaceName,true);

        //     service.registerListener((IHdmiCallback)mHdmiCallback);
        // } catch (RemoteException e) {
        //     e.printStackTrace();
        // }
        Log.d(TAG,"remove take pic button");
        /*
        Button takePictureButton = (Button) findViewById(R.id.btn_takepicture);
        assert takePictureButton != null;
        takePictureButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                takePicture();
            }
        });
        */
        createTextureView();
        assert textureView != null;
	// Add permission for camera and let user grant the permission
	if (ActivityCompat.checkSelfPermission(this,
	    Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED
	    && ActivityCompat.checkSelfPermission(this,
		    Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
	    ActivityCompat.requestPermissions(RockchipCamera2.this,
		new String[] { Manifest.permission.CAMERA, Manifest.permission.WRITE_EXTERNAL_STORAGE },
		REQUEST_CAMERA_PERMISSION);
	    return;
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
                textureView = new TextureView(RockchipCamera2.this);
                RelativeLayout.LayoutParams layoutParams = new RelativeLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                layoutParams.addRule(RelativeLayout.ALIGN_PARENT_TOP);
                textureView.setLayoutParams(layoutParams);
                rootView.addView(textureView, 0);
                textureView.setSurfaceTextureListener(textureListener);
            }
        });
    }

    private void removeTextureView() {
        Log.d(TAG, "removeTextureView");
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                if (textureView != null) {
                    rootView.removeView(textureView);
                    textureView = null;
                }
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
            if (null != cameraDevice) {
                cameraDevice.close();
                cameraDevice = null;
            }
        }
    };

    final CameraCaptureSession.CaptureCallback captureCallbackListener = new CameraCaptureSession.CaptureCallback() {
        @Override
        public void onCaptureCompleted(CameraCaptureSession session, CaptureRequest request,
                TotalCaptureResult result) {
            super.onCaptureCompleted(session, request, result);
            Toast.makeText(RockchipCamera2.this, "Saved:" + file, Toast.LENGTH_SHORT).show();
            createCameraPreview();
        }
    };

    protected void startBackgroundThread() {
        mBackgroundThread = new HandlerThread("Camera Background");
        mBackgroundThread.start();
        mBackgroundHandler = new Handler(mBackgroundThread.getLooper());
    }

    protected void stopBackgroundThread() {
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
                    Toast.makeText(RockchipCamera2.this, "Saved:" + file, Toast.LENGTH_SHORT).show();
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
                    Toast.makeText(RockchipCamera2.this, "Configuration failed", Toast.LENGTH_SHORT).show();
                }
            }, null);
        } catch (CameraAccessException e) {
            e.printStackTrace();
        }
    }

    private void openCamera() {
        String  getHdmiDeviceId= "";

        try {
                IHdmi service = IHdmi.getService(true);
                getHdmiDeviceId = service.getHdmiDeviceId();
                // HdmiStatus status = service.getMipiStatus();
                // Log.d(TAG,"status:"+status.status);
                // Log.d(TAG,"width:"+status.width);
                // Log.d(TAG,"height:"+status.height);
                // Log.d(TAG,"fps:"+status.fps);
                HdmiStatus status = service.getHdmiRxStatus();
                Log.d(TAG,"status:"+status.status);
                Log.d(TAG,"width:"+status.width);
                Log.d(TAG,"height:"+status.height);
                Log.d(TAG,"fps:"+status.fps);
                imageDimension = new Size((int)status.width,(int)status.height);
            } catch (RemoteException e) {
                e.printStackTrace();
            }
        CameraManager manager = (CameraManager) getSystemService(Context.CAMERA_SERVICE);
        Log.i(TAG, "openCamera start getHdmiDeviceId=" + getHdmiDeviceId);
        try {
            if(manager.getCameraIdList().length == 0){
                Log.i(TAG, "openCamera length == 0");
                return;
            }
            boolean haveHDMI=false;
            String hdmiCameraId="";
            String alternativeId = "";//备选cameraId
            if ("0".equals(mAssignCameraType)) {
                String[] cameraIds = manager.getCameraIdList();
                if (null != cameraIds && cameraIds.length > 0) {
                    hdmiCameraId = cameraIds[cameraIds.length-1];
                    haveHDMI = true;
                }
            } else if ("1".equals(mAssignCameraType)) {
                hdmiCameraId = getHdmiDeviceId;
                if (null == hdmiCameraId) {
                    hdmiCameraId = "";
                } else {
                    haveHDMI = true;
                }
            } else {
            for (String cameraId : manager.getCameraIdList()) {
                Log.i(TAG, "cameraId:"+cameraId);
                if (TextUtils.isEmpty(mAssignCameraId)) {
                    if(cameraId.equals(getHdmiDeviceId)){
                        haveHDMI = true;
                        hdmiCameraId = cameraId;
                        Log.i(TAG, "haveHDMI cameraId:"+cameraId);
                    }
                } else if (!cameraId.equals(getHdmiDeviceId)) {
                    alternativeId = cameraId;
                    if (cameraId.equals(mAssignCameraId)) {
                        haveHDMI = true;
                        hdmiCameraId = cameraId;
                        Log.i(TAG, "have switch HDMI cameraId:"+cameraId);
                        break;
                    }
                }
            }
            }
            /*if (TextUtils.isEmpty(hdmiCameraId)
                    && !TextUtils.isEmpty(mAssignCameraId) && !TextUtils.isEmpty(alternativeId)) {
                haveHDMI = true;
                hdmiCameraId = alternativeId;
                Log.i(TAG, "have alternative cameraId:"+mAssignCameraId);
            }*/
            if(!haveHDMI){
                return;
            }
            Log.w(TAG, "open cameraId:"+hdmiCameraId);
            CameraCharacteristics characteristics = manager.getCameraCharacteristics(hdmiCameraId);
            StreamConfigurationMap map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
            assert map != null;
            //imageDimension = map.getOutputSizes(SurfaceTexture.class)[0];
            for (Size size : map.getOutputSizes(SurfaceTexture.class)) {
                Log.d(TAG,"supported stream size: "+size.toString());
            }

            Log.d(TAG,"current hdmi input size:"+imageDimension.toString());
            manager.openCamera(hdmiCameraId, stateCallback, mBackgroundHandler);
        } catch (CameraAccessException e) {
            e.printStackTrace();
        } catch (Exception e){
            e.printStackTrace();
        }
        Log.i(TAG, "openCamera end");
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
        Log.d(TAG, "closeCamera");
        if (null != cameraDevice) {
            cameraDevice.close();
            cameraDevice = null;
        }
        if (null != imageReader) {
            imageReader.close();
            imageReader = null;
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
            @NonNull int[] grantResults) {
        if (requestCode == REQUEST_CAMERA_PERMISSION) {
            if (grantResults[0] == PackageManager.PERMISSION_DENIED) {
                // close the app
                Toast.makeText(RockchipCamera2.this, "Sorry!!!, you can't use this app without granting permission",
                        Toast.LENGTH_LONG).show();
                finish();
            }
        }
    }

    @Override
    protected void onResume() {
        mPaused = false;
        super.onResume();
        Log.d(TAG, "onResume");
	if (textureView == null) {
            // JniCameraCall.openDevice();
            try {
                IHdmi service = IHdmi.getService(true);

                service.registerListener((IHdmiCallback)mHdmiCallback);
            } catch (RemoteException e) {
                e.printStackTrace();
            }
            createTextureView();
	}
        startBackgroundThread();
        /* if (textureView.isAvailable()) {
            Log.i(TAG, "onResume textureView is Available");
            // openCamera();
            // Intent hdmiService = new Intent(RockchipCamera2.this, HdmiService.class);
            // hdmiService.setPackage(getPackageName());
            // bindService(hdmiService, conn, Context.BIND_AUTO_CREATE);
        } else {
            textureView.setSurfaceTextureListener(textureListener);
        } */

    }

    @Override
    protected void onPause() {
        Log.d(TAG, "onPause");
        mPaused = true;
        super.onPause();
	// try {
	//     Log.d(TAG, "unbindService");
	//     unbindService(conn);
	// } catch(Exception e) {
	//     Log.e(TAG, "exception:" + e);
	// }
        try {
            IHdmi service = IHdmi.getService(true);
            service.unregisterListener((IHdmiCallback)mHdmiCallback);
        } catch (RemoteException e) {
            e.printStackTrace();
        }
	closeCamera();
	// JniCameraCall.closeDevice();
	stopBackgroundThread();
	if (textureView != null) {
		rootView.removeView(textureView);
		textureView = null;
	}
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        Log.i(TAG, "onDestroy");
    }
}
