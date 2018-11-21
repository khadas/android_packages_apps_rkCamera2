LOCAL_PATH:= $(call my-dir)
include $(CLEAR_VARS)

# This is the target being built.
LOCAL_MODULE:= libhdmiinput_jni

LOCAL_DEFAULT_CPP_EXTENSION := cpp

# All of the source files that we will compile.
 LOCAL_SRC_FILES := \
       native.cpp \

 LOCAL_SHARED_LIBRARIES := \
             	libutils \
 		liblog
include $(BUILD_SHARED_LIBRARY)
