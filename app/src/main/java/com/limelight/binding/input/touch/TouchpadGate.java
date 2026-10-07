package com.limelight.binding.input.touch;

import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;

// Local patch (2026-09-23): decides which touchpad input reaches the trackpad code.
//
// The user's third-party keyboard case has no way to switch its touchpad off, and its own "off while
// typing" lasts about 0.2 s, so a palm moves the pointer and clicks things between keystrokes. Three rules:
//   - a toggle (Ctrl+Shift+M) that switches the pad off until it is pressed again
//   - a pause after every non-modifier key; a touch that starts inside the pause is dropped, and a touch
//     that is already down when typing starts is cancelled
//   - a touch that STARTS within a margin of the pad's edge is dropped, because that is where palms land.
//     A touch that starts inside and then travels to the edge is unaffected.
// A dropped touch is dropped for its whole sequence, so the trackpad contexts never see half a gesture.
// Only touchpad events pass through here; a real mouse is never affected.
public class TouchpadGate {
    public interface Host {
        // Let go of anything the trackpad contexts and the gesture detector are holding
        void cancelTouchpadTouches();

        void onTouchpadToggled(boolean off);
    }

    private final Host host;
    private final int typingPauseMs;
    private final float edgeMm;

    private boolean off;
    // No key has been pressed yet: without this, "now - lastTypingTime" would have to start from a sentinel,
    // and Long.MIN_VALUE overflows to a negative difference that blocks every touch until the first key press.
    private boolean typedYet;
    private long lastTypingTime;
    private boolean sequenceActive;
    private boolean sequenceIgnored;
    private boolean toggleKeyDown;

    private int knownDeviceId = -1;
    private float minX, maxX, minY, maxY;
    private float edgeX, edgeY;

    public TouchpadGate(Host host, int typingPauseMs, float edgeMm) {
        this.host = host;
        this.typingPauseMs = typingPauseMs;
        this.edgeMm = edgeMm;
    }

    public boolean isOff() {
        return off;
    }

    // Returns true when the key was the toggle and must not be sent to the host
    public boolean onKeyDown(KeyEvent event) {
        int keyCode = event.getKeyCode();

        if (keyCode == KeyEvent.KEYCODE_M && event.isCtrlPressed() && event.isShiftPressed()) {
            if (!toggleKeyDown) {
                toggleKeyDown = true;
                off = !off;
                if (off) {
                    sequenceIgnored = true;
                    host.cancelTouchpadTouches();
                }
                host.onTouchpadToggled(off);
            }
            return true;
        }

        if (typingPauseMs > 0 && !isModifier(keyCode)) {
            lastTypingTime = event.getEventTime();
            typedYet = true;
            if (sequenceActive && !sequenceIgnored) {
                // A finger (or palm) is already on the pad while typing starts
                sequenceIgnored = true;
                host.cancelTouchpadTouches();
            }
        }

        return false;
    }

    public boolean onKeyUp(KeyEvent event) {
        if (toggleKeyDown && event.getKeyCode() == KeyEvent.KEYCODE_M) {
            toggleKeyDown = false;
            return true;
        }
        return false;
    }

    public boolean shouldIgnore(MotionEvent event) {
        int action = event.getActionMasked();

        if (action == MotionEvent.ACTION_DOWN) {
            sequenceActive = true;
            sequenceIgnored = off
                    || (typingPauseMs > 0 && typedYet && event.getEventTime() - lastTypingTime < typingPauseMs)
                    || (edgeMm > 0 && startsAtEdge(event));
        } else if (off) {
            sequenceIgnored = true;
        }

        boolean ignore = sequenceIgnored;

        if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
            sequenceActive = false;
            sequenceIgnored = false;
        }

        return ignore;
    }

    private static boolean isModifier(int keyCode) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_SHIFT_LEFT:
            case KeyEvent.KEYCODE_SHIFT_RIGHT:
            case KeyEvent.KEYCODE_CTRL_LEFT:
            case KeyEvent.KEYCODE_CTRL_RIGHT:
            case KeyEvent.KEYCODE_ALT_LEFT:
            case KeyEvent.KEYCODE_ALT_RIGHT:
            case KeyEvent.KEYCODE_META_LEFT:
            case KeyEvent.KEYCODE_META_RIGHT:
            case KeyEvent.KEYCODE_FUNCTION:
            case KeyEvent.KEYCODE_CAPS_LOCK:
            case KeyEvent.KEYCODE_NUM_LOCK:
            case KeyEvent.KEYCODE_SCROLL_LOCK:
                return true;
            default:
                return false;
        }
    }

    private boolean startsAtEdge(MotionEvent event) {
        if (!ensureDeviceInfo(event)) {
            return false;
        }

        float x = event.getX(0);
        float y = event.getY(0);
        return x < minX + edgeX || x > maxX - edgeX || y < minY + edgeY || y > maxY - edgeY;
    }

    // The margin is millimetres where the driver reports a resolution, otherwise a share of the pad
    private boolean ensureDeviceInfo(MotionEvent event) {
        if (event.getDeviceId() != knownDeviceId) {
            knownDeviceId = event.getDeviceId();
            edgeX = edgeY = 0;

            InputDevice device = event.getDevice();
            if (device == null) {
                return false;
            }

            InputDevice.MotionRange rangeX = device.getMotionRange(MotionEvent.AXIS_X, InputDevice.SOURCE_TOUCHPAD);
            InputDevice.MotionRange rangeY = device.getMotionRange(MotionEvent.AXIS_Y, InputDevice.SOURCE_TOUCHPAD);
            if (rangeX == null || rangeY == null) {
                rangeX = device.getMotionRange(MotionEvent.AXIS_X);
                rangeY = device.getMotionRange(MotionEvent.AXIS_Y);
            }
            if (rangeX == null || rangeY == null || rangeX.getRange() <= 0 || rangeY.getRange() <= 0) {
                return false;
            }

            minX = rangeX.getMin();
            maxX = rangeX.getMax();
            minY = rangeY.getMin();
            maxY = rangeY.getMax();

            float marginX = rangeX.getResolution() > 0 ? edgeMm * rangeX.getResolution() : 0.04f * rangeX.getRange();
            float marginY = rangeY.getResolution() > 0 ? edgeMm * rangeY.getResolution() : 0.04f * rangeY.getRange();

            // Never eat more than 15 % of the pad, whatever the driver claims
            edgeX = Math.min(marginX, 0.15f * rangeX.getRange());
            edgeY = Math.min(marginY, 0.15f * rangeY.getRange());
        }

        return edgeX > 0 && edgeY > 0;
    }
}
