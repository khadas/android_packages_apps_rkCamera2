/*
 * Copyright (C) 2018 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.rockchip.camera2;

import android.app.Activity;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Point;
import android.graphics.drawable.Icon;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.MediaRecorder;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.net.Uri;
import android.os.IBinder;
import android.provider.MediaStore;
import android.provider.Settings;
import android.util.DisplayMetrics;
import android.util.Log;
import android.util.Size;
import android.view.Surface;
import android.widget.Toast;

import com.android.rockchip.camera2.util.DataUtils;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.text.SimpleDateFormat;
import java.util.Date;

/**
 * A service which records the device screen and optionally microphone input.
 */
public class ScreenRecordService extends Service {
    public static final int NOTIFICATION_ID = 1;
    private static final String TAG = "RecordingService";
    public static final String CHANNEL_ID = "screen_record";
    private static final String EXTRA_RESULT_CODE = "extra_resultCode";
    private static final String EXTRA_DATA = "extra_data";
    private static final String EXTRA_PATH = "extra_path";

    private static final String ACTION_CREATE = "com.android.rockchip.camera2.screenrecord.CREATE";
    private static final String ACTION_START = "com.android.rockchip.camera2.screenrecord.START";
    private static final String ACTION_STOP = "com.android.rockchip.camera2.screenrecord.STOP";

    private MediaProjectionManager mMediaProjectionManager;
    private MediaProjection mMediaProjection;
    private Surface mInputSurface;
    private VirtualDisplay mVirtualDisplay;
    private MediaRecorder mMediaRecorder;
    private Notification.Builder mRecordingNotificationBuilder;

    private String mSavePath;
    private File mTempFile;

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) {
            return Service.START_NOT_STICKY;
        }
        String action = intent.getAction();
        Log.d(TAG, "onStartCommand " + action);

        switch (action) {
            case ACTION_CREATE:
                createRecordingNotification();
                break;
            case ACTION_START:
                int resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED);
                mSavePath = intent.getStringExtra(EXTRA_PATH);
                Intent data = intent.getParcelableExtra(EXTRA_DATA);
                if (data != null) {
                    mMediaProjection = mMediaProjectionManager.getMediaProjection(resultCode, data);
                    startRecording();
                }
                break;
            case ACTION_STOP:
                stopRecording();
//                saveRecording(notificationManager);
                break;
        }
        return Service.START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onCreate() {
        super.onCreate();

        mMediaProjectionManager =
                (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);


    }

    private void createRecordingNotification() {
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                getString(R.string.app_name),
                NotificationManager.IMPORTANCE_HIGH);
        channel.setDescription(getString(R.string.app_name));
//        channel.enableVibration(true);
        NotificationManager notificationManager =
                (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        notificationManager.createNotificationChannel(channel);

        mRecordingNotificationBuilder = new Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_launcher_foreground)
                .setContentTitle(getResources().getString(R.string.app_name))
                .setUsesChronometer(true)
                .setOngoing(true);
        Notification notification = mRecordingNotificationBuilder.build();
        startForeground(NOTIFICATION_ID, notification);
    }

    /**
     * Begin the recording session
     */
    private void startRecording() {
        try {
            mTempFile = File.createTempFile("temp", ".mp4");
            Log.d(TAG, "Writing video output to: " + mTempFile.getAbsolutePath());

            // Set up media recorder
            mMediaRecorder = new MediaRecorder();
            mMediaRecorder.setAudioSource(MediaRecorder.AudioSource.MIC);
            mMediaRecorder.setVideoSource(MediaRecorder.VideoSource.SURFACE);
            mMediaRecorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);

            // Set up video
            DisplayMetrics metrics = getResources().getDisplayMetrics();
            int screenWidth = metrics.widthPixels;
            int screenHeight = metrics.heightPixels;
            mMediaRecorder.setVideoEncoder(MediaRecorder.VideoEncoder.H264);
            mMediaRecorder.setVideoSize(screenWidth, screenHeight);
            mMediaRecorder.setVideoFrameRate(DataUtils.VIDEO_RECORD_FRAME_RATE);
            mMediaRecorder.setVideoEncodingBitRate(DataUtils.VIDEO_RECORD_BIT_RATE);

            // Set up audio
            mMediaRecorder.setAudioEncoder(MediaRecorder.AudioEncoder.AMR_NB);
            mMediaRecorder.setAudioChannels(DataUtils.AUDIO_RECORD_TOTAL_NUM_TRACKS);
            mMediaRecorder.setAudioEncodingBitRate(DataUtils.AUDIO_RECORD_BIT_RATE);
            mMediaRecorder.setAudioSamplingRate(DataUtils.AUDIO_RECORD_SAMPLE_RATE);

            mMediaRecorder.setOutputFile(mSavePath);
            mMediaRecorder.prepare();

            // Create surface
            mInputSurface = mMediaRecorder.getSurface();
            mVirtualDisplay = mMediaProjection.createVirtualDisplay(
                    "Recording Display",
                    screenWidth,
                    screenHeight,
                    metrics.densityDpi,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                    mInputSurface,
                    null,
                    null);

            mMediaRecorder.start();
        } catch (IOException e) {
            Log.e(TAG, "Error starting screen recording: " + e.getMessage());
            e.printStackTrace();
            throw new RuntimeException(e);
        }
    }

    private void stopRecording() {
        mMediaRecorder.stop();
        mMediaRecorder.release();
        mMediaRecorder = null;
        mMediaProjection.stop();
        mMediaProjection = null;
        mInputSurface.release();
        mVirtualDisplay.release();
//        stopSelf();
    }

//    private void saveRecording(NotificationManager notificationManager) {
//        String fileName = new SimpleDateFormat("'screen-'yyyyMMdd-HHmmss'.mp4'")
//                .format(new Date());
//
//        ContentValues values = new ContentValues();
//        values.put(MediaStore.Video.Media.DISPLAY_NAME, fileName);
//        values.put(MediaStore.Video.Media.MIME_TYPE, "video/mp4");
//        values.put(MediaStore.Video.Media.DATE_ADDED, System.currentTimeMillis());
//        values.put(MediaStore.Video.Media.DATE_TAKEN, System.currentTimeMillis());
//
//        ContentResolver resolver = getContentResolver();
//        Uri collectionUri = MediaStore.Video.Media.getContentUri(
//                MediaStore.VOLUME_EXTERNAL_PRIMARY);
//        Uri itemUri = resolver.insert(collectionUri, values);
//
//        try {
//            // Add to the mediastore
//            OutputStream os = resolver.openOutputStream(itemUri, "w");
//            Files.copy(mTempFile.toPath(), os);
//            os.close();
//
//            Notification notification = createSaveNotification(itemUri);
//            notificationManager.notify(NOTIFICATION_ID, notification);
//
//            mTempFile.delete();
//        } catch (IOException e) {
//            Log.e(TAG, "Error saving screen recording: " + e.getMessage());
//            Toast.makeText(this, R.string.screenrecord_delete_error, Toast.LENGTH_LONG)
//                    .show();
//        }
//    }

    public static Intent getCreateIntent(Context context){
        return new Intent(context, ScreenRecordService.class).setAction(ACTION_CREATE);
    }

    public static Intent getStopIntent(Context context) {
        return new Intent(context, ScreenRecordService.class).setAction(ACTION_STOP);
    }

    public static Intent getStartIntent(Context context, int resultCode, Intent data,
                                        String savePath) {
        return new Intent(context, ScreenRecordService.class)
                .setAction(ACTION_START)
                .putExtra(EXTRA_RESULT_CODE, resultCode)
                .putExtra(EXTRA_DATA, data)
                .putExtra(EXTRA_PATH, savePath);
    }
}
