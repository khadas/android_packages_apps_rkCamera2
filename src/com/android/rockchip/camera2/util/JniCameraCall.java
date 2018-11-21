package com.android.rockchip.camera2.util;

import android.util.Log;

public class JniCameraCall {
	static {
		Log.d("JNI" ,"JNI CAMERA CALL init");
		System.loadLibrary("hdmiinput_jni");
	}
	/*
	public static native int[] get(double x, double y);
	public static native int[] getOther(double x, double y);
	public static native boolean isSupportHDR();
	public static native void setHDREnable(int enable);
	public static native int[] getEetf(float maxDst, float minDst);
	public static native int[] getOetf(float maxDst, float minDst);
	public static native int[] getMaxMin(float maxDst, float minDst);
	*/
	public static native void openDevice();
	public static native void closeDevice();
	public static native int[] getFormat();
}

