package com.limelight.binding.input.touch;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.widget.Toast;

import com.limelight.binding.input.KeyboardTranslator;
import com.limelight.nvstream.NvConnection;
import com.limelight.nvstream.input.KeyboardPacket;
import com.limelight.nvstream.input.MouseButtonPacket;

/**
 * Local addition (2026-09-19), not part of upstream Artemis.
 *
 * The stream protocol has no "touchpad contact" input type and the host can only inject mouse,
 * keyboard, touch-screen and pen input, so the 3- and 4-finger gestures of a Windows precision
 * touchpad cannot be forwarded as such. This class watches the raw contacts of a captured touchpad
 * (SOURCE_TOUCHPAD) and sends the keyboard shortcut that the same gesture triggers on Windows:
 *
 *   3 or 4 fingers up      Win+Tab            (Task View)
 *   3 or 4 fingers down    Win+D              (show desktop)
 *   3 fingers left/right   Alt held + Tab / Shift+Tab per step, Alt released when the fingers lift
 *   4 fingers left/right   Ctrl+Win+Right / Ctrl+Win+Left  (content follows the fingers)
 *   3-finger tap           mouse button X1    (back)     - the user's choice, 2026-09-19
 *   4-finger tap           mouse button X2    (forward)
 *
 * Without this class a third finger was simply ignored by the 2-slot trackpad contexts, so a
 * 3-finger swipe scrolled like a 2-finger one.
 *
 * Holding 3 or more fingers still for 3 seconds toggles small on-screen diagnostics (off by default).
 *
 * Local addition (2026-10-01): two-finger pinch -> Ctrl + mouse wheel, which is what Windows itself turns
 * a precision touchpad pinch into for most apps. Spreading the fingers zooms in. One wheel notch (120) per
 * 15 % change of the finger distance, so apps that zoom one step per wheel event (Chrome) do not race.
 */
public class TrackpadGestureDetector {
    public interface Host {
        // Abort whatever the 1/2-finger trackpad contexts were doing (scroll, pending click, drag)
        void cancelTrackpadTouches();

        // Press the keys in order (modifiers first) and release them shortly after
        void sendKeyCombo(short[] keys);
    }

    // The third finger must land this soon after the first one. A contact that shows up in the
    // middle of a scroll is more likely a resting thumb or a palm than the start of a gesture.
    private static final int LANDING_WINDOW_MS = 300;
    // No gesture right after a (non-modifier) key press: palms brush the pad while typing
    private static final int TYPING_GUARD_MS = 350;
    // Give a late 4th finger a moment to land before a swipe is classified as 3-finger
    private static final int SETTLE_MS = 40;
    private static final int TAP_MAX_MS = 300;
    private static final int KEY_HOLD_MS = 30;
    private static final int MIN_STEP_INTERVAL_MS = 70;
    private static final int DEBUG_HOLD_MS = 3000;

    private static final int MODE_UNDECIDED = 0;
    private static final int MODE_SWITCHER = 1;
    private static final int MODE_DONE = 2;

    private static final String PREFS_NAME = "trackpad_gestures";
    private static final String PREF_DEBUG = "debug";

    private final Context context;
    private final NvConnection conn;
    private final boolean swapAxis;
    private final Host host;
    private final Handler handler = new Handler(Looper.getMainLooper());

    private boolean active;
    private boolean suppressed;
    private boolean fired;
    private int mode;
    private int maxFingers;
    private long firstDownTime;
    private long gestureStartTime;
    private long lastPointerDownTime;
    private long lastStepTime;
    private long lastKeyTime;
    private float lastCx, lastCy;
    private float accX, accY;
    private float maxDisplacement;

    private float fireDistance = 200;
    private float stepDistance = 180;
    private float tapSlop = 50;
    private String padInfo = "pad ?";
    // Local patch (2026-09-23): only the Samsung cover reports the vertical direction the other way round
    private boolean invertVertical;

    private boolean altDown;
    private boolean tabDown;
    private boolean tabBackward;

    private boolean debug;
    private Toast debugToast;

    // Two-finger pinch (2026-10-01). WATCH holds back the 2-finger movement until it is clear whether the
    // fingers move together (scroll, handed back to the trackpad contexts) or apart/together (zoom).
    private static final int PINCH_NONE = 0;
    private static final int PINCH_WATCH = 1;
    private static final int PINCH_SCROLL = 2;
    private static final int PINCH_ZOOM = 3;
    // Zoom ended by a lifted finger: swallow the rest of the contact sequence
    private static final int PINCH_DONE = 4;
    private static final double ZOOM_STEP_LOG = Math.log(1.15);
    private static final short WHEEL_DELTA = 120;

    private int pinchState;
    private int pinchId0, pinchId1;
    private float pinchX0, pinchY0, pinchX1, pinchY1;
    private float pinchStartDist, pinchLastDist;
    private double pinchAcc;
    private float pinchDecide = 40;
    private boolean ctrlDown;

    public TrackpadGestureDetector(Context context, NvConnection conn, boolean swapAxis, Host host) {
        this.context = context;
        this.conn = conn;
        this.swapAxis = swapAxis;
        this.host = host;
        this.debug = prefs().getBoolean(PREF_DEBUG, false);
    }

    private SharedPreferences prefs() {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    private final Runnable tabUpRunnable = new Runnable() {
        @Override
        public void run() {
            if (!tabDown) {
                return;
            }
            byte modifiers = (byte) (KeyboardPacket.MODIFIER_ALT | (tabBackward ? KeyboardPacket.MODIFIER_SHIFT : 0));
            conn.sendKeyboardInput((short) KeyboardTranslator.VK_TAB, KeyboardPacket.KEY_UP, modifiers, (byte) 0);
            if (tabBackward) {
                conn.sendKeyboardInput((short) KeyboardTranslator.VK_LSHIFT, KeyboardPacket.KEY_UP, KeyboardPacket.MODIFIER_ALT, (byte) 0);
            }
            tabDown = false;
        }
    };

    private final Runnable altUpRunnable = new Runnable() {
        @Override
        public void run() {
            if (tabDown) {
                handler.removeCallbacks(tabUpRunnable);
                tabUpRunnable.run();
            }
            if (altDown) {
                conn.sendKeyboardInput((short) KeyboardTranslator.VK_LMENU, KeyboardPacket.KEY_UP, (byte) 0, (byte) 0);
                altDown = false;
            }
        }
    };

    private final Runnable debugHoldRunnable = new Runnable() {
        @Override
        public void run() {
            if (active && !fired && maxDisplacement <= tapSlop) {
                fired = true;
                mode = MODE_DONE;
                debug = !debug;
                prefs().edit().putBoolean(PREF_DEBUG, debug).apply();
                showToast("Gesture diagnostics " + (debug ? "ON" : "OFF") + "  " + padInfo, true);
            }
        }
    };

    // Typing guard input. Modifier keys do not count as typing.
    public void onKeyDown(KeyEvent event) {
        if (!KeyEvent.isModifierKey(event.getKeyCode())) {
            lastKeyTime = event.getEventTime();

            // Diagnostics only: which key code does a special key (ex: the cover's screenshot key) arrive as?
            if (debug && !event.isPrintingKey() && event.getRepeatCount() == 0) {
                showToast("key " + KeyEvent.keyCodeToString(event.getKeyCode()) + " (" + event.getKeyCode() +
                        ") scan=" + event.getScanCode(), false);
            }
        }
    }

    // Shown only while the diagnostics are switched on
    public void note(String text) {
        debugNote(text);
    }

    // Focus lost, stream ending: never leave Alt held on the host
    public void reset() {
        handler.removeCallbacks(debugHoldRunnable);
        flushKeys();
        endZoom();
        pinchState = PINCH_NONE;
        active = false;
    }

    /**
     * @return true if the event belongs to a 3+ finger gesture and must not reach the trackpad contexts
     */
    public boolean onTouchpadEvent(MotionEvent event) {
        long now = event.getEventTime();
        int count = event.getPointerCount();

        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                if (active) {
                    // The previous contact sequence never delivered its final UP
                    finish(now, true);
                }
                if (pinchState == PINCH_ZOOM || pinchState == PINCH_DONE) {
                    // The previous contact sequence never delivered its final UP
                    endZoom();
                }
                pinchState = PINCH_NONE;
                suppressed = false;
                firstDownTime = now;
                return false;

            case MotionEvent.ACTION_POINTER_DOWN:
                if (active) {
                    if (count > maxFingers) {
                        maxFingers = count;
                    }
                    lastPointerDownTime = now;
                    setCentroid(event, -1);
                    return true;
                }
                if (pinchState == PINCH_ZOOM || pinchState == PINCH_DONE) {
                    return true;
                }
                if (count == 2) {
                    watchPinch(event, now);
                    return false;
                }
                pinchState = PINCH_NONE;
                if (suppressed || count < 3) {
                    return false;
                }
                if (now - firstDownTime > LANDING_WINDOW_MS) {
                    suppressed = true;
                    debugNote(count + "F ignored (finger added late)");
                    return false;
                }
                if (now - lastKeyTime < TYPING_GUARD_MS) {
                    suppressed = true;
                    debugNote(count + "F ignored (typing)");
                    return false;
                }
                begin(event, now, count);
                return true;

            case MotionEvent.ACTION_MOVE:
                if (!active) {
                    return onPinchMove(event);
                }
                track(event, now);
                return true;

            case MotionEvent.ACTION_POINTER_UP:
                if (!active) {
                    if (pinchState == PINCH_ZOOM || pinchState == PINCH_DONE) {
                        endZoom();
                        pinchState = PINCH_DONE;
                        return true;
                    }
                    pinchState = PINCH_NONE;
                    return false;
                }
                setCentroid(event, event.getActionIndex());
                return true;

            case MotionEvent.ACTION_UP:
                if (!active) {
                    return endPinchSequence();
                }
                finish(now, Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                        (event.getFlags() & MotionEvent.FLAG_CANCELED) != 0);
                return true;

            case MotionEvent.ACTION_CANCEL:
                if (!active) {
                    return endPinchSequence();
                }
                finish(now, true);
                return true;

            default:
                return active || pinchState == PINCH_ZOOM || pinchState == PINCH_DONE;
        }
    }

    private boolean endPinchSequence() {
        boolean swallow = pinchState == PINCH_ZOOM || pinchState == PINCH_DONE;
        if (swallow) {
            endZoom();
        }
        pinchState = PINCH_NONE;
        return swallow;
    }

    private void watchPinch(MotionEvent event, long now) {
        pinchState = PINCH_NONE;
        // A held physical button means a click-drag, and fresh typing means a palm
        if (event.getButtonState() != 0 || now - lastKeyTime < TYPING_GUARD_MS) {
            return;
        }
        computeThresholds(event);
        pinchId0 = event.getPointerId(0);
        pinchId1 = event.getPointerId(1);
        pinchX0 = event.getX(0);
        pinchY0 = event.getY(0);
        pinchX1 = event.getX(1);
        pinchY1 = event.getY(1);
        pinchStartDist = (float) Math.hypot(pinchX1 - pinchX0, pinchY1 - pinchY0);
        if (pinchStartDist > 0) {
            pinchState = PINCH_WATCH;
        }
    }

    /**
     * @return true if the movement belongs to a pinch (or may still turn into one) and must not reach the contexts
     */
    private boolean onPinchMove(MotionEvent event) {
        if (pinchState == PINCH_DONE) {
            return true;
        }
        if (pinchState != PINCH_WATCH && pinchState != PINCH_ZOOM) {
            return false;
        }

        int i0 = event.findPointerIndex(pinchId0);
        int i1 = event.findPointerIndex(pinchId1);
        if (event.getPointerCount() != 2 || i0 < 0 || i1 < 0) {
            if (pinchState == PINCH_ZOOM) {
                return true;
            }
            pinchState = PINCH_NONE;
            return false;
        }

        float x0 = event.getX(i0), y0 = event.getY(i0);
        float x1 = event.getX(i1), y1 = event.getY(i1);
        float dist = (float) Math.hypot(x1 - x0, y1 - y0);

        if (pinchState == PINCH_ZOOM) {
            if (dist > 0 && pinchLastDist > 0) {
                pinchAcc += Math.log(dist / pinchLastDist);
                pinchLastDist = dist;
                sendZoomSteps();
            }
            return true;
        }

        // WATCH: decide once the fingers have moved far enough either way
        float dDist = Math.abs(dist - pinchStartDist);
        float dCentroid = (float) Math.hypot((x0 + x1 - pinchX0 - pinchX1) / 2, (y0 + y1 - pinchY0 - pinchY1) / 2);
        if (Math.max(dDist, dCentroid) < pinchDecide) {
            // Held back for now. The contexts get the whole movement in one step if this turns out to be a scroll.
            return true;
        }

        float move0 = (float) Math.hypot(x0 - pinchX0, y0 - pinchY0);
        float move1 = (float) Math.hypot(x1 - pinchX1, y1 - pinchY1);
        // Both fingers must move: a resting thumb plus a moving finger is not a pinch
        if (dDist >= 1.5f * dCentroid && Math.min(move0, move1) >= 0.25f * pinchDecide && dist > 0) {
            pinchState = PINCH_ZOOM;
            host.cancelTrackpadTouches();
            conn.sendKeyboardInput((short) KeyboardTranslator.VK_LCONTROL, KeyboardPacket.KEY_DOWN, (byte) 0, (byte) 0);
            ctrlDown = true;
            pinchAcc = Math.log(dist / pinchStartDist);
            pinchLastDist = dist;
            debugNote("2F pinch: Ctrl+wheel  dd=" + Math.round(dist - pinchStartDist) + " dc=" + Math.round(dCentroid) +
                    " decide=" + Math.round(pinchDecide));
            sendZoomSteps();
            return true;
        }

        pinchState = PINCH_SCROLL;
        return false;
    }

    private void sendZoomSteps() {
        while (pinchAcc >= ZOOM_STEP_LOG) {
            // Fingers apart -> wheel up -> zoom in
            conn.sendMouseHighResScroll(WHEEL_DELTA);
            pinchAcc -= ZOOM_STEP_LOG;
            debugNote("2F pinch: zoom in");
        }
        while (pinchAcc <= -ZOOM_STEP_LOG) {
            conn.sendMouseHighResScroll((short) -WHEEL_DELTA);
            pinchAcc += ZOOM_STEP_LOG;
            debugNote("2F pinch: zoom out");
        }
    }

    private void endZoom() {
        if (ctrlDown) {
            conn.sendKeyboardInput((short) KeyboardTranslator.VK_LCONTROL, KeyboardPacket.KEY_UP, (byte) 0, (byte) 0);
            ctrlDown = false;
        }
        pinchAcc = 0;
    }

    /**
     * Uncaptured pointer (Android 14+): the system already recognises the pinch and reports it as a
     * CLASSIFICATION_PINCH stream with a per-event scale factor.
     *
     * @return true if the event was a pinch and has been handled
     */
    public boolean onPinchGesture(MotionEvent event) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            return false;
        }
        int action = event.getActionMasked();
        if (event.getClassification() != MotionEvent.CLASSIFICATION_PINCH) {
            if (ctrlDown && pinchState == PINCH_NONE) {
                endZoom();
            }
            return false;
        }

        if (!ctrlDown) {
            conn.sendKeyboardInput((short) KeyboardTranslator.VK_LCONTROL, KeyboardPacket.KEY_DOWN, (byte) 0, (byte) 0);
            ctrlDown = true;
            pinchAcc = 0;
        }
        if (action == MotionEvent.ACTION_MOVE) {
            float scale = event.getAxisValue(MotionEvent.AXIS_GESTURE_PINCH_SCALE_FACTOR);
            if (scale > 0) {
                pinchAcc += Math.log(scale);
                sendZoomSteps();
            }
        } else if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL || action == MotionEvent.ACTION_POINTER_UP) {
            endZoom();
        }
        return true;
    }

    private void begin(MotionEvent event, long now, int count) {
        flushKeys();
        pinchState = PINCH_NONE;

        active = true;
        fired = false;
        mode = MODE_UNDECIDED;
        maxFingers = count;
        gestureStartTime = now;
        lastPointerDownTime = now;
        accX = accY = 0;
        maxDisplacement = 0;

        computeThresholds(event);
        setCentroid(event, -1);

        host.cancelTrackpadTouches();

        handler.removeCallbacks(debugHoldRunnable);
        handler.postDelayed(debugHoldRunnable, DEBUG_HOLD_MS);
    }

    // Centroid of all contacts except the one that is lifting. Called whenever the set of contacts
    // changes so that the jump of the centroid is not mistaken for movement.
    private void setCentroid(MotionEvent event, int excludedIndex) {
        float sumX = 0, sumY = 0;
        int n = 0;
        for (int i = 0; i < event.getPointerCount(); i++) {
            if (i == excludedIndex) {
                continue;
            }
            sumX += event.getX(i);
            sumY += event.getY(i);
            n++;
        }
        if (n > 0) {
            lastCx = sumX / n;
            lastCy = sumY / n;
        }
    }

    private void track(MotionEvent event, long now) {
        float prevCx = lastCx, prevCy = lastCy;
        setCentroid(event, -1);
        float rawDx = lastCx - prevCx;
        float rawDy = lastCy - prevCy;

        // Same frame as the pointer movement in TrackpadContext: +X right, +Y down
        accX += swapAxis ? rawDy : rawDx;
        accY += swapAxis ? rawDx : rawDy;

        if (mode == MODE_UNDECIDED) {
            maxDisplacement = Math.max(maxDisplacement, (float) Math.hypot(accX, accY));
            if (now - lastPointerDownTime < SETTLE_MS) {
                return;
            }
            float absX = Math.abs(accX), absY = Math.abs(accY);
            if (Math.max(absX, absY) >= fireDistance) {
                fire(absX >= absY, now);
            }
        } else if (mode == MODE_SWITCHER) {
            if (Math.abs(accX) >= stepDistance && now - lastStepTime >= MIN_STEP_INTERVAL_MS) {
                boolean right = accX > 0;
                accX -= right ? stepDistance : -stepDistance;
                step(right, now);
                debugNote("3F step " + (right ? "right: Tab" : "left: Shift+Tab"));
            }
        }
    }

    private void fire(boolean horizontal, long now) {
        fired = true;
        handler.removeCallbacks(debugHoldRunnable);

        int fingers = Math.min(maxFingers, 4);
        String what;
        if (!horizontal) {
            // 2026-09-19: the user reported the two vertical actions the wrong way round on the Tab S7+
            // (swipe towards the keyboard gave the desktop). The pointer frame says accY > 0 is "down", the
            // device says otherwise for this gesture, so the actions are exchanged for that pad. dy in the
            // diagnostics still shows the raw sign.
            // 2026-09-23: a third-party keyboard case is the other way round again, so the exchange now
            // follows the device: only the Samsung cover (sec_touchpad) gets it.
            boolean swipeUp = invertVertical ? accY > 0 : accY < 0;
            if (swipeUp) {
                host.sendKeyCombo(new short[]{KeyboardTranslator.VK_LWIN, KeyboardTranslator.VK_TAB});
                what = "up: Win+Tab";
            } else {
                host.sendKeyCombo(new short[]{KeyboardTranslator.VK_LWIN, KeyboardTranslator.VK_D});
                what = "down: Win+D";
            }
            mode = MODE_DONE;
        } else if (fingers == 3) {
            boolean right = accX > 0;
            mode = MODE_SWITCHER;
            conn.sendKeyboardInput((short) KeyboardTranslator.VK_LMENU, KeyboardPacket.KEY_DOWN, (byte) 0, (byte) 0);
            altDown = true;
            step(right, now);
            what = right ? "right: Alt+Tab (Alt held)" : "left: Alt+Shift+Tab (Alt held)";
        } else {
            // Content follows the fingers: swiping left brings in the desktop on the right
            if (accX < 0) {
                host.sendKeyCombo(new short[]{KeyboardTranslator.VK_LCONTROL, KeyboardTranslator.VK_LWIN, KeyboardTranslator.VK_RIGHT});
                what = "left: Ctrl+Win+Right";
            } else {
                host.sendKeyCombo(new short[]{KeyboardTranslator.VK_LCONTROL, KeyboardTranslator.VK_LWIN, KeyboardTranslator.VK_LEFT});
                what = "right: Ctrl+Win+Left";
            }
            mode = MODE_DONE;
        }

        debugNote(fingers + "F " + what + "  dx=" + Math.round(accX) + " dy=" + Math.round(accY) +
                " fire=" + Math.round(fireDistance) + " t=" + (now - gestureStartTime) + "ms");

        if (mode == MODE_SWITCHER) {
            // Further steps are measured from here
            accX = 0;
        }
    }

    private void step(boolean right, long now) {
        if (tabDown) {
            handler.removeCallbacks(tabUpRunnable);
            tabUpRunnable.run();
        }

        tabBackward = !right;
        if (tabBackward) {
            conn.sendKeyboardInput((short) KeyboardTranslator.VK_LSHIFT, KeyboardPacket.KEY_DOWN, KeyboardPacket.MODIFIER_ALT, (byte) 0);
        }
        byte modifiers = (byte) (KeyboardPacket.MODIFIER_ALT | (tabBackward ? KeyboardPacket.MODIFIER_SHIFT : 0));
        conn.sendKeyboardInput((short) KeyboardTranslator.VK_TAB, KeyboardPacket.KEY_DOWN, modifiers, (byte) 0);
        tabDown = true;
        lastStepTime = now;
        handler.postDelayed(tabUpRunnable, KEY_HOLD_MS);
    }

    private void finish(long now, boolean canceled) {
        handler.removeCallbacks(debugHoldRunnable);

        if (!canceled && !fired) {
            float absX = Math.abs(accX), absY = Math.abs(accY);
            if (Math.max(absX, absY) >= fireDistance) {
                // A flick that ended before the settle time had passed
                fire(absX >= absY, now);
            } else if (maxDisplacement <= tapSlop && now - gestureStartTime <= TAP_MAX_MS) {
                fired = true;
                int fingers = Math.min(maxFingers, 4);
                if (fingers == 3) {
                    clickMouseButton(MouseButtonPacket.BUTTON_X1);
                    debugNote("3F tap: mouse back (X1)");
                } else {
                    clickMouseButton(MouseButtonPacket.BUTTON_X2);
                    debugNote("4F tap: mouse forward (X2)");
                }
            } else {
                debugNote(Math.min(maxFingers, 4) + "F nothing  dx=" + Math.round(accX) + " dy=" + Math.round(accY) +
                        " max=" + Math.round(maxDisplacement) + " fire=" + Math.round(fireDistance) +
                        " tap=" + Math.round(tapSlop) + " t=" + (now - gestureStartTime) + "ms");
            }
        }

        if (altDown) {
            // Alt goes up after the last Tab so that the highlighted window is selected
            handler.removeCallbacks(altUpRunnable);
            handler.postDelayed(altUpRunnable, tabDown ? KEY_HOLD_MS + 10 : 10);
        }

        active = false;
    }

    private void clickMouseButton(final byte button) {
        conn.sendMouseButtonDown(button);
        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                conn.sendMouseButtonUp(button);
            }
        }, KEY_HOLD_MS);
    }

    private void flushKeys() {
        handler.removeCallbacks(tabUpRunnable);
        handler.removeCallbacks(altUpRunnable);
        altUpRunnable.run();
    }

    // Thresholds follow the size of the pad: about 8 mm to trigger, 7 mm per switcher step, 3 mm tap slop.
    // 2026-09-20: the user found the swipe distance too long, so trigger and step were cut by about a third
    // (was 12 mm / 11 mm). The tap slop stays at 3 mm, well below the trigger, so taps are still taps.
    private void computeThresholds(MotionEvent event) {
        float shortSide = 1000;
        float resolution = 0;
        String source = "default";

        InputDevice device = event.getDevice();
        if (device != null) {
            String deviceName = device.getName();
            // Local patch (2026-09-23): see fire()
            invertVertical = deviceName != null && deviceName.toLowerCase().contains("sec_touchpad");
            source = deviceName + " ";

            InputDevice.MotionRange rangeX = device.getMotionRange(MotionEvent.AXIS_X, InputDevice.SOURCE_TOUCHPAD);
            InputDevice.MotionRange rangeY = device.getMotionRange(MotionEvent.AXIS_Y, InputDevice.SOURCE_TOUCHPAD);
            if (rangeX == null || rangeY == null) {
                rangeX = device.getMotionRange(MotionEvent.AXIS_X);
                rangeY = device.getMotionRange(MotionEvent.AXIS_Y);
            }
            if (rangeX != null && rangeY != null && rangeX.getRange() > 0 && rangeY.getRange() > 0) {
                shortSide = Math.min(rangeX.getRange(), rangeY.getRange());
                resolution = Math.min(rangeX.getResolution(), rangeY.getResolution());
                source += Math.round(rangeX.getRange()) + "x" + Math.round(rangeY.getRange());
            }
        }

        fireDistance = 0.13f * shortSide;
        stepDistance = 0.12f * shortSide;
        tapSlop = 0.05f * shortSide;
        pinchDecide = 0.04f * shortSide;

        // Use real millimetres when the driver reports a believable resolution (units per mm)
        if (resolution > 0) {
            float fireMm = 8f * resolution;
            if (fireMm >= 0.05f * shortSide && fireMm <= 0.40f * shortSide) {
                fireDistance = fireMm;
                stepDistance = 7f * resolution;
                tapSlop = 3f * resolution;
                // Pinch vs scroll is decided after 2.5 mm
                pinchDecide = 2.5f * resolution;
                source += " res=" + resolution;
            }
        }

        padInfo = "pad " + source + " fire=" + Math.round(fireDistance) + " swap=" + swapAxis + " invY=" + invertVertical;
    }

    private void debugNote(String text) {
        if (debug) {
            showToast(text, false);
        }
    }

    private void showToast(String text, boolean longer) {
        try {
            if (debugToast != null) {
                debugToast.cancel();
            }
            debugToast = Toast.makeText(context, text, longer ? Toast.LENGTH_LONG : Toast.LENGTH_SHORT);
            debugToast.show();
        } catch (Exception ignored) {
            // Diagnostics must never break input handling
        }
    }
}
