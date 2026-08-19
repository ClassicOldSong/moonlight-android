package com.limelight.binding.audio;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class AudioOutputModeTest {
    @Test
    public void preferenceValuesAreStableAndUnknownValuesUsePcm() {
        assertEquals(AudioOutputMode.PCM, AudioOutputMode.fromPreference(null));
        assertEquals(AudioOutputMode.PCM, AudioOutputMode.fromPreference("unknown"));
        assertEquals(AudioOutputMode.AC3_AUTO, AudioOutputMode.fromPreference("ac3_auto"));
        assertEquals(AudioOutputMode.AC3_FORCE, AudioOutputMode.fromPreference("ac3_force"));
        assertEquals(AudioOutputMode.EAC3_AUTO, AudioOutputMode.fromPreference("eac3_auto"));
        assertEquals(AudioOutputMode.EAC3_FORCE, AudioOutputMode.fromPreference("eac3_force"));
    }

    @Test
    public void encodedModesSelectTheExpectedFormatAndRoutePolicy() {
        assertFalse(AudioOutputMode.PCM.isEncoded());
        assertTrue(AudioOutputMode.AC3_AUTO.isEncoded());
        assertTrue(AudioOutputMode.AC3_AUTO.isAutomatic());
        assertFalse(AudioOutputMode.AC3_FORCE.isAutomatic());
        assertFalse(AudioOutputMode.AC3_AUTO.isEac3());
        assertTrue(AudioOutputMode.EAC3_AUTO.isEac3());
        assertTrue(AudioOutputMode.EAC3_AUTO.isAutomatic());
        assertFalse(AudioOutputMode.EAC3_FORCE.isAutomatic());
        assertEquals(EncodedAudioFormat.AC3,
                EncodedAudioFormat.fromMode(AudioOutputMode.AC3_FORCE));
        assertEquals(EncodedAudioFormat.EAC3,
                EncodedAudioFormat.fromMode(AudioOutputMode.EAC3_FORCE));
    }
}
