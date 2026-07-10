package com.limelight.binding.input.capture;

import static org.junit.Assert.assertNotNull;

import android.app.Activity;
import android.view.View;

import com.limelight.binding.input.evdev.EvdevListener;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;

@RunWith(RobolectricTestRunner.class)
public class InputCaptureManagerTargetTest {
    @Test
    public void getInputCaptureProvider_acceptsExplicitTargetView() {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        View targetView = new View(activity);
        EvdevListener listener = new NoOpEvdevListener();

        InputCaptureProvider provider = InputCaptureManager.getInputCaptureProvider(activity, targetView, listener);

        assertNotNull(provider);
    }

    private static final class NoOpEvdevListener implements EvdevListener {
        @Override
        public void mouseMove(int deltaX, int deltaY) {
        }

        @Override
        public void mouseButtonEvent(int buttonId, boolean down) {
        }

        @Override
        public void mouseVScroll(byte amount) {
        }

        @Override
        public void mouseHScroll(byte amount) {
        }

        @Override
        public void keyboardEvent(boolean buttonDown, short keyCode) {
        }
    }
}
