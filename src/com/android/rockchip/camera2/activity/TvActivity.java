/*
 * Copyright (C) 2015 The Android Open Source Project
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

package com.android.rockchip.camera2.activity;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.media.tv.TvInputInfo;
import android.media.tv.TvInputManager;
import android.media.tv.TvView;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.os.PowerManager;
import android.util.Log;
import android.view.KeyEvent;
import android.view.View;
import android.widget.Toast;

import com.android.rockchip.camera2.R;
import com.android.rockchip.camera2.RockchipCamera2;
import com.android.rockchip.camera2.util.DataUtils;
import com.android.rockchip.camera2.util.SystemPropertiesProxy;

import java.util.List;

public class TvActivity extends Activity implements View.OnAttachStateChangeListener,
        View.OnClickListener {
    private final String TAG = "waha";
    private final int MSG_START_TV = 0;

    private TvView tvView;

    private Object mLock = new Object();
    private PowerManager mPowerManager;
    private TvInputManager mTvInputManager;
    private MyTvinputCallback mTvInputCallback;
    private Uri mChannelUri;
    private MyBroadCastReceiver mBroadCastReceiver;
    private boolean mResumePrepared;
    private boolean mSurfacePrepared;
    private boolean mAlreadyTune;
    private boolean mIsDestory;
    private long mLastClickTime;

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
                if (MSG_START_TV == msg.what) {
                    if (mResumePrepared && mSurfacePrepared && !mAlreadyTune) {
                        mAlreadyTune = true;
                        tvView.tune(DataUtils.INPUT_ID, mChannelUri);
                        startHdmiAudioService();
                    }
                }
            }
        }
    };


    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_tv);

        mResumePrepared = false;
        mSurfacePrepared = false;
        mChannelUri = getIntent().getData();
        Log.v(TAG, "channelUri= " + mChannelUri);
        if (null == mChannelUri) {
            showToast(R.string.err_not_channel);
            finish();
            return;
        }

        mPowerManager = (PowerManager) getSystemService(Context.POWER_SERVICE);
        mTvInputManager = (TvInputManager) getSystemService(Context.TV_INPUT_SERVICE);
        List<TvInputInfo> infos = mTvInputManager.getTvInputList();
        for (TvInputInfo info : infos) {
            Log.v(TAG, "" + info);
        }
        registerReceiver();

        mTvInputCallback = new MyTvinputCallback();
        mTvInputManager.registerCallback(mTvInputCallback, new Handler());

        tvView = (TvView) findViewById(R.id.main_tv_view);
        tvView.addOnAttachStateChangeListener(this);
        tvView.setOnClickListener(this);
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

    private void startHdmiAudioService() {
        Log.v(TAG, "startHdmiAudioService");
        SystemPropertiesProxy.set("vendor.hdmiin.audiorate", "48KHZ");
        Intent intent = new Intent();
        ComponentName cn = new ComponentName(DataUtils.HDMIIN_AUDIO_PACKAGE_NAME, DataUtils.HDMIIN_AUDIO_CLS_NAME);
        intent.setComponent(cn);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent);
        } else {
            startService(intent);
        }
    }

    private void stopHdmiAudioService() {
        Log.v(TAG, "stopHdmiAudioService");
        Intent intent = new Intent();
        ComponentName cn = new ComponentName(DataUtils.HDMIIN_AUDIO_PACKAGE_NAME, DataUtils.HDMIIN_AUDIO_CLS_NAME);
        intent.setComponent(cn);
        stopService(intent);
    }

    @Override
    protected void onStart() {
        super.onStart();
    }

    @Override
    protected void onResume() {
        super.onResume();
        Log.d(TAG, "onResume");
//        if (mStopWithScreenOff) {
//            Intent intent = new Intent(this, WelcomeActivity.class);
//            startActivity(intent);
//            finish();
//        }
        mResumePrepared = true;
        mHandler.removeMessages(MSG_START_TV);
        mHandler.sendEmptyMessageDelayed(MSG_START_TV, DataUtils.START_TV_REVIEW_DELAY);
    }

    @Override
    protected void onPause() {
        super.onPause();
        Log.d(TAG, "onPause");
        mResumePrepared = false;
        mAlreadyTune = false;
        stopHdmiAudioService();
    }

    @Override
    protected void onStop() {
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        mIsDestory = true;
        mHandler.removeMessages(MSG_START_TV);
        unregisterReceiver();
    }

    @Override
    public void onViewAttachedToWindow(View v) {
        Log.v(TAG, "onViewAttachedToWindow");
        mSurfacePrepared = true;
        mHandler.removeMessages(MSG_START_TV);
        mHandler.sendEmptyMessageDelayed(MSG_START_TV, DataUtils.START_TV_REVIEW_DELAY);
    }

    @Override
    public void onViewDetachedFromWindow(View v) {
        Log.v(TAG, "onViewDetachedFromWindow");
        mSurfacePrepared = false;
        tvView.reset();//?
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
    public void onClick(View v) {
        long clickTime = System.currentTimeMillis();
        if (clickTime - mLastClickTime < DataUtils.LIMIT_DOUBLE_CLICK_TIME) {
            return;
        }
        mLastClickTime = clickTime;
        if (R.id.main_tv_view == v.getId()) {
            if (SystemPropertiesProxy.getBoolean("persist.test.jumpcamera", false)) {
                Log.v(TAG, "jump2 rkCamera");
                Intent intent = new Intent();
                intent.setClass(this, RockchipCamera2.class);
                startActivity(intent);
                exitApp();
            }
        }
    }


    private void exitApp() {
        try {
            Thread.sleep(50);
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
        stopHdmiAudioService();
        finish();
        try {
            Thread.sleep(100);
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
        System.exit(0);
    }

    private final class MyTvinputCallback extends TvInputManager.TvInputCallback {
        @Override
        public void onInputAdded(String inputId) {
            Log.v(TAG, "onInputAdded " + inputId);
        }

        @Override
        public void onInputRemoved(String inputId) {
            Log.v(TAG, "onInputRemoved " + inputId);
        }
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
