LOCAL_PATH:= $(call my-dir)
include $(CLEAR_VARS)
LOCAL_MODULE_TAGS := optional
LOCAL_SRC_FILES := $(call all-subdir-java-files)

LOCAL_STATIC_JAVA_LIBRARIES := android-support-v4
#LOCAL_STATIC_JAVA_LIBRARIES := android-support-v13
#LOCAL_STATIC_JAVA_LIBRARIES += android-ex-camera2-portability

LOCAL_RESOURCE_DIR := $(LOCAL_PATH)/res

LOCAL_CERTIFICATE := platform

LOCAL_PACKAGE_NAME := rkcamera2
include $(BUILD_PACKAGE)
