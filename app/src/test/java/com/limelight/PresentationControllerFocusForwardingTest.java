package com.limelight;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.limelight.binding.input.capture.InputCaptureProvider;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;

import java.lang.reflect.Field;

@RunWith(RobolectricTestRunner.class)
public class PresentationControllerFocusForwardingTest {
    @Test
    public void presentationControllerFocusChange_forwardsToInputCaptureProvider() throws Exception {
        Game game = Robolectric.buildActivity(Game.class).get();
        RecordingInputCaptureProvider provider = new RecordingInputCaptureProvider();
        setField(game, "inPresentationMode", true);
        setField(game, "inputCaptureProvider", provider);

        game.onPresentationControllerWindowFocusChanged(true);

        assertEquals(1, provider.focusChangeCount);
        assertTrue(provider.lastFocusActive);
    }

    @Test
    public void presentationControllerFocusChange_ignoresNonPresentationGame() throws Exception {
        Game game = Robolectric.buildActivity(Game.class).get();
        RecordingInputCaptureProvider provider = new RecordingInputCaptureProvider();
        setField(game, "inPresentationMode", false);
        setField(game, "inputCaptureProvider", provider);

        game.onPresentationControllerWindowFocusChanged(true);

        assertEquals(0, provider.focusChangeCount);
    }

    @Test
    public void presentationControllerFocusChange_forwardsFocusLossToo() throws Exception {
        Game game = Robolectric.buildActivity(Game.class).get();
        RecordingInputCaptureProvider provider = new RecordingInputCaptureProvider();
        setField(game, "inPresentationMode", true);
        setField(game, "inputCaptureProvider", provider);

        game.onPresentationControllerWindowFocusChanged(false);

        assertEquals(1, provider.focusChangeCount);
        assertFalse(provider.lastFocusActive);
    }

    private static void setField(Game game, String fieldName, Object value) throws Exception {
        Field field = Game.class.getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(game, value);
    }

    private static final class RecordingInputCaptureProvider extends InputCaptureProvider {
        private int focusChangeCount;
        private boolean lastFocusActive;

        @Override
        public void onWindowFocusChanged(boolean focusActive) {
            focusChangeCount++;
            lastFocusActive = focusActive;
        }
    }
}
