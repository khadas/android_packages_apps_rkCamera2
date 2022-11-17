package com.android.rockchip.camera2.activity;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.ListView;
import android.widget.TextView;

import com.android.rockchip.camera2.R;
import com.android.rockchip.camera2.RockchipCamera2;
import com.android.rockchip.camera2.util.DataUtils;
import com.android.rockchip.camera2.util.SystemPropertiesProxy;

import java.util.ArrayList;
import java.util.List;

public class WelcomeActivity extends Activity {
    public enum HDMIIN_TYPE {
        HDMI_RX,
        MIPI_CSI,
    }

    private InputInfoAdapter mInputInfoAdapter;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
    }

    private void initInputView() {
        setContentView(R.layout.activity_welcome);
        List<InputInfo> inputInfoList = new ArrayList<>();
        inputInfoList.add(new InputInfo("HDMI_RX", HDMIIN_TYPE.HDMI_RX));
        inputInfoList.add(new InputInfo("MIPI_CSI", HDMIIN_TYPE.MIPI_CSI));
        mInputInfoAdapter = new InputInfoAdapter(this, inputInfoList);

        View root_view = findViewById(R.id.root_view);
        root_view.setBackgroundColor(Color.WHITE);
        ListView list_input = findViewById(R.id.list_input);
        list_input.setDivider(new ColorDrawable(Color.GRAY));
        list_input.setDividerHeight(2);
        list_input.setAdapter(mInputInfoAdapter);
    }

    @Override
    protected void onStart() {
        super.onStart();
        int mode = SystemPropertiesProxy.getInt("persist.sys.hdmiinmode", 0);
        Intent intent = new Intent();
        switch (mode) {
            case 1:
                initInputView();
                return;
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

    private class InputInfo {
        private String name;
        private HDMIIN_TYPE type;

        public InputInfo(String name, HDMIIN_TYPE type) {
            this.name = name;
            this.type = type;
        }

        public String getName() {
            return name;
        }

        public HDMIIN_TYPE getType() {
            return type;
        }
    }

    private class InputInfoAdapter extends BaseAdapter {
        private List<InputInfo> mInfoList;
        private LayoutInflater mInflater;

        public InputInfoAdapter(Context context, List<InputInfo> infoList) {
            mInflater = LayoutInflater.from(context);
            mInfoList = infoList;
        }

        @Override
        public int getCount() {
            if (null == mInfoList) {
                return 0;
            }
            return mInfoList.size();
        }

        @Override
        public Object getItem(int position) {
            return null;
        }

        @Override
        public long getItemId(int position) {
            if (null == mInflater) {
                return 0;
            }
            return position;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            ViewHolder holder;
            if (convertView == null) {
                convertView = mInflater.inflate(R.layout.layout_input_info_item, null);
                holder = new ViewHolder();
                holder.txt_input_name = convertView.findViewById(R.id.txt_input_name);
                convertView.setTag(holder);
            } else {
                holder = (ViewHolder) convertView.getTag();
            }

            final InputInfo info = mInfoList.get(position);
            holder.txt_input_name.setText(info.getName());
            convertView.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View view) {
                    SystemPropertiesProxy.set(DataUtils.PERSIST_HDMIIN_TYPE, String.valueOf(info.getType().ordinal()));
                    Intent intent = new Intent();
                    intent.setClass(WelcomeActivity.this, MainActivity.class);
                    startActivity(intent);
                    finish();
                }
            });
            return convertView;
        }

        private final class ViewHolder {
            public TextView txt_input_name;
        }
    }
}
