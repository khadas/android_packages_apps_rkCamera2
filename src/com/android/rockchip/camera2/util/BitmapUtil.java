package com.android.rockchip.camera2.util;

import android.graphics.Bitmap;
import android.text.TextUtils;

import java.io.FileOutputStream;

public class BitmapUtil {

    public static void saveBitmap2file(Bitmap bmp, String path) {
        if (null == bmp || TextUtils.isEmpty(path)) {
            return;
        }
        FileOutputStream fos = null;
        try {
            fos = new FileOutputStream(path);
            bmp.compress(Bitmap.CompressFormat.JPEG, 100, fos);
            fos.flush();
        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            if (null != fos) {
                try {
                    fos.close();
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
        }
    }

}
