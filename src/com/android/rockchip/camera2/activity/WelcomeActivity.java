package com.android.rockchip.camera2.activity;

import android.app.Activity;
import android.content.Intent;
import android.media.tv.TvContract;

import com.android.rockchip.camera2.RockchipCamera2;
import com.android.rockchip.camera2.util.DataUtils;
import com.android.rockchip.camera2.util.SystemPropertiesProxy;

public class WelcomeActivity extends Activity {

    @Override
    protected void onStart() {
        super.onStart();
        int mode = SystemPropertiesProxy.getInt("persist.sys.hdmiinmode", 0);
        Intent intent = new Intent();
        switch (mode) {
            case 1:
                intent.setClass(this, TvActivity.class);
                intent.setData(TvContract.buildChannelUriForPassthroughInput(DataUtils.INPUT_ID));
                break;
            case 2:
                intent.setClass(this, RockchipCamera2.class);
                break;
            default:
                intent.setClass(this, MainActivity.class);
                break;
        }
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        startActivity(intent);
        finish();
    }
}
