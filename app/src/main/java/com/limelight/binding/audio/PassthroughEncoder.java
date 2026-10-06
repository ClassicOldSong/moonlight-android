package com.limelight.binding.audio;

import com.limelight.LimeLog;

/**
 * Streaming passthrough bitstream encoder (Dolby Digital, DTS, Dolby TrueHD)
 * backed by a minimal libavcodec (see app/src/main/jni/ptenc).
 *
 * Input is interleaved 16-bit PCM in Moonlight's channel order
 * (FL FR FC LFE BL BR for 5.1). Input chunks of any size are accepted;
 * output is produced whenever a complete write unit is available
 * (one AC-3 or DTS frame, or a batch of 16 TrueHD access units).
 *
 * Not thread-safe: use from the audio thread only.
 */
public class PassthroughEncoder {

    public enum Codec {
        // id must match PtencCodec in ptenc.h
        AC3(0, "ac3", "Dolby Digital"),
        DTS(1, "dts", "DTS"),
        TRUEHD(2, "truehd", "Dolby TrueHD");

        public final int id;
        public final String prefValue;
        public final String displayName;

        Codec(int id, String prefValue, String displayName) {
            this.id = id;
            this.prefValue = prefValue;
            this.displayName = displayName;
        }

        /** @return the codec for a preference value, or null for "off"/unknown */
        public static Codec fromPrefValue(String value) {
            for (Codec c : values()) {
                if (c.prefValue.equals(value)) {
                    return c;
                }
            }
            return null;
        }
    }

    private static final boolean LIBRARY_LOADED;

    static {
        boolean loaded;
        try {
            System.loadLibrary("moonlight-ptenc");
            loaded = true;
        } catch (Throwable t) {
            // The library is only built when the FFmpeg prebuilts are present
            LimeLog.warning("Passthrough encoder library unavailable: " + t.getMessage());
            loaded = false;
        }
        LIBRARY_LOADED = loaded;
    }

    private long handle;
    private final Codec codec;
    private final int channelCount;
    private final int sampleRate;
    private final int writeSize;
    private final int codecDelay;
    private byte[] outBuffer = new byte[0];
    private long samplesIn;

    /** @return true if libmoonlight-ptenc.so is packaged in this build */
    public static boolean isAvailable() {
        return LIBRARY_LOADED;
    }

    /** @return true if this build can encode the given codec */
    public static boolean isSupported(Codec codec) {
        return LIBRARY_LOADED && nativeIsSupported(codec.id);
    }

    /**
     * @param sampleRate sample rate in Hz (48000 for Moonlight)
     * @param channelCount 2 or 6 (4 also works for AC-3 and DTS)
     * @param bitrate bits per second, or 0 for the codec default
     * @param iec61937 wrap each frame in an IEC 61937 burst for an ENCODING_IEC61937
     *                 track (AC-3 and DTS only)
     * @throws IllegalStateException if the encoder can't be created
     */
    public PassthroughEncoder(Codec codec, int sampleRate, int channelCount, int bitrate, boolean iec61937) {
        if (!LIBRARY_LOADED) {
            throw new IllegalStateException("Passthrough encoder library not available");
        }

        handle = nativeCreate(codec.id, sampleRate, channelCount, bitrate, iec61937);
        if (handle == 0) {
            throw new IllegalStateException("Failed to create " + codec.displayName + " encoder ("
                    + sampleRate + " Hz, " + channelCount + " ch)");
        }

        this.codec = codec;
        this.channelCount = channelCount;
        this.sampleRate = sampleRate;
        this.writeSize = nativeWriteSize(handle);
        this.codecDelay = nativeCodecDelay(handle);
    }

    public Codec getCodec() {
        return codec;
    }

    /** @return samples per channel delivered by each write unit */
    public int getWriteSize() {
        return writeSize;
    }

    /** @return decoder priming delay in samples per channel */
    public int getCodecDelay() {
        return codecDelay;
    }

    /** @return samples per channel fed in but not yet returned as bitstream */
    public long getBufferedSamples() {
        return samplesIn - nativeSamplesOutput(handle);
    }

    /** @return samples per channel represented by all bitstream returned so far */
    public long getSamplesOutput() {
        return nativeSamplesOutput(handle);
    }

    public int getSampleRate() {
        return sampleRate;
    }

    /**
     * Feeds interleaved PCM. The returned byte count refers to {@link #getOutput()}
     * and holds zero or more complete write units.
     *
     * @param pcm interleaved samples; its length must be a multiple of the channel count
     * @return number of bytes available in getOutput(), or a negative value on error
     */
    public int encode(short[] pcm) {
        int samplesPerChannel = pcm.length / channelCount;

        int maxOut = nativeMaxOutput(handle, samplesPerChannel);
        if (outBuffer.length < maxOut) {
            outBuffer = new byte[maxOut];
        }

        samplesIn += samplesPerChannel;
        return nativeEncode(handle, pcm, samplesPerChannel, outBuffer);
    }

    /** Buffer holding the output of the last {@link #encode(short[])} call. */
    public byte[] getOutput() {
        return outBuffer;
    }

    public void release() {
        if (handle != 0) {
            nativeDestroy(handle);
            handle = 0;
        }
    }

    private static native boolean nativeIsSupported(int codec);
    private static native long nativeCreate(int codec, int sampleRate, int channelCount, int bitrate, boolean iec61937);
    private static native int nativeWriteSize(long handle);
    private static native int nativeCodecDelay(long handle);
    private static native int nativeMaxOutput(long handle, int sampleCount);
    private static native long nativeSamplesOutput(long handle);
    private static native int nativeEncode(long handle, short[] pcm, int samplesPerChannel, byte[] out);
    private static native void nativeDestroy(long handle);
}
