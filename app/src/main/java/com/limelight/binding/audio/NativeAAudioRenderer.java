package com.limelight.binding.audio;

import android.os.Build;

import com.limelight.LimeLog;
import com.limelight.nvstream.jni.MoonBridge;

final class NativeAAudioRenderer {
    private NativeAAudioRenderer() {}

    private static final boolean SUPPORTED = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && nativeIsSupported();

    static boolean isSupported() {
        return SUPPORTED;
    }

    static int setup(MoonBridge.AudioConfiguration audioConfiguration, int sampleRate, int samplesPerFrame) {
        if (!SUPPORTED) {
            return -1;
        }

        int result = nativeSetup(audioConfiguration.channelCount, sampleRate, samplesPerFrame);
        if (result == 0) {
            LimeLog.info("Using native AAudio renderer for low-latency audio");
        }
        return result;
    }

    static void start() {
        nativeStart();
    }

    static void stop() {
        nativeStop();
    }

    static void playDecodedAudio(short[] audioData) {
        nativeWrite(audioData, audioData.length);
    }

    static void cleanup() {
        nativeCleanup();
    }

    private static native boolean nativeIsSupported();
    private static native int nativeSetup(int channelCount, int sampleRate, int samplesPerFrame);
    private static native void nativeStart();
    private static native void nativeStop();
    private static native void nativeWrite(short[] audioData, int sampleCount);
    private static native void nativeCleanup();
}
