package com.limelight.binding.input.driver;

import android.app.Activity;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.SparseIntArray;
import android.view.InputDevice;
import android.view.KeyCharacterMap;
import android.view.KeyEvent;
import android.view.View;

import java.lang.ref.WeakReference;

import com.limelight.nvstream.input.ControllerPacket;

// Android never sees driver-owned controllers (e.g. the Steam Controller) as gamepads, so while
// no stream is using them, turn their D-pad, left stick, A and B into key presses for our own UI.
// Apps can't inject input (INJECT_EVENTS), so keys go straight to the visible activity.
// ponytail: dialogs are separate windows and don't get these keys; no key repeat while held.
public class MenuNavigator {
    // The Artemis activity in front, set by ArtemisApplication (null while streaming or in the background)
    public static volatile WeakReference<Activity> target = new WeakReference<>(null);

    private static final float STICK_THRESHOLD = 0.5f;
    private static final int[][] KEYS = {
            {ControllerPacket.UP_FLAG, KeyEvent.KEYCODE_DPAD_UP},
            {ControllerPacket.DOWN_FLAG, KeyEvent.KEYCODE_DPAD_DOWN},
            {ControllerPacket.LEFT_FLAG, KeyEvent.KEYCODE_DPAD_LEFT},
            {ControllerPacket.RIGHT_FLAG, KeyEvent.KEYCODE_DPAD_RIGHT},
            {ControllerPacket.A_FLAG, KeyEvent.KEYCODE_DPAD_CENTER},
            {ControllerPacket.B_FLAG, KeyEvent.KEYCODE_BACK},
    };

    private final SparseIntArray pressed = new SparseIntArray(); // controller id -> flags
    private final Handler handler = new Handler(Looper.getMainLooper());

    // Driver threads call this with the raw driver state (stick Y is down-positive)
    synchronized void update(int controllerId, int buttons, float leftStickX, float leftStickY) {
        int state = buttons;
        if (leftStickX < -STICK_THRESHOLD) state |= ControllerPacket.LEFT_FLAG;
        if (leftStickX > STICK_THRESHOLD) state |= ControllerPacket.RIGHT_FLAG;
        if (leftStickY < -STICK_THRESHOLD) state |= ControllerPacket.UP_FLAG;
        if (leftStickY > STICK_THRESHOLD) state |= ControllerPacket.DOWN_FLAG;

        int changed = pressed.get(controllerId) ^ state;
        pressed.put(controllerId, state);
        for (int[] key : KEYS) {
            if ((changed & key[0]) != 0) {
                inject(key[1], (state & key[0]) != 0);
            }
        }
    }

    // Release whatever a controller was holding, e.g. when it disconnects or a stream takes over
    synchronized void release(int controllerId) {
        update(controllerId, 0, 0, 0);
        pressed.delete(controllerId);
    }

    synchronized void releaseAll() {
        while (pressed.size() > 0) {
            release(pressed.keyAt(0));
        }
    }

    void inject(int keyCode, boolean down) { // package-private for MenuNavigatorTest
        long now = SystemClock.uptimeMillis();
        KeyEvent event = new KeyEvent(now, now, down ? KeyEvent.ACTION_DOWN : KeyEvent.ACTION_UP, keyCode, 0, 0,
                KeyCharacterMap.VIRTUAL_KEYBOARD, 0, 0, InputDevice.SOURCE_DPAD);
        handler.post(() -> {
            Activity activity = target.get();
            if (activity == null || activity.isFinishing()) {
                return;
            }

            // Activities that opt into OnBackInvokedCallback never turn a BACK key event into a back
            // press (Android 13+), so press back directly on release
            if (keyCode == KeyEvent.KEYCODE_BACK) {
                if (!down) {
                    activity.onBackPressed();
                }
                return;
            }

            if (activity.dispatchKeyEvent(event) || !down) {
                return;
            }

            // Nobody consumed the arrow: move focus the way the framework would for a real D-pad
            int direction = focusDirection(keyCode);
            if (direction == 0) {
                return;
            }
            View focused = activity.getCurrentFocus();
            View next = focused != null ? focused.focusSearch(direction) : activity.getWindow().getDecorView();
            if (next != null) {
                next.requestFocusFromTouch();
            }
        });
    }

    private static int focusDirection(int keyCode) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_DPAD_UP: return View.FOCUS_UP;
            case KeyEvent.KEYCODE_DPAD_DOWN: return View.FOCUS_DOWN;
            case KeyEvent.KEYCODE_DPAD_LEFT: return View.FOCUS_LEFT;
            case KeyEvent.KEYCODE_DPAD_RIGHT: return View.FOCUS_RIGHT;
            default: return 0;
        }
    }
}
