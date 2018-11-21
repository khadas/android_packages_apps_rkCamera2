LOCAL_PATH:= $(call my-dir)

include $(CLEAR_VARS)
LOCAL_MODULE_TAGS := optional

LOCAL_SRC_FILES := $(call all-java-files-under, src)
LOCAL_STATIC_JAVA_LIBRARIES := android-support-v4

LOCAL_RESOURCE_DIR := $(LOCAL_PATH)/res
LOCAL_JNI_SHARED_LIBRARIES := libhdmiinput_jni
LOCAL_CERTIFICATE := platform

LOCAL_PACKAGE_NAME := rkCamera2
include $(BUILD_PACKAGE)

include $(call all-makefiles-under,$(LOCAL_PATH))
