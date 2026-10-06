#include "ptenc.h"

#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#include <libavcodec/avcodec.h>
#include <libavutil/channel_layout.h>
#include <libavutil/error.h>
#include <libavutil/frame.h>
#include <libavutil/opt.h>

struct Ptenc {
    PtencCodec codec;
    AVCodecContext* ctx;
    AVFrame* frame;
    AVPacket* pkt;
    int channels;
    int frame_size;       // samples per channel per codec frame
    int fill;             // samples per channel currently buffered in frame
    int64_t next_pts;

    // Packets are grouped into write units (only TrueHD uses more than one)
    int units_per_write;
    int max_unit_bytes;
    uint8_t* pending;
    int pending_bytes;
    int pending_units;

    int64_t samples_output;

    // When set, each AC-3/DTS frame is wrapped in an IEC 61937 burst
    int iec61937;
    int burst_bytes;
};

static void set_err(char* err, size_t err_len, const char* what, int averr) {
    if (err == NULL || err_len == 0) {
        return;
    }
    if (averr != 0) {
        char buf[AV_ERROR_MAX_STRING_SIZE];
        av_strerror(averr, buf, sizeof(buf));
        snprintf(err, err_len, "%s: %s", what, buf);
    }
    else {
        snprintf(err, err_len, "%s", what);
    }
}

static enum AVCodecID codec_id(PtencCodec codec) {
    switch (codec) {
    case PTENC_CODEC_AC3:    return AV_CODEC_ID_AC3;
    case PTENC_CODEC_DTS:    return AV_CODEC_ID_DTS;
    case PTENC_CODEC_TRUEHD: return AV_CODEC_ID_TRUEHD;
    default:                 return AV_CODEC_ID_NONE;
    }
}

int ptenc_is_supported(PtencCodec codec) {
    enum AVCodecID id = codec_id(codec);
    return id != AV_CODEC_ID_NONE && avcodec_find_encoder(id) != NULL;
}

// Moonlight delivers FL FR [FC LFE] BL BR. With a native-order layout the
// planes/interleaved slots follow the channel bit order, so we only need a
// layout whose bit order matches positionally. AC-3 accepts 5.1(back); DTS
// and TrueHD only list 5.1(side), whose surrounds sit in the same slots.
static int pick_layout(PtencCodec codec, int channels, AVChannelLayout* layout) {
    switch (channels) {
    case 2:
        *layout = (AVChannelLayout)AV_CHANNEL_LAYOUT_STEREO;
        return 0;
    case 4:
        if (codec == PTENC_CODEC_AC3) {
            *layout = (AVChannelLayout)AV_CHANNEL_LAYOUT_QUAD;
            return 0;
        }
        else if (codec == PTENC_CODEC_DTS) {
            *layout = (AVChannelLayout)AV_CHANNEL_LAYOUT_2_2;
            return 0;
        }
        return -1;
    case 6:
        if (codec == PTENC_CODEC_AC3) {
            *layout = (AVChannelLayout)AV_CHANNEL_LAYOUT_5POINT1_BACK;
        }
        else {
            *layout = (AVChannelLayout)AV_CHANNEL_LAYOUT_5POINT1;
        }
        return 0;
    default:
        return -1;
    }
}

// Picks the encoder sample format closest to our S16 input
static enum AVSampleFormat pick_sample_fmt(const AVCodec* codec) {
    static const enum AVSampleFormat preference[] = {
        AV_SAMPLE_FMT_S16P, AV_SAMPLE_FMT_S16, AV_SAMPLE_FMT_S32P, AV_SAMPLE_FMT_S32,
        AV_SAMPLE_FMT_FLTP, AV_SAMPLE_FMT_FLT,
    };
    const enum AVSampleFormat* fmts = NULL;
    int num = 0;

    if (avcodec_get_supported_config(NULL, codec, AV_CODEC_CONFIG_SAMPLE_FORMAT, 0,
                                     (const void**)&fmts, &num) < 0 || fmts == NULL) {
        return AV_SAMPLE_FMT_NONE;
    }

    for (size_t p = 0; p < sizeof(preference) / sizeof(preference[0]); p++) {
        for (int i = 0; i < num; i++) {
            if (fmts[i] == preference[p]) {
                return fmts[i];
            }
        }
    }
    return AV_SAMPLE_FMT_NONE;
}

Ptenc* ptenc_create(PtencCodec codec_type, int sample_rate, int channels, int bitrate,
                    int iec61937, char* err, size_t err_len) {
    AVChannelLayout layout;
    if (iec61937 && codec_type == PTENC_CODEC_TRUEHD) {
        set_err(err, err_len, "IEC 61937 packing is only implemented for AC-3 and DTS", 0);
        return NULL;
    }
    if (pick_layout(codec_type, channels, &layout) < 0) {
        set_err(err, err_len, "Unsupported channel count for this codec", 0);
        return NULL;
    }

    const AVCodec* codec = avcodec_find_encoder(codec_id(codec_type));
    if (codec == NULL) {
        set_err(err, err_len, "Encoder not compiled into libavcodec", 0);
        return NULL;
    }

    Ptenc* enc = calloc(1, sizeof(*enc));
    if (enc == NULL) {
        set_err(err, err_len, "Out of memory", 0);
        return NULL;
    }
    enc->codec = codec_type;
    enc->channels = channels;

    enc->ctx = avcodec_alloc_context3(codec);
    if (enc->ctx == NULL) {
        set_err(err, err_len, "avcodec_alloc_context3 failed", 0);
        goto fail;
    }

    enc->ctx->sample_rate = sample_rate;
    enc->ctx->sample_fmt = pick_sample_fmt(codec);
    enc->ctx->time_base = (AVRational){ 1, sample_rate };
    if (enc->ctx->sample_fmt == AV_SAMPLE_FMT_NONE) {
        set_err(err, err_len, "No usable sample format", 0);
        goto fail;
    }
    int ret = av_channel_layout_copy(&enc->ctx->ch_layout, &layout);
    if (ret < 0) {
        set_err(err, err_len, "av_channel_layout_copy", ret);
        goto fail;
    }

    switch (codec_type) {
    case PTENC_CODEC_AC3:
        enc->ctx->bit_rate = bitrate > 0 ? bitrate : 640000;
        enc->units_per_write = 1;
        enc->max_unit_bytes = 3840;
        break;
    case PTENC_CODEC_DTS:
        // 1509.75 kbps gives 2012 byte frames, which fit an IEC 61937 type I burst
        enc->ctx->bit_rate = bitrate > 0 ? bitrate : 1509750;
        enc->ctx->strict_std_compliance = FF_COMPLIANCE_EXPERIMENTAL;
        enc->units_per_write = 1;
        enc->max_unit_bytes = 16384;
        break;
    case PTENC_CODEC_TRUEHD:
        enc->ctx->strict_std_compliance = FF_COMPLIANCE_EXPERIMENTAL;
        // Emit a major sync every 8 access units (the minimum) to keep the
        // encoder's internal lookahead as short as possible
        av_opt_set_int(enc->ctx->priv_data, "max_interval", 8, 0);
        enc->units_per_write = PTENC_TRUEHD_UNITS_PER_WRITE;
        enc->max_unit_bytes = 4096;
        break;
    }

    ret = avcodec_open2(enc->ctx, codec, NULL);
    if (ret < 0) {
        set_err(err, err_len, "avcodec_open2", ret);
        goto fail;
    }

    enc->frame_size = enc->ctx->frame_size;
    if (iec61937) {
        // One burst spans the frame's duration as 16-bit stereo: 4 bytes per sample
        enc->iec61937 = 1;
        enc->burst_bytes = enc->frame_size * 4;
    }

    enc->pending = malloc((size_t)enc->max_unit_bytes * enc->units_per_write);
    enc->frame = av_frame_alloc();
    enc->pkt = av_packet_alloc();
    if (enc->pending == NULL || enc->frame == NULL || enc->pkt == NULL) {
        set_err(err, err_len, "Out of memory", 0);
        goto fail;
    }

    enc->frame->format = enc->ctx->sample_fmt;
    enc->frame->nb_samples = enc->frame_size;
    enc->frame->sample_rate = sample_rate;
    ret = av_channel_layout_copy(&enc->frame->ch_layout, &enc->ctx->ch_layout);
    if (ret < 0) {
        set_err(err, err_len, "av_channel_layout_copy", ret);
        goto fail;
    }
    ret = av_frame_get_buffer(enc->frame, 0);
    if (ret < 0) {
        set_err(err, err_len, "av_frame_get_buffer", ret);
        goto fail;
    }

    return enc;

fail:
    ptenc_destroy(enc);
    return NULL;
}

int ptenc_frame_size(const Ptenc* enc) {
    return enc->frame_size;
}

int ptenc_write_size(const Ptenc* enc) {
    return enc->frame_size * enc->units_per_write;
}

int ptenc_codec_delay(const Ptenc* enc) {
    if (enc->codec == PTENC_CODEC_DTS && enc->ctx->initial_padding == 0) {
        // dcaenc doesn't report it; measured as one frame (512 samples)
        return enc->frame_size;
    }
    return enc->ctx->initial_padding;
}

int ptenc_max_output(const Ptenc* enc, int sample_count) {
    // Every frame completed by this call could finish a write unit
    int frames = sample_count / enc->frame_size + 1;
    if (enc->iec61937) {
        return (frames + 1) * enc->burst_bytes;
    }
    return (frames + enc->units_per_write) * enc->max_unit_bytes;
}

int64_t ptenc_samples_output(const Ptenc* enc) {
    return enc->samples_output;
}

// Copies S16 interleaved input into the frame at offset enc->fill
static void convert_input(Ptenc* enc, const int16_t* src, int count) {
    const int nch = enc->channels;
    const int off = enc->fill;

    switch (enc->ctx->sample_fmt) {
    case AV_SAMPLE_FMT_S16P:
        for (int ch = 0; ch < nch; ch++) {
            int16_t* dst = (int16_t*)enc->frame->extended_data[ch] + off;
            for (int i = 0; i < count; i++) {
                dst[i] = src[(size_t)i * nch + ch];
            }
        }
        break;
    case AV_SAMPLE_FMT_S16:
        memcpy((int16_t*)enc->frame->data[0] + (size_t)off * nch, src, (size_t)count * nch * sizeof(int16_t));
        break;
    case AV_SAMPLE_FMT_S32P:
        for (int ch = 0; ch < nch; ch++) {
            int32_t* dst = (int32_t*)enc->frame->extended_data[ch] + off;
            for (int i = 0; i < count; i++) {
                dst[i] = (int32_t)src[(size_t)i * nch + ch] * 65536;
            }
        }
        break;
    case AV_SAMPLE_FMT_S32: {
        int32_t* dst = (int32_t*)enc->frame->data[0] + (size_t)off * nch;
        for (int i = 0; i < count * nch; i++) {
            dst[i] = (int32_t)src[i] * 65536;
        }
        break;
    }
    case AV_SAMPLE_FMT_FLTP:
        for (int ch = 0; ch < nch; ch++) {
            float* dst = (float*)enc->frame->extended_data[ch] + off;
            for (int i = 0; i < count; i++) {
                dst[i] = src[(size_t)i * nch + ch] * (1.0f / 32768.0f);
            }
        }
        break;
    case AV_SAMPLE_FMT_FLT: {
        float* dst = (float*)enc->frame->data[0] + (size_t)off * nch;
        for (int i = 0; i < count * nch; i++) {
            dst[i] = src[i] * (1.0f / 32768.0f);
        }
        break;
    }
    default:
        break;
    }
}

static void put_le16(uint8_t* p, unsigned v) {
    p[0] = v & 0xff;
    p[1] = (v >> 8) & 0xff;
}

// Wraps one AC-3 or DTS frame in an IEC 61937 data burst (little-endian
// 16-bit words, as written to a 16-bit stereo PCM stream), matching
// FFmpeg's spdif muxer. Returns the burst size or a negative error.
static int iec61937_pack(const Ptenc* enc, const uint8_t* data, int size, uint8_t* out) {
    unsigned data_type;
    if (enc->codec == PTENC_CODEC_AC3) {
        // Pc: AC-3 with the frame's bitstream mode (bsmod)
        data_type = 0x01 | ((data[5] & 0x7) << 8);
    }
    else {
        // Pc: DTS type I (512 samples per frame)
        data_type = 0x0B;
    }

    int payload = size & ~1;
    int used = 8 + payload + ((size & 1) ? 2 : 0);
    if (used > enc->burst_bytes) {
        return AVERROR(EINVAL);
    }

    put_le16(out + 0, 0xF872);                        // Pa
    put_le16(out + 2, 0x4E1F);                        // Pb
    put_le16(out + 4, data_type);                     // Pc
    put_le16(out + 6, (unsigned)((size + 1) & ~1) << 3); // Pd: length in bits

    // The bitstream is big-endian 16-bit words; swap to little-endian
    for (int i = 0; i < payload; i += 2) {
        out[8 + i] = data[i + 1];
        out[8 + i + 1] = data[i];
    }
    int pos = 8 + payload;
    if (size & 1) {
        // A final lone byte is MSB aligned
        put_le16(out + pos, (unsigned)data[size - 1] << 8);
        pos += 2;
    }
    memset(out + pos, 0, enc->burst_bytes - pos);
    return enc->burst_bytes;
}

// Sends the full frame to the encoder. Received packets are collected into
// write units; each completed unit is appended to out.
static int flush_frame(Ptenc* enc, uint8_t* out, int out_cap, int* out_len) {
    enc->frame->pts = enc->next_pts;
    enc->next_pts += enc->frame_size;

    int ret = avcodec_send_frame(enc->ctx, enc->frame);
    if (ret < 0) {
        return ret;
    }

    for (;;) {
        ret = avcodec_receive_packet(enc->ctx, enc->pkt);
        if (ret == AVERROR(EAGAIN) || ret == AVERROR_EOF) {
            break;
        }
        else if (ret < 0) {
            return ret;
        }

        if (enc->pkt->size > enc->max_unit_bytes) {
            av_packet_unref(enc->pkt);
            return AVERROR(ENOSPC);
        }
        memcpy(enc->pending + enc->pending_bytes, enc->pkt->data, enc->pkt->size);
        enc->pending_bytes += enc->pkt->size;
        enc->pending_units++;
        av_packet_unref(enc->pkt);

        if (enc->pending_units == enc->units_per_write) {
            if (enc->iec61937) {
                if (*out_len + enc->burst_bytes > out_cap) {
                    return AVERROR(ENOSPC);
                }
                int burst = iec61937_pack(enc, enc->pending, enc->pending_bytes, out + *out_len);
                if (burst < 0) {
                    return burst;
                }
                *out_len += burst;
            }
            else {
                if (*out_len + enc->pending_bytes > out_cap) {
                    return AVERROR(ENOSPC);
                }
                memcpy(out + *out_len, enc->pending, enc->pending_bytes);
                *out_len += enc->pending_bytes;
            }
            enc->samples_output += (int64_t)enc->pending_units * enc->frame_size;
            enc->pending_bytes = 0;
            enc->pending_units = 0;
        }
    }

    return 0;
}

int ptenc_encode(Ptenc* enc, const int16_t* pcm, int sample_count, uint8_t* out, int out_cap) {
    int out_len = 0;
    int consumed = 0;

    while (consumed < sample_count) {
        if (enc->fill == 0) {
            // The encoder may still hold a reference to the previous frame's buffers
            int ret = av_frame_make_writable(enc->frame);
            if (ret < 0) {
                return ret;
            }
        }

        int todo = enc->frame_size - enc->fill;
        if (todo > sample_count - consumed) {
            todo = sample_count - consumed;
        }

        convert_input(enc, pcm + (size_t)consumed * enc->channels, todo);

        enc->fill += todo;
        consumed += todo;

        if (enc->fill == enc->frame_size) {
            enc->fill = 0;
            int ret = flush_frame(enc, out, out_cap, &out_len);
            if (ret < 0) {
                return ret;
            }
        }
    }

    return out_len;
}

void ptenc_destroy(Ptenc* enc) {
    if (enc == NULL) {
        return;
    }
    av_packet_free(&enc->pkt);
    av_frame_free(&enc->frame);
    avcodec_free_context(&enc->ctx);
    free(enc->pending);
    free(enc);
}
