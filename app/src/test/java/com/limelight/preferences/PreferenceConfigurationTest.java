package com.limelight.preferences;

import static org.junit.Assert.assertEquals;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.test.core.app.ApplicationProvider;

import com.limelight.TestLogSuppressor;

import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

@Config(sdk = {33}, shadows = {com.limelight.shadows.ShadowMoonBridge.class})
@RunWith(RobolectricTestRunner.class)
public class PreferenceConfigurationTest {
    private Context context;
    private SharedPreferences preferences;

    @BeforeClass
    public static void suppressInvalidIdLogs() {
        TestLogSuppressor.install();
    }

    @Before
    public void setup() {
        context = ApplicationProvider.getApplicationContext();
        preferences = context.getSharedPreferences("rumble-throttle-test", Context.MODE_PRIVATE);
        preferences.edit().clear().commit();
    }

    @Test
    public void rumbleThrottleDefaultsToDisabled() {
        PreferenceConfiguration config = PreferenceConfiguration.readPreferences(context, preferences);

        assertEquals(0, config.rumbleThrottleMs);
    }

    @Test
    public void rumbleThrottleReadsConfiguredInterval() {
        preferences.edit().putInt("seekbar_rumble_throttle_ms", 50).commit();

        PreferenceConfiguration config = PreferenceConfiguration.readPreferences(context, preferences);

        assertEquals(50, config.rumbleThrottleMs);
    }
}
