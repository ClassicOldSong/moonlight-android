package com.limelight.nvstream;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.limelight.shadows.ShadowMoonBridge;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 33, shadows = {ShadowMoonBridge.class})
public class StreamConfigurationTest {

    @Test
    public void microphoneFlagDefaultsDisabled() {
        StreamConfiguration config = new StreamConfiguration.Builder().build();

        assertFalse(config.getEnableMicrophone());
    }

    @Test
    public void microphoneFlagCanBeEnabled() {
        StreamConfiguration config = new StreamConfiguration.Builder()
                .setEnableMicrophone(true)
                .build();

        assertTrue(config.getEnableMicrophone());
    }
}
