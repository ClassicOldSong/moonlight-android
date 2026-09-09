/**
 * Created by Karim Mreisi.
 */

package com.limelight.binding.input.virtual_controller;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.view.MotionEvent;

import java.util.ArrayList;
import java.util.List;

/**
 * This is a analog stick on screen element. It is used to get 2-Axis user input.
 */
public class AnalogStick extends VirtualControllerElement {

    /**
     * outer radius size in percent of the ui element
     */
    public static final int SIZE_RADIUS_COMPLETE = 90;
    /**
     * analog stick size in percent of the ui element
     */
    public static final int SIZE_RADIUS_ANALOG_STICK = 90;
    /**
     * dead zone size in percent of the ui element
     */
    public static final int SIZE_RADIUS_DEADZONE = 90;
    /**
     * time frame for a double click
     */
    public final static long timeoutDoubleClick = 350;

    /**
     * touch down time until the deadzone is lifted to allow precise movements with the analog sticks
     */
    public final static long timeoutDeadzone = 150;

    /**
     * Listener interface to update registered observers.
     */
    public interface AnalogStickListener {

        /**
         * onMovement event will be fired on real analog stick movement (outside of the deadzone).
         *
         * @param x horizontal position, value from -1.0 ... 0 .. 1.0
         * @param y vertical position, value from -1.0 ... 0 .. 1.0
         */
        void onMovement(float x, float y);

        /**
         * onClick event will be fired on click on the analog stick
         */
        void onClick();

        /**
         * onDoubleClick event will be fired on a double click in a short time frame on the analog
         * stick.
         */
        void onDoubleClick();

        /**
         * onRevoke event will be fired on unpress of the analog stick.
         */
        void onRevoke();
    }

    /**
     * Movement states of the analog sick.
     */
    private enum STICK_STATE {
        NO_MOVEMENT,
        MOVED_IN_DEAD_ZONE,
        MOVED_ACTIVE
    }

    /**
     * Click type states.
     */
    private enum CLICK_STATE {
        SINGLE,
        DOUBLE
    }

    /**
     * configuration if the analog stick should be displayed as circle or square
     */
    private boolean circle_stick = true; // TODO: implement square sick for simulations

    /**
     * outer radius, this size will be automatically updated on resize
     */
    private float radius_complete = 0;
    /**
     * analog stick radius, this size will be automatically updated on resize
     */
    private float radius_analog_stick = 0;
    /**
     * dead zone radius, this size will be automatically updated on resize
     */
    private float radius_dead_zone = 0;

    /**
     * horizontal position in relation to the center of the element
     */
    private float relative_x = 0;
    /**
     * vertical position in relation to the center of the element
     */
    private float relative_y = 0;


    private double movement_radius = 0;
    private double movement_angle = 0;

    private float position_stick_x = 0;
    private float position_stick_y = 0;

    private final Paint paint = new Paint();

    private STICK_STATE stick_state = STICK_STATE.NO_MOVEMENT;
    private CLICK_STATE click_state = CLICK_STATE.SINGLE;

    private List<AnalogStickListener> listeners = new ArrayList<>();
    private long timeLastClick = 0;

    // Velocity sensitivity configuration (COMMENTED OUT - using edge-impact method instead)
    // private static final float SMASH_VELOCITY_THRESHOLD = 1200.0f; // pixels per second for smash detection
    // private static final long SMASH_TIME_WINDOW = 100; // milliseconds to reach edge for smash

    // Axis snapping configuration - snap to cardinal directions
    private static final float AXIS_SNAP_THRESHOLD = 15.0f; // degrees from cardinal direction to snap

    // Edge-impact smash detection
    private static final float EDGE_THRESHOLD = 0.95f; // 95% of max radius triggers smash
    private static final long SMASH_DURATION = 50; // milliseconds to hold max value on impact
    private boolean wasAtEdge = false;
    private boolean isSmashActive = false;
    private long smashStartTime = 0;

    private final android.os.Handler updateHandler = new android.os.Handler(android.os.Looper.getMainLooper());
    private final Runnable continuousUpdateRunnable = new Runnable() {
        @Override
        public void run() {
            if (isPressed() && stick_state == STICK_STATE.MOVED_ACTIVE) {
                // Re-send the current position to maintain smooth updates
                float complete = radius_complete - radius_analog_stick;
                float correlated_y = (float) (Math.sin(Math.PI / 2 - movement_angle) * (movement_radius));
                float correlated_x = (float) (Math.cos(Math.PI / 2 - movement_angle) * (movement_radius));
                notifyOnMovement(-correlated_x / complete, correlated_y / complete);

                // Schedule next update in 8ms (~120Hz update rate)
                updateHandler.postDelayed(this, 8);
            }
        }
    };

    private static double getMovementRadius(float x, float y) {
        return Math.sqrt(x * x + y * y);
    }

    private static double getAngle(float way_x, float way_y) {
        // prevent divisions by zero for corner cases
        if (way_x == 0) {
            return way_y < 0 ? Math.PI : 0;
        } else if (way_y == 0) {
            if (way_x > 0) {
                return Math.PI * 3 / 2;
            } else if (way_x < 0) {
                return Math.PI * 1 / 2;
            }
        }
        // return correct calculated angle for each quadrant
        if (way_x > 0) {
            if (way_y < 0) {
                // first quadrant
                return 3 * Math.PI / 2 + Math.atan((double) (-way_y / way_x));
            } else {
                // second quadrant
                return Math.PI + Math.atan((double) (way_x / way_y));
            }
        } else {
            if (way_y > 0) {
                // third quadrant
                return Math.PI / 2 + Math.atan((double) (way_y / -way_x));
            } else {
                // fourth quadrant
                return 0 + Math.atan((double) (-way_x / -way_y));
            }
        }
    }

    public AnalogStick(VirtualController controller, Context context, int elementId) {
        super(controller, context, elementId);
        // reset stick position
        position_stick_x = getWidth() / 2;
        position_stick_y = getHeight() / 2;
    }

    public void addAnalogStickListener(AnalogStickListener listener) {
        listeners.add(listener);
    }

    private void notifyOnMovement(float x, float y) {
        _DBG("movement x: " + x + " movement y: " + y);
        // notify listeners
        for (AnalogStickListener listener : listeners) {
            listener.onMovement(x, y);
        }
    }

    private void notifyOnClick() {
        _DBG("click");
        // notify listeners
        for (AnalogStickListener listener : listeners) {
            listener.onClick();
        }
    }

    private void notifyOnDoubleClick() {
        _DBG("double click");
        // notify listeners
        for (AnalogStickListener listener : listeners) {
            listener.onDoubleClick();
        }
    }

    private void notifyOnRevoke() {
        _DBG("revoke");
        // notify listeners
        for (AnalogStickListener listener : listeners) {
            listener.onRevoke();
        }
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        // calculate new radius sizes depending
        radius_complete = getPercent(getCorrectWidth() / 2, 100) - 2 * getDefaultStrokeWidth();
        radius_dead_zone = getPercent(getCorrectWidth() / 2, 30);
        radius_analog_stick = getPercent(getCorrectWidth() / 2, 20);

        super.onSizeChanged(w, h, oldw, oldh);
    }

    @Override
    protected void onElementDraw(Canvas canvas) {
        // set transparent background
        canvas.drawColor(Color.TRANSPARENT);

        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(getDefaultStrokeWidth());

        // draw outer circle
        if (!isPressed() || click_state == CLICK_STATE.SINGLE) {
            paint.setColor(getDefaultColor());
        } else {
            paint.setColor(pressedColor);
        }
        canvas.drawCircle(getWidth() / 2, getHeight() / 2, radius_complete, paint);

        paint.setColor(getDefaultColor());
        // draw dead zone
        canvas.drawCircle(getWidth() / 2, getHeight() / 2, radius_dead_zone, paint);

        // draw stick depending on state
        switch (stick_state) {
            case NO_MOVEMENT: {
                paint.setColor(getDefaultColor());
                canvas.drawCircle(getWidth() / 2, getHeight() / 2, radius_analog_stick, paint);
                break;
            }
            case MOVED_IN_DEAD_ZONE:
            case MOVED_ACTIVE: {
                paint.setColor(pressedColor);
                canvas.drawCircle(position_stick_x, position_stick_y, radius_analog_stick, paint);
                break;
            }
        }
    }

    private void updatePosition(long eventTime) {
        // get 100% way
        float complete = radius_complete - radius_analog_stick;

        // Edge-impact smash detection
        float normalizedRadius = (float) movement_radius / complete;
        long currentTime = System.currentTimeMillis();

        // Detect edge impact
        if (!wasAtEdge && normalizedRadius >= EDGE_THRESHOLD) {
            // Just hit the edge - trigger smash
            wasAtEdge = true;
            isSmashActive = true;
            smashStartTime = currentTime;

            // Haptic feedback
            performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY);

            _DBG("SMASH! Edge impact detected at radius: " + normalizedRadius);
        } else if (wasAtEdge && normalizedRadius < EDGE_THRESHOLD * 0.9f) {
            // Moved away from edge
            wasAtEdge = false;
        }

        // Deactivate smash after duration
        if (isSmashActive && (currentTime - smashStartTime) > SMASH_DURATION) {
            isSmashActive = false;
        }

        // calculate relative way
        float correlated_y = (float) (Math.sin(Math.PI / 2 - movement_angle) * (movement_radius));
        float correlated_x = (float) (Math.cos(Math.PI / 2 - movement_angle) * (movement_radius));

        // update positions
        position_stick_x = getWidth() / 2 - correlated_x;
        position_stick_y = getHeight() / 2 - correlated_y;

        // Stay active even if we're back in the deadzone because we know the user is actively
        // giving analog stick input and we don't want to snap back into the deadzone.
        // We also release the deadzone if the user keeps the stick pressed for a bit to allow
        // them to make precise movements.
        stick_state = (stick_state == STICK_STATE.MOVED_ACTIVE ||
                eventTime - timeLastClick > timeoutDeadzone ||
                movement_radius > radius_dead_zone) ?
                STICK_STATE.MOVED_ACTIVE : STICK_STATE.MOVED_IN_DEAD_ZONE;

        //  trigger move event if state active
        if (stick_state == STICK_STATE.MOVED_ACTIVE) {
            float output_x, output_y;

            // For smash inputs (edge impact), send max value during smash window
            if (isSmashActive) {
                // Send max value in current direction
                float smash_correlated_y = (float) (Math.sin(Math.PI / 2 - movement_angle) * complete);
                float smash_correlated_x = (float) (Math.cos(Math.PI / 2 - movement_angle) * complete);
                output_x = -smash_correlated_x / complete;
                output_y = smash_correlated_y / complete;
            } else {
                // Normal smooth movement for tilt inputs
                output_x = -correlated_x / complete;
                output_y = correlated_y / complete;
            }

            // Apply axis snapping to reduce accidental diagonal inputs
            float[] snapped = applyAxisSnapping(output_x, output_y);
            notifyOnMovement(snapped[0], snapped[1]);

            // Start continuous update loop if not already running
            updateHandler.removeCallbacks(continuousUpdateRunnable);
            updateHandler.postDelayed(continuousUpdateRunnable, 8);
        }
    }

    /**
     * Snap to cardinal directions (left, right, up, down) if close enough
     */
    private float[] applyAxisSnapping(float x, float y) {
        // Calculate angle in degrees (0 = right, 90 = down, 180 = left, 270 = up)
        double angleDegrees = Math.toDegrees(Math.atan2(y, x));
        if (angleDegrees < 0) angleDegrees += 360;

        // Check distance from each cardinal direction
        double distFromRight = Math.min(Math.abs(angleDegrees - 0), Math.abs(angleDegrees - 360));
        double distFromDown = Math.abs(angleDegrees - 90);
        double distFromLeft = Math.abs(angleDegrees - 180);
        double distFromUp = Math.abs(angleDegrees - 270);

        float[] result = new float[2];
        result[0] = x;
        result[1] = y;

        // Snap to horizontal (left/right) if within threshold
        if (distFromRight <= AXIS_SNAP_THRESHOLD || distFromLeft <= AXIS_SNAP_THRESHOLD) {
            result[1] = 0; // Zero out vertical component
            // Preserve magnitude in horizontal direction
            result[0] = x > 0 ? (float)Math.sqrt(x*x + y*y) : -(float)Math.sqrt(x*x + y*y);
        }
        // Snap to vertical (up/down) if within threshold
        else if (distFromDown <= AXIS_SNAP_THRESHOLD || distFromUp <= AXIS_SNAP_THRESHOLD) {
            result[0] = 0; // Zero out horizontal component
            // Preserve magnitude in vertical direction
            result[1] = y > 0 ? (float)Math.sqrt(x*x + y*y) : -(float)Math.sqrt(x*x + y*y);
        }

        return result;
    }

    @Override
    public boolean onElementTouchEvent(MotionEvent event) {
        // save last click state
        CLICK_STATE lastClickState = click_state;

        // get absolute way for each axis
        relative_x = -(getWidth() / 2 - event.getX());
        relative_y = -(getHeight() / 2 - event.getY());

        // get radius and angel of movement from center
        movement_radius = getMovementRadius(relative_x, relative_y);
        movement_angle = getAngle(relative_x, relative_y);

        // pass touch event to parent if out of outer circle
        if (movement_radius > radius_complete && !isPressed())
            return false;

        // chop radius if out of outer circle or near the edge
        if (movement_radius > (radius_complete - radius_analog_stick)) {
            movement_radius = radius_complete - radius_analog_stick;
        }

        // handle event depending on action
        switch (event.getActionMasked()) {
            // down event (touch event)
            case MotionEvent.ACTION_DOWN: {
                // Reset edge tracking for new input
                wasAtEdge = false;
                isSmashActive = false;

                // set to dead zoned, will be corrected in update position if necessary
                stick_state = STICK_STATE.MOVED_IN_DEAD_ZONE;
                // check for double click
                if (lastClickState == CLICK_STATE.SINGLE &&
                        event.getEventTime() - timeLastClick <= timeoutDoubleClick) {
                    click_state = CLICK_STATE.DOUBLE;
                    notifyOnDoubleClick();
                } else {
                    click_state = CLICK_STATE.SINGLE;
                    notifyOnClick();
                }
                // reset last click timestamp
                timeLastClick = event.getEventTime();
                // set item pressed and update
                setPressed(true);
                break;
            }
            // up event (revoke touch)
            case MotionEvent.ACTION_CANCEL:
            case MotionEvent.ACTION_UP: {
                setPressed(false);
                break;
            }
        }

        if (isPressed()) {
            // when is pressed calculate new positions (will trigger movement if necessary)
            updatePosition(event.getEventTime());
        } else {
            // Stop continuous updates
            updateHandler.removeCallbacks(continuousUpdateRunnable);

            stick_state = STICK_STATE.NO_MOVEMENT;
            notifyOnRevoke();

            // not longer pressed reset analog stick
            notifyOnMovement(0, 0);
        }
        // refresh view
        invalidate();
        // accept the touch event
        return true;
    }
}
