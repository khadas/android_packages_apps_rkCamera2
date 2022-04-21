LOCAL_PATH:= $(call my-dir)

include $(CLEAR_VARS)
LOCAL_MODULE_TAGS := optional

LOCAL_SRC_FILES := $(call all-java-files-under, src)
LOCAL_STATIC_JAVA_LIBRARIES := android-support-v4

LOCAL_PRIVATE_PLATFORM_APIS:= true
LOCAL_RESOURCE_DIR := $(LOCAL_PATH)/res
LOCAL_CERTIFICATE := platform
LOCAL_PACKAGE_NAME := rkCamera2
LOCAL_STATIC_JAVA_LIBRARIES += rockchip.hardware.hdmi-V1.0-java
LOCAL_STATIC_JAVA_LIBRARIES += rockchip.hardware.hdmi-V1.0-java-shallow

include $(BUILD_PACKAGE)

include $(call all-makefiles-under,$(LOCAL_PATH))
