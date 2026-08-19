package com.limelight.binding.audio;

import com.limelight.LimeLog;

import java.nio.ByteBuffer;
import java.nio.ShortBuffer;

final class NativeAc3Encoder {
    static final int OUTPUT_CAPACITY = 4_096;

    private static final boolean LIBRARY_LOADED;

    static {
        boolean loaded = false;
        try {
            System.loadLibrary("artemis-ac3");
            loaded = true;
        } catch (UnsatisfiedLinkError error) {
            LimeLog.severe("Unable to load Artemis encoded-audio encoder: " + error.getMessage());
        }
        LIBRARY_LOADED = loaded;
    }

    private long handle;

    boolean create(EncodedAudioFormat outputFormat) {
        if (!LIBRARY_LOADED || handle != 0) {
            return handle != 0;
        }
        handle = nativeCreate(48_000, 6, outputFormat.bitrate, outputFormat.encoderVariant);
        return handle != 0;
    }

    int encode(ShortBuffer interleavedS16, ByteBuffer outputPacket) {
        if (handle == 0) {
            return -1;
        }
        outputPacket.clear();
        return nativeEncode(handle, interleavedS16,
                PcmFrameAccumulator.SAMPLES_PER_CHANNEL, outputPacket);
    }

    void reset() {
        if (handle != 0) {
            nativeReset(handle);
        }
    }

    void destroy() {
        if (handle != 0) {
            nativeDestroy(handle);
            handle = 0;
        }
    }

    static String getBuildInfo() {
        return LIBRARY_LOADED ? nativeGetBuildInfo() : "native library unavailable";
    }

    private static native long nativeCreate(int sampleRate, int channels, int bitrate,
                                            int encoderVariant);
    private static native int nativeEncode(long handle, ShortBuffer interleavedS16,
                                           int samplesPerChannel, ByteBuffer outputPacket);
    private static native int nativeFlush(long handle, ByteBuffer outputPacket);
    private static native void nativeReset(long handle);
    private static native void nativeDestroy(long handle);
    private static native String nativeGetBuildInfo();
}
