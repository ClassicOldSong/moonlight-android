package com.limelight.binding.audio;

import android.media.AudioFormat;

enum EncodedAudioFormat {
    AC3("AC-3", AudioFormat.ENCODING_AC3, 0, 448_000, 1_792),
    EAC3("E-AC-3", AudioFormat.ENCODING_E_AC3, 1, 640_000, 2_560);

    final String displayName;
    final int androidEncoding;
    final int encoderVariant;
    final int bitrate;
    final int frameBytes;

    EncodedAudioFormat(String displayName, int androidEncoding, int encoderVariant,
                       int bitrate, int frameBytes) {
        this.displayName = displayName;
        this.androidEncoding = androidEncoding;
        this.encoderVariant = encoderVariant;
        this.bitrate = bitrate;
        this.frameBytes = frameBytes;
    }

    static EncodedAudioFormat fromMode(AudioOutputMode mode) {
        return mode != null && mode.isEac3() ? EAC3 : AC3;
    }
}
