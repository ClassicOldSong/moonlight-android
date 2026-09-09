package com.limelight;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;

import com.limelight.utils.ExternalDisplayControlActivity;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

@RunWith(RobolectricTestRunner.class)
public class ExternalDisplayControllerKeyForwardingTest {
    @Test
    public void forwardsEachSupportedKeyActionThroughOneGameHandler() {
        Game game = mock(Game.class);
        KeyEvent down = new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_A);
        KeyEvent up = new KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_A);
        KeyEvent multiple = new KeyEvent(0, "a", 0, 0);
        when(game.handleKeyDown(down)).thenReturn(true);
        when(game.handleKeyUp(up)).thenReturn(true);
        when(game.handleKeyMultiple(multiple)).thenReturn(true);

        assertTrue(ExternalDisplayControlActivity.forwardControllerKeyEvent(game, down));
        assertTrue(ExternalDisplayControlActivity.forwardControllerKeyEvent(game, up));
        assertTrue(ExternalDisplayControlActivity.forwardControllerKeyEvent(game, multiple));

        verify(game).handleKeyDown(down);
        verify(game).handleKeyUp(up);
        verify(game).handleKeyMultiple(multiple);
        verifyNoMoreInteractions(game);
    }

    @Test
    public void ignoresUnknownActionAndMissingGame() {
        Game game = mock(Game.class);
        KeyEvent unknown = new KeyEvent(99, KeyEvent.KEYCODE_A);

        assertFalse(ExternalDisplayControlActivity.forwardControllerKeyEvent(game, unknown));
        assertFalse(ExternalDisplayControlActivity.forwardControllerKeyEvent(null, unknown));
        verifyNoMoreInteractions(game);
    }

    @Test
    public void forwardsTheFirstTouchEventWithoutWaitingForPointerCapture() {
        Game game = mock(Game.class);
        View view = mock(View.class);
        MotionEvent down = MotionEvent.obtain(0, 0, MotionEvent.ACTION_DOWN, 10, 10, 0);
        when(game.handleMotionEvent(view, down)).thenReturn(true);

        assertTrue(ExternalDisplayControlActivity.forwardControllerTouchEvent(game, view, down));

        verify(game).handleMotionEvent(view, down);
        verifyNoMoreInteractions(game);
        down.recycle();
    }
}
