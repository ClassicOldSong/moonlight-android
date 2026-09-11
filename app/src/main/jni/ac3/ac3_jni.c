#include <jni.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#include <libavcodec/avcodec.h>
#include <libavutil/avutil.h>
#include <libavutil/channel_layout.h>
#include <libavutil/error.h>
#include <libavutil/frame.h>

typedef struct ArtemisAc3Encoder {
    AVCodecContext *codec_context;
    AVFrame *frame;
    AVPacket *packet;
    int64_t next_pts;
} ArtemisAc3Encoder;

static void destroy_encoder(ArtemisAc3Encoder *encoder) {
    if (encoder == NULL) return;
    av_packet_free(&encoder->packet);
    av_frame_free(&encoder->frame);
    avcodec_free_context(&encoder->codec_context);
    free(encoder);
}

JNIEXPORT jlong JNICALL
Java_com_limelight_binding_audio_NativeAc3Encoder_nativeCreate(
        JNIEnv *env, jclass clazz, jint sample_rate, jint channels, jint bitrate,
        jint encoder_variant) {
    (void) env;
    (void) clazz;
    if (sample_rate != 48000 || channels != 6) return 0;

    const char *codec_name;
    enum AVSampleFormat sample_format;
    if (encoder_variant == 0 && bitrate == 448000) {
        codec_name = "ac3_fixed";
        sample_format = AV_SAMPLE_FMT_S32P;
    } else if (encoder_variant == 1 && bitrate == 640000) {
        codec_name = "eac3";
        sample_format = AV_SAMPLE_FMT_FLTP;
    } else {
        return 0;
    }

    const AVCodec *codec = avcodec_find_encoder_by_name(codec_name);
    if (codec == NULL) return 0;
    ArtemisAc3Encoder *encoder = calloc(1, sizeof(*encoder));
    if (encoder == NULL) return 0;

    encoder->codec_context = avcodec_alloc_context3(codec);
    encoder->frame = av_frame_alloc();
    encoder->packet = av_packet_alloc();
    if (encoder->codec_context == NULL || encoder->frame == NULL || encoder->packet == NULL) {
        destroy_encoder(encoder);
        return 0;
    }

    encoder->codec_context->sample_rate = sample_rate;
    encoder->codec_context->sample_fmt = sample_format;
    encoder->codec_context->bit_rate = bitrate;
    encoder->codec_context->time_base = (AVRational) {1, sample_rate};
    av_channel_layout_default(&encoder->codec_context->ch_layout, channels);
    if (avcodec_open2(encoder->codec_context, codec, NULL) < 0 ||
            encoder->codec_context->frame_size != 1536) {
        destroy_encoder(encoder);
        return 0;
    }

    encoder->frame->format = encoder->codec_context->sample_fmt;
    encoder->frame->sample_rate = sample_rate;
    if (av_channel_layout_copy(&encoder->frame->ch_layout,
            &encoder->codec_context->ch_layout) < 0) {
        destroy_encoder(encoder);
        return 0;
    }
    encoder->frame->nb_samples = encoder->codec_context->frame_size;
    if (av_frame_get_buffer(encoder->frame, 0) < 0) {
        destroy_encoder(encoder);
        return 0;
    }
    return (jlong) (intptr_t) encoder;
}

JNIEXPORT jint JNICALL
Java_com_limelight_binding_audio_NativeAc3Encoder_nativeEncode(
        JNIEnv *env, jclass clazz, jlong handle, jobject input_buffer,
        jint samples_per_channel, jobject output_buffer) {
    (void) clazz;
    ArtemisAc3Encoder *encoder = (ArtemisAc3Encoder *) (intptr_t) handle;
    int16_t *input = (*env)->GetDirectBufferAddress(env, input_buffer);
    uint8_t *output = (*env)->GetDirectBufferAddress(env, output_buffer);
    jlong input_capacity = (*env)->GetDirectBufferCapacity(env, input_buffer);
    jlong output_capacity = (*env)->GetDirectBufferCapacity(env, output_buffer);
    if (encoder == NULL || input == NULL || output == NULL || samples_per_channel != 1536 ||
            input_capacity < 1536 * 6 || output_capacity < 2560) return -1;
    if (av_frame_make_writable(encoder->frame) < 0) return -2;

    /* Moonlight decoder order and AV_CH_LAYOUT_5POINT1 are both FL FR FC LFE BL BR. */
    for (int channel = 0; channel < 6; channel++) {
        if (encoder->codec_context->sample_fmt == AV_SAMPLE_FMT_S32P) {
            int32_t *plane = (int32_t *) encoder->frame->data[channel];
            for (int sample = 0; sample < 1536; sample++) {
                plane[sample] = ((int32_t) input[sample * 6 + channel]) * 65536;
            }
        } else if (encoder->codec_context->sample_fmt == AV_SAMPLE_FMT_FLTP) {
            float *plane = (float *) encoder->frame->data[channel];
            for (int sample = 0; sample < 1536; sample++) {
                plane[sample] = (float) input[sample * 6 + channel] / 32768.0f;
            }
        } else {
            return -6;
        }
    }
    encoder->frame->pts = encoder->next_pts;
    encoder->next_pts += 1536;
    av_packet_unref(encoder->packet);
    if (avcodec_send_frame(encoder->codec_context, encoder->frame) < 0) return -3;
    int result = avcodec_receive_packet(encoder->codec_context, encoder->packet);
    if (result < 0) return -4;
    if (encoder->packet->size > output_capacity) return -5;
    memcpy(output, encoder->packet->data, (size_t) encoder->packet->size);
    return encoder->packet->size;
}

JNIEXPORT jint JNICALL
Java_com_limelight_binding_audio_NativeAc3Encoder_nativeFlush(
        JNIEnv *env, jclass clazz, jlong handle, jobject output_buffer) {
    (void) env; (void) clazz; (void) handle; (void) output_buffer;
    return 0;
}

JNIEXPORT void JNICALL
Java_com_limelight_binding_audio_NativeAc3Encoder_nativeReset(
        JNIEnv *env, jclass clazz, jlong handle) {
    (void) env; (void) clazz;
    ArtemisAc3Encoder *encoder = (ArtemisAc3Encoder *) (intptr_t) handle;
    if (encoder != NULL) {
        avcodec_flush_buffers(encoder->codec_context);
        encoder->next_pts = 0;
    }
}

JNIEXPORT void JNICALL
Java_com_limelight_binding_audio_NativeAc3Encoder_nativeDestroy(
        JNIEnv *env, jclass clazz, jlong handle) {
    (void) env; (void) clazz;
    destroy_encoder((ArtemisAc3Encoder *) (intptr_t) handle);
}

JNIEXPORT jstring JNICALL
Java_com_limelight_binding_audio_NativeAc3Encoder_nativeGetBuildInfo(
        JNIEnv *env, jclass clazz) {
    (void) clazz;
    const char *architecture =
#if defined(__aarch64__)
            "arm64-v8a";
#elif defined(__arm__)
            "armeabi-v7a";
#elif defined(__x86_64__)
            "x86_64";
#elif defined(__i386__)
            "x86";
#else
            "unknown";
#endif
    char text[160];
    snprintf(text, sizeof(text),
             "FFmpeg %s db69d06eeeab ac3_fixed+eac3 abi=%s avcodec=%u",
             av_version_info(), architecture, avcodec_version());
    return (*env)->NewStringUTF(env, text);
}
