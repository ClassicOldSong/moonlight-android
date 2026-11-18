package com.limelight.binding.input.driver;

import static org.junit.Assert.assertEquals;

import android.view.KeyEvent;

import com.limelight.nvstream.input.ControllerPacket;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

@Config(sdk = {33})
@RunWith(RobolectricTestRunner.class)
public class MenuNavigatorTest {
    private final List<String> keys = new ArrayList<>();
    private final MenuNavigator nav = new MenuNavigator() {
        @Override
        void inject(int keyCode, boolean down) {
            keys.add(keyCode + (down ? " down" : " up"));
        }
    };

    @Test
    public void mapsButtonsAndStickToNavigationKeys() {
        nav.update(1, ControllerPacket.A_FLAG, 0, 0);
        nav.update(1, ControllerPacket.A_FLAG, 0, 0); // no change, no key
        nav.update(1, 0, 0, -0.9f);                   // A released, stick up (Y down-positive)
        nav.update(1, ControllerPacket.B_FLAG | ControllerPacket.UP_FLAG, 0, -0.9f); // D-pad up while stick up: still one key
        nav.release(1);

        assertEquals(Arrays.asList(
                KeyEvent.KEYCODE_DPAD_CENTER + " down",
                KeyEvent.KEYCODE_DPAD_UP + " down",
                KeyEvent.KEYCODE_DPAD_CENTER + " up",
                KeyEvent.KEYCODE_BACK + " down",
                KeyEvent.KEYCODE_DPAD_UP + " up",
                KeyEvent.KEYCODE_BACK + " up"), keys);
    }
}
