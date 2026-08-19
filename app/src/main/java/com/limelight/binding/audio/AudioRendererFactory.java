package com.limelight.binding.audio;

import android.content.Context;

import com.limelight.nvstream.av.audio.AudioRenderer;
import com.limelight.nvstream.jni.MoonBridge;
import com.limelight.preferences.PreferenceConfiguration;

public final class AudioRendererFactory {
    private AudioRendererFactory() {
    }

    public static MoonBridge.AudioConfiguration getEffectiveAudioConfiguration(
            PreferenceConfiguration preferences) {
        return preferences.audioOutputMode != null && preferences.audioOutputMode.isEncoded()
                ? MoonBridge.AUDIO_CONFIGURATION_51_SURROUND
                : preferences.audioConfiguration;
    }

    public static AudioRenderer create(Context context, PreferenceConfiguration preferences) {
        if (preferences.audioOutputMode == null || !preferences.audioOutputMode.isEncoded()) {
            // Preserve the current Artemis PCM constructor behavior.
            return new AndroidAudioRenderer(context, preferences.playHostAudio);
        }

        return new Ac3AudioRenderer(context, preferences.audioOutputMode,
                preferences.playHostAudio, preferences.disableWarnings);
    }
}
