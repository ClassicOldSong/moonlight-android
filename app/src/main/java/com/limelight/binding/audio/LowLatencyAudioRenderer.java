package com.limelight.binding.audio;

import android.content.Context;

import com.limelight.LimeLog;
import com.limelight.nvstream.av.audio.AudioRenderer;
import com.limelight.nvstream.jni.MoonBridge;

public class LowLatencyAudioRenderer implements AudioRenderer {
    // Some Android TV devices deny AudioTrack's fast path even when low-latency
    // mode is requested. Prefer AAudio's callback path on Android O+ to avoid
    // those device-specific AudioTrack latency spikes, then fall back if needed.
    private final AndroidAudioRenderer audioTrackRenderer;
    private final boolean enableAudioFx;
    private boolean useNativeAAudio;

    public LowLatencyAudioRenderer(Context context, boolean enableAudioFx) {
        this.audioTrackRenderer = new AndroidAudioRenderer(context, enableAudioFx);
        this.enableAudioFx = enableAudioFx;
    }

    @Override
    public int setup(MoonBridge.AudioConfiguration audioConfiguration, int sampleRate, int samplesPerFrame) {
        if (!enableAudioFx && NativeAAudioRenderer.isSupported()) {
            int result = NativeAAudioRenderer.setup(audioConfiguration, sampleRate, samplesPerFrame);
            if (result == 0) {
                useNativeAAudio = true;
                return 0;
            }

            LimeLog.info("Native AAudio renderer setup failed; falling back to AudioTrack: " + result);
            NativeAAudioRenderer.cleanup();
        }
        else if (enableAudioFx) {
            LimeLog.info("Audio effects enabled; using AudioTrack renderer");
        }

        return audioTrackRenderer.setup(audioConfiguration, sampleRate, samplesPerFrame);
    }

    @Override
    public void start() {
        if (useNativeAAudio) {
            NativeAAudioRenderer.start();
        }
        else {
            audioTrackRenderer.start();
        }
    }

    @Override
    public void stop() {
        if (useNativeAAudio) {
            NativeAAudioRenderer.stop();
        }
        else {
            audioTrackRenderer.stop();
        }
    }

    @Override
    public void playDecodedAudio(short[] audioData) {
        if (MoonBridge.getPendingAudioDuration() >= 40) {
            LimeLog.info("Too much pending audio data: " + MoonBridge.getPendingAudioDuration() + " ms");
            return;
        }

        if (useNativeAAudio) {
            NativeAAudioRenderer.playDecodedAudio(audioData);
        }
        else {
            audioTrackRenderer.playDecodedAudio(audioData);
        }
    }

    @Override
    public void cleanup() {
        if (useNativeAAudio) {
            NativeAAudioRenderer.cleanup();
            useNativeAAudio = false;
        }
        else {
            audioTrackRenderer.cleanup();
        }
    }
}
