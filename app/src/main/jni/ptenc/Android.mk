# Android.mk for the optional passthrough encoder (Dolby Digital, DTS, Dolby TrueHD)
#
# This module links against a minimal static libavcodec/libavutil that only
# contains FFmpeg's AC-3, DTS and TrueHD encoders. Those libraries are not checked in; build
# them with ./build-ffmpeg.sh (see that script for details). If they are
# missing for an ABI, the module is skipped and the app falls back to PCM.

LOCAL_PATH := $(call my-dir)

PTENC_FFMPEG_DIR := $(LOCAL_PATH)/ffmpeg/$(TARGET_ARCH_ABI)

ifneq (,$(wildcard $(PTENC_FFMPEG_DIR)/lib/libavcodec.a))

include $(CLEAR_VARS)
LOCAL_MODULE := ptenc-avcodec
LOCAL_SRC_FILES := ffmpeg/$(TARGET_ARCH_ABI)/lib/libavcodec.a
LOCAL_EXPORT_C_INCLUDES := $(PTENC_FFMPEG_DIR)/include
include $(PREBUILT_STATIC_LIBRARY)

include $(CLEAR_VARS)
LOCAL_MODULE := ptenc-avutil
LOCAL_SRC_FILES := ffmpeg/$(TARGET_ARCH_ABI)/lib/libavutil.a
LOCAL_EXPORT_C_INCLUDES := $(PTENC_FFMPEG_DIR)/include
include $(PREBUILT_STATIC_LIBRARY)

include $(CLEAR_VARS)
LOCAL_MODULE := moonlight-ptenc
LOCAL_SRC_FILES := ptenc.c ptenc_jni.c
LOCAL_STATIC_LIBRARIES := ptenc-avcodec ptenc-avutil
LOCAL_LDLIBS := -llog -lm
LOCAL_LDFLAGS += -Wl,--exclude-libs,ALL
LOCAL_BRANCH_PROTECTION := standard
include $(BUILD_SHARED_LIBRARY)

else

$(warning Passthrough encoder: no prebuilt FFmpeg for $(TARGET_ARCH_ABI) in $(PTENC_FFMPEG_DIR), skipping libmoonlight-ptenc. Run app/src/main/jni/ptenc/build-ffmpeg.sh to enable Dolby Digital/DTS passthrough.)

endif
