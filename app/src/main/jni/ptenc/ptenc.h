#pragma once

// Streaming passthrough bitstream encoder built on libavcodec.
//
// Moonlight hands us decoded PCM in small chunks (typically 5 or 10 ms).
// The supported formats all work on fixed-size frames:
//   AC-3 (Dolby Digital)  1536 samples (32 ms at 48 kHz)
//   DTS (core)             512 samples (10.7 ms)
//   Dolby TrueHD            40 samples per access unit (0.83 ms), written in
//                          groups of PTENC_TRUEHD_UNITS_PER_WRITE units
// This wrapper accumulates PCM until a full frame is available, encodes it,
// and returns bitstream data ready for a passthrough AudioTrack.

#include <stddef.h>
#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

typedef enum {
    PTENC_CODEC_AC3 = 0,
    PTENC_CODEC_DTS = 1,
    PTENC_CODEC_TRUEHD = 2,
} PtencCodec;

// Android's TrueHD passthrough expects access units in batches of 16
// (same as ExoPlayer's TRUEHD_RECHUNK_SAMPLE_COUNT).
#define PTENC_TRUEHD_UNITS_PER_WRITE 16

typedef struct Ptenc Ptenc;

// Returns non-zero if libavcodec was built with an encoder for codec.
int ptenc_is_supported(PtencCodec codec);

// Creates an encoder for interleaved S16 PCM.
//
// channels: 2 (FL FR), 4 (FL FR BL BR) or 6 (FL FR FC LFE BL BR).
// That is the channel order Moonlight's Opus decoder produces, which is
// also Android's CHANNEL_OUT_* order.
//
// bitrate: bits per second, or 0 for the codec default (AC-3 640 kbps,
// DTS 1509.75 kbps; ignored for lossless TrueHD).
//
// iec61937: wrap each AC-3/DTS frame in an IEC 61937 burst (as sent over
// S/PDIF/HDMI) so the output can be written to an ENCODING_IEC61937 track:
// one burst lasts exactly one frame (AC-3 6144 bytes, DTS 2048 bytes).
// Not supported for TrueHD.
//
// Returns NULL on failure; if err is non-NULL a message is written to it.
Ptenc* ptenc_create(PtencCodec codec, int sample_rate, int channels, int bitrate,
                    int iec61937, char* err, size_t err_len);

// Samples per channel in one codec frame / access unit.
int ptenc_frame_size(const Ptenc* enc);

// Samples per channel represented by one write (one frame, or for TrueHD
// a batch of PTENC_TRUEHD_UNITS_PER_WRITE access units).
int ptenc_write_size(const Ptenc* enc);

// Encoder delay in samples (priming the decoder will output before audio).
int ptenc_codec_delay(const Ptenc* enc);

// Upper bound on bytes ptenc_encode() can return for sample_count samples.
int ptenc_max_output(const Ptenc* enc, int sample_count);

// Feeds sample_count samples per channel of interleaved S16 PCM.
//
// Every complete write unit produced by this call is appended to out.
// Returns the number of bytes written to out (0 if nothing was completed),
// or a negative value on error (including out_cap < ptenc_max_output()).
int ptenc_encode(Ptenc* enc, const int16_t* pcm, int sample_count, uint8_t* out, int out_cap);

// Total samples per channel represented by all bytes returned so far.
int64_t ptenc_samples_output(const Ptenc* enc);

void ptenc_destroy(Ptenc* enc);

#ifdef __cplusplus
}
#endif
