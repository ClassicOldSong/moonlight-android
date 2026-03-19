package com.limelight.preferences;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.preference.PreferenceManager;
import androidx.test.core.app.ApplicationProvider;

import com.limelight.shadows.ShadowMoonBridge;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 33, shadows = {ShadowMoonBridge.class})
public class PreferenceConfigurationTest {
    private Context context;
    private SharedPreferences prefs;

    @Before
    public void setUp() {
        context = ApplicationProvider.getApplicationContext();
        prefs = PreferenceManager.getDefaultSharedPreferences(context);
        prefs.edit().clear().commit();
    }

    @Test
    public void readPreferencesLoadsMicrophoneSettings() {
        prefs.edit()
                .putBoolean(PreferenceConfiguration.ENABLE_MICROPHONE_PREF_STRING, true)
                .putString(PreferenceConfiguration.MICROPHONE_DEVICE_PREF_STRING, "7")
                .commit();

        PreferenceConfiguration config = PreferenceConfiguration.readPreferences(context, prefs);

        assertTrue(config.enableMicrophone);
        assertEquals(7, config.microphoneDeviceId);
    }

    @Test
    public void readPreferencesResetsInvalidMicrophoneDeviceSelection() {
        prefs.edit()
                .putString(PreferenceConfiguration.MICROPHONE_DEVICE_PREF_STRING, "invalid")
                .commit();

        PreferenceConfiguration config = PreferenceConfiguration.readPreferences(context, prefs);

        assertEquals(0, config.microphoneDeviceId);
        assertEquals("0", prefs.getString(PreferenceConfiguration.MICROPHONE_DEVICE_PREF_STRING, null));
    }
}
