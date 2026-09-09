package com.limelight.ui;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

import java.util.concurrent.atomic.AtomicInteger;

@RunWith(RobolectricTestRunner.class)
public class ExternalControllerViewGenericMotionTest {
    @Test
    public void dispatchGenericMotionEvent_forPointerHoverMove_usesInputCallback() {
        ExternalControllerView view = new ExternalControllerView(RuntimeEnvironment.getApplication());
        RecordingCallbacks callbacks = new RecordingCallbacks(true);
        MotionEvent event = obtainMouseEvent(MotionEvent.ACTION_HOVER_MOVE);

        view.setInputCallbacks(callbacks);

        assertTrue(view.dispatchGenericMotionEvent(event));
        assertTrue(callbacks.genericMotionCalled);
        assertSame(view, callbacks.callbackView);
        assertSame(event, callbacks.callbackEvent);
    }

    @Test
    public void dispatchGenericMotionEvent_forPointerScroll_usesInputCallback() {
        ExternalControllerView view = new ExternalControllerView(RuntimeEnvironment.getApplication());
        RecordingCallbacks callbacks = new RecordingCallbacks(true);
        MotionEvent event = obtainMouseEvent(MotionEvent.ACTION_SCROLL);

        view.setInputCallbacks(callbacks);

        assertTrue(view.dispatchGenericMotionEvent(event));
        assertTrue(callbacks.genericMotionCalled);
        assertSame(view, callbacks.callbackView);
        assertSame(event, callbacks.callbackEvent);
    }

    @Test
    public void onCapturedPointerEvent_forRelativeMouseMove_usesInputCallback() {
        ExternalControllerView view = new ExternalControllerView(RuntimeEnvironment.getApplication());
        RecordingCallbacks callbacks = new RecordingCallbacks(true);
        MotionEvent event = obtainMouseEvent(MotionEvent.ACTION_MOVE);
        event.setSource(InputDevice.SOURCE_MOUSE_RELATIVE);

        view.setInputCallbacks(callbacks);

        assertTrue(view.onCapturedPointerEvent(event));
        assertTrue(callbacks.genericMotionCalled);
        assertSame(view, callbacks.callbackView);
        assertSame(event, callbacks.callbackEvent);
    }

    @Test
    public void dispatchGenericMotionEvent_forJoystickMove_skipsInputCallback() {
        ExternalControllerView view = new ExternalControllerView(RuntimeEnvironment.getApplication());
        RecordingCallbacks callbacks = new RecordingCallbacks(true);
        MotionEvent event = obtainMouseEvent(MotionEvent.ACTION_MOVE);
        event.setSource(InputDevice.SOURCE_JOYSTICK);

        view.setInputCallbacks(callbacks);
        view.dispatchGenericMotionEvent(event);

        assertFalse(callbacks.genericMotionCalled);
    }

    @Test
    public void dispatchGenericMotionEvent_forPointerHoverMove_reportsUserActivity() {
        ExternalControllerView view = new ExternalControllerView(RuntimeEnvironment.getApplication());
        RecordingCallbacks callbacks = new RecordingCallbacks(true);
        AtomicInteger userActivityCount = new AtomicInteger();
        MotionEvent event = obtainMouseEvent(MotionEvent.ACTION_HOVER_MOVE);

        view.setInputCallbacks(callbacks);
        view.setUserActivityCallback(userActivityCount::incrementAndGet);

        assertTrue(view.dispatchGenericMotionEvent(event));
        assertEquals(1, userActivityCount.get());
    }

    @Test
    public void onCapturedPointerEvent_forRelativeMouseMove_reportsUserActivity() {
        ExternalControllerView view = new ExternalControllerView(RuntimeEnvironment.getApplication());
        RecordingCallbacks callbacks = new RecordingCallbacks(true);
        AtomicInteger userActivityCount = new AtomicInteger();
        MotionEvent event = obtainMouseEvent(MotionEvent.ACTION_MOVE);
        event.setSource(InputDevice.SOURCE_MOUSE_RELATIVE);

        view.setInputCallbacks(callbacks);
        view.setUserActivityCallback(userActivityCount::incrementAndGet);

        assertTrue(view.onCapturedPointerEvent(event));
        assertEquals(1, userActivityCount.get());
    }

    @Test
    public void dispatchGenericMotionEvent_forJoystickMove_skipsUserActivity() {
        ExternalControllerView view = new ExternalControllerView(RuntimeEnvironment.getApplication());
        RecordingCallbacks callbacks = new RecordingCallbacks(true);
        AtomicInteger userActivityCount = new AtomicInteger();
        MotionEvent event = obtainMouseEvent(MotionEvent.ACTION_MOVE);
        event.setSource(InputDevice.SOURCE_JOYSTICK);

        view.setInputCallbacks(callbacks);
        view.setUserActivityCallback(userActivityCount::incrementAndGet);

        view.dispatchGenericMotionEvent(event);
        assertEquals(0, userActivityCount.get());
    }

    private static MotionEvent obtainMouseEvent(int action) {
        MotionEvent event = MotionEvent.obtain(0, 0, action, 10, 10, 0);
        event.setSource(InputDevice.SOURCE_MOUSE);
        return event;
    }

    private static final class RecordingCallbacks implements ExternalControllerView.InputCallbacks {
        private final boolean consumeGenericMotion;
        private boolean genericMotionCalled;
        private View callbackView;
        private MotionEvent callbackEvent;

        private RecordingCallbacks(boolean consumeGenericMotion) {
            this.consumeGenericMotion = consumeGenericMotion;
        }

        @Override
        public boolean handleGenericMotion(View view, MotionEvent event) {
            genericMotionCalled = true;
            callbackView = view;
            callbackEvent = event;
            return consumeGenericMotion;
        }

        @Override
        public boolean handleKeyUp(KeyEvent event) {
            return false;
        }

        @Override
        public boolean handleKeyDown(KeyEvent event) {
            return false;
        }

        @Override
        public boolean handleCommitText(CharSequence text) {
            return false;
        }

        @Override
        public boolean handleDeleteSurroundingText(int beforeLength, int afterLength) {
            return false;
        }
    }
}
