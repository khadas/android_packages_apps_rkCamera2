package com.android.rockchip.camera2.activity;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.media.tv.TvContract;
import android.media.tv.TvView;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Message;
import android.text.TextUtils;
import android.util.Log;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.PopupWindow;
import android.widget.RelativeLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.android.rockchip.camera2.R;
import com.android.rockchip.camera2.util.DataUtils;
import com.android.rockchip.camera2.util.SystemPropertiesProxy;
import com.android.rockchip.camera2.widget.RoundMenu;

import java.io.DataOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;

public class MainActivity extends Activity implements
        View.OnAttachStateChangeListener, View.OnClickListener {

    private static final String TAG = "MainActivity";

    private final String HDMI_OUT_ACTION = "android.intent.action.HDMI_PLUGGED";
    private final String DP_OUT_ACTION = "android.intent.action.DP_PLUGGED";

    private final int MSG_START_TV = 0;
    private final int MSG_ENABLE_SETTINGS = 1;

    private RelativeLayout rootView;
    private TvView tvView;
    private PopupWindow mPopSettings;
    private RoundMenu rm_pop_settings;
    private TextView txt_hdmirx_edid_1;
    private TextView txt_hdmirx_edid_2;

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
    private boolean mIsSidebandRecord;

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
        rm_pop_settings = (RoundMenu) view.findViewById(R.id.rm_pop_settings);
        rm_pop_settings.setOnStateListener(new RoundMenu.onStateListener() {
            @Override
            public void collapseEnd() {
                mPopSettings.dismiss();
            }

            @Override
            public void centerClick() {
                if (mIsDestory) {
                    return;
                }
                Log.v(TAG, "centerClick mIsSidebandRecord=" + mIsSidebandRecord);
                if (mIsSidebandRecord) {
                    stopSidebandRecord(true);
                } else {
                    mCurrentSavePath = getSavePath(".mp4");
                    Bundle bundle = new Bundle();
                    bundle.putString("status", "1");
                    bundle.putString("storePath", mCurrentSavePath);
                    tvView.sendAppPrivateCommand("record", bundle);
                    rm_pop_settings.setCenterDrawable(R.drawable.ic_record_shutter);
                    showToast(R.string.btn_record_start);
                }
                mIsSidebandRecord = !mIsSidebandRecord;
                mPopSettings.dismiss();
            }
        });
        rm_pop_settings.setCenterDrawable(R.drawable.ic_record_start);
        txt_hdmirx_edid_1 = (TextView) view.findViewById(R.id.txt_hdmirx_edid_1);
        txt_hdmirx_edid_1.setOnClickListener(this);
        txt_hdmirx_edid_2 = (TextView) view.findViewById(R.id.txt_hdmirx_edid_2);
        txt_hdmirx_edid_2.setOnClickListener(this);
        mPopSettings = new PopupWindow(view,
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT, true);
        mPopSettings.setTouchable(true);
        mPopSettings.setBackgroundDrawable(new ColorDrawable(0x00000000));
        mPopSettings.setClippingEnabled(false);
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

    private void pauseSideband() {
        mResumePrepared = false;
        mAlreadyTvTune = false;
        if (null != tvView) {
            tvView.reset();
            rootView.removeView(tvView);
        }
        Log.v(TAG, "pauseSideband");
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
        stopSidebandRecord(true);
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
        if (txt_hdmirx_edid_1.getId() == v.getId()) {
            rm_pop_settings.collapse(true);
            writeHdmiRxEdid(DataUtils.HDMIRX_EDID_1);
        } else if (txt_hdmirx_edid_2.getId() == v.getId()) {
            rm_pop_settings.collapse(true);
            writeHdmiRxEdid(DataUtils.HDMIRX_EDID_2);
        } else if (mPopSettingsPrepared) {
            String hdmiInVersion = SystemPropertiesProxy.getString(DataUtils.PERSIST_HDMIRX_EDID,
                    DataUtils.HDMIRX_EDID_1);
            if (DataUtils.HDMIRX_EDID_1.equals(hdmiInVersion)) {
                txt_hdmirx_edid_1.setTextColor(Color.parseColor("#AAAAFF"));
                txt_hdmirx_edid_2.setTextColor(Color.WHITE);
            } else if (DataUtils.HDMIRX_EDID_2.equals(hdmiInVersion)) {
                txt_hdmirx_edid_1.setTextColor(Color.WHITE);
                txt_hdmirx_edid_2.setTextColor(Color.parseColor("#AAAAFF"));
            }
            mPopSettings.showAtLocation(rootView, Gravity.LEFT, 0, 0);
        }
    }

    private void writeHdmiRxEdid(String value) {
        FileOutputStream file = null;
        try {
            file = new FileOutputStream("sys/class/hdmirx/hdmirx/edid");
            Log.v(TAG, "write hdmirx edid value " + value);
            file.write(value.getBytes());
            file.flush();
            SystemPropertiesProxy.set(DataUtils.PERSIST_HDMIRX_EDID, value);
        } catch (Exception e) {
            showToast("set failed");
            e.printStackTrace();
        } finally {
            if (null != file) {
                try {
                    file.close();
                } catch (Exception e1) {
                }
            }
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

    private void addSaveFileToDb(String filePath) {
        if (TextUtils.isEmpty(filePath)) {
            return;
        }
        Intent intent = new Intent(Intent.ACTION_MEDIA_SCANNER_SCAN_FILE);
        intent.setData(Uri.fromFile(new File(filePath)));
        sendBroadcast(intent);
    }

    private void stopSidebandRecord(boolean sendStopCmd) {
        if (!TextUtils.isEmpty(mCurrentSavePath)) {
            if (sendStopCmd) {
                Bundle bundle = new Bundle();
                bundle.putString("status", "0");
                bundle.putString("storePath", "");
                tvView.sendAppPrivateCommand("record", bundle);
            }
            rm_pop_settings.setCenterDrawable(R.drawable.ic_record_start);
            addSaveFileToDb(mCurrentSavePath);
            showToast(mCurrentSavePath);
            mCurrentSavePath = "";
            Log.v(TAG, "stopSidebandRecord");
        }
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
        pauseSideband();
        stopSidebandRecord(true);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        Log.i(TAG, "onDestroy");
        mIsDestory = true;
        mHandler.removeMessages(MSG_START_TV);
        mHandler.removeMessages(MSG_ENABLE_SETTINGS);
        unregisterReceiver();
    }

    private final class MyBroadCastReceiver extends BroadcastReceiver {

        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            Log.v(TAG, action);
            if (Intent.ACTION_CLOSE_SYSTEM_DIALOGS.equals(action)) {
                exitApp();
            } else if (HDMI_OUT_ACTION.equals(action) || DP_OUT_ACTION.equals(action)) {
                stopSidebandRecord(true);
                mAlreadyTvTune = false;
                resumeSideband();
            } else if (Intent.ACTION_HDMIIN_RK_PRIV_CMD.equals(action)) {
                action = intent.getStringExtra("action");
                Log.v(TAG, "receiver: " + action);
                if ("hdmiinout".equals(action)) {
                    stopSidebandRecord(false);
                }
            }
        }
    }
}
