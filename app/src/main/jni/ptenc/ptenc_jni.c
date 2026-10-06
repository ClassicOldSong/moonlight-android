// JNI glue for com.limelight.binding.audio.PassthroughEncoder

#include <jni.h>
#include <stdlib.h>

#include <android/log.h>

#include "ptenc.h"

#define LOG_TAG "moonlight-ptenc"

typedef struct {
    Ptenc* enc;
    int channels;
    int16_t* pcm;
    int pcm_cap;      // in samples per channel
    uint8_t* out;
    int out_cap;      // in bytes
} JniPtenc;

static void destroy(JniPtenc* j) {
    if (j == NULL) {
        return;
    }
    ptenc_destroy(j->enc);
    free(j->pcm);
    free(j->out);
    free(j);
}

JNIEXPORT jboolean JNICALL
Java_com_limelight_binding_audio_PassthroughEncoder_nativeIsSupported(JNIEnv* env, jclass clazz, jint codec) {
    return ptenc_is_supported((PtencCodec)codec) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jlong JNICALL
Java_com_limelight_binding_audio_PassthroughEncoder_nativeCreate(JNIEnv* env, jclass clazz, jint codec,
                                                                 jint sampleRate, jint channels, jint bitrate,
                                                                 jboolean iec61937) {
    char err[256] = "";
    JniPtenc* j = calloc(1, sizeof(*j));
    if (j == NULL) {
        return 0;
    }

    j->enc = ptenc_create((PtencCodec)codec, sampleRate, channels, bitrate, iec61937 ? 1 : 0, err, sizeof(err));
    if (j->enc == NULL) {
        __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, "Failed to create encoder %d: %s", codec, err);
        free(j);
        return 0;
    }
    j->channels = channels;

    __android_log_print(ANDROID_LOG_INFO, LOG_TAG,
                        "Encoder %d ready: %d Hz, %d ch, frame %d, write %d samples, codec delay %d%s",
                        codec, sampleRate, channels, ptenc_frame_size(j->enc),
                        ptenc_write_size(j->enc), ptenc_codec_delay(j->enc), iec61937 ? ", IEC 61937" : "");
    return (jlong)(intptr_t)j;
}

JNIEXPORT jint JNICALL
Java_com_limelight_binding_audio_PassthroughEncoder_nativeWriteSize(JNIEnv* env, jclass clazz, jlong handle) {
    return ptenc_write_size(((JniPtenc*)(intptr_t)handle)->enc);
}

JNIEXPORT jint JNICALL
Java_com_limelight_binding_audio_PassthroughEncoder_nativeCodecDelay(JNIEnv* env, jclass clazz, jlong handle) {
    return ptenc_codec_delay(((JniPtenc*)(intptr_t)handle)->enc);
}

JNIEXPORT jint JNICALL
Java_com_limelight_binding_audio_PassthroughEncoder_nativeMaxOutput(JNIEnv* env, jclass clazz, jlong handle,
                                                                    jint sampleCount) {
    return ptenc_max_output(((JniPtenc*)(intptr_t)handle)->enc, sampleCount);
}

JNIEXPORT jlong JNICALL
Java_com_limelight_binding_audio_PassthroughEncoder_nativeSamplesOutput(JNIEnv* env, jclass clazz, jlong handle) {
    return ptenc_samples_output(((JniPtenc*)(intptr_t)handle)->enc);
}

JNIEXPORT jint JNICALL
Java_com_limelight_binding_audio_PassthroughEncoder_nativeEncode(JNIEnv* env, jclass clazz, jlong handle,
                                                                 jshortArray pcm, jint sampleCount, jbyteArray out) {
    JniPtenc* j = (JniPtenc*)(intptr_t)handle;

    if (sampleCount <= 0) {
        return 0;
    }
    if ((*env)->GetArrayLength(env, pcm) < sampleCount * j->channels) {
        return -1;
    }

    // Grow scratch buffers as needed (only happens on the first call or two)
    if (sampleCount > j->pcm_cap) {
        int16_t* p = realloc(j->pcm, (size_t)sampleCount * j->channels * sizeof(int16_t));
        if (p == NULL) {
            return -1;
        }
        j->pcm = p;
        j->pcm_cap = sampleCount;
    }
    int needed_out = ptenc_max_output(j->enc, sampleCount);
    if (needed_out > j->out_cap) {
        uint8_t* o = realloc(j->out, needed_out);
        if (o == NULL) {
            return -1;
        }
        j->out = o;
        j->out_cap = needed_out;
    }

    (*env)->GetShortArrayRegion(env, pcm, 0, sampleCount * j->channels, j->pcm);

    int len = ptenc_encode(j->enc, j->pcm, sampleCount, j->out, j->out_cap);
    if (len < 0) {
        __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, "Encode failed: %d", len);
        return -1;
    }
    if (len > 0) {
        if ((*env)->GetArrayLength(env, out) < len) {
            return -2;
        }
        (*env)->SetByteArrayRegion(env, out, 0, len, (const jbyte*)j->out);
    }
    return len;
}

JNIEXPORT void JNICALL
Java_com_limelight_binding_audio_PassthroughEncoder_nativeDestroy(JNIEnv* env, jclass clazz, jlong handle) {
    destroy((JniPtenc*)(intptr_t)handle);
}
