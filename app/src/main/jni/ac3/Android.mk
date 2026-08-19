LOCAL_PATH := $(call my-dir)

include $(CLEAR_VARS)
LOCAL_MODULE := artemis_ffmpeg_avcodec
LOCAL_SRC_FILES := ../third_party/ffmpeg/prebuilt/$(TARGET_ARCH_ABI)/lib/libavcodec.a
LOCAL_EXPORT_C_INCLUDES := $(LOCAL_PATH)/../third_party/ffmpeg/include
include $(PREBUILT_STATIC_LIBRARY)

include $(CLEAR_VARS)
LOCAL_MODULE := artemis_ffmpeg_avutil
LOCAL_SRC_FILES := ../third_party/ffmpeg/prebuilt/$(TARGET_ARCH_ABI)/lib/libavutil.a
LOCAL_EXPORT_C_INCLUDES := $(LOCAL_PATH)/../third_party/ffmpeg/include
include $(PREBUILT_STATIC_LIBRARY)

include $(CLEAR_VARS)
LOCAL_MODULE := artemis-ac3
LOCAL_SRC_FILES := ac3_jni.c
LOCAL_CFLAGS := -std=c11 -Wall -Wextra -Werror
LOCAL_LDLIBS := -llog -lm -ldl
LOCAL_STATIC_LIBRARIES := artemis_ffmpeg_avcodec artemis_ffmpeg_avutil
LOCAL_LDFLAGS := -Wl,--exclude-libs,ALL
include $(BUILD_SHARED_LIBRARY)
