ARTEMIS_JNI_PATH := $(call my-dir)

# Keep module discovery explicit so newly added native libraries invalidate the
# Android Gradle Plugin's ndk-build model reliably on Windows.
include $(ARTEMIS_JNI_PATH)/moonlight-core/Android.mk
include $(ARTEMIS_JNI_PATH)/evdev_reader/Android.mk
include $(ARTEMIS_JNI_PATH)/ac3/Android.mk
