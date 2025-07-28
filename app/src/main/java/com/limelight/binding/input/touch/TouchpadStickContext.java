package com.limelight.binding.input.touch;

import com.limelight.nvstream.NvConnection;
import com.limelight.binding.input.TouchpadToStickHandler;

public class TouchpadStickContext implements TouchContext {
    private final NvConnection conn;
    private final int actionIndex;
    private final TouchpadToStickHandler touchpadHandler;

    private float lastX;
    private float lastY;
    private boolean tracking;
    private boolean cancelled;
    private int pointerCount;
    private long lastUpdateTime = 0;

    public TouchpadStickContext(NvConnection conn, int actionIndex, TouchpadToStickHandler touchpadHandler) {
        this.conn = conn;
        this.actionIndex = actionIndex;
        this.touchpadHandler = touchpadHandler;
        this.cancelled = false;
        this.pointerCount = 0;
    }

    @Override
    public int getActionIndex() {
        return actionIndex;
    }

    @Override
    public void setPointerCount(int pointerCount) {
        this.pointerCount = pointerCount;
    }

    @Override
    public boolean touchDownEvent(int eventX, int eventY, long eventTime, boolean isNewFinger) {
        // Start tracking from this point
        lastX = eventX;
        lastY = eventY;
        // Convert pixel coordinates to normalized 0-1 range (approximate)
        float normalizedX = eventX / 1000.0f; // Rough approximation
        float normalizedY = eventY / 1000.0f;
        touchpadHandler.startTracking(normalizedX, normalizedY);
        tracking = true;
        cancelled = false;
        return true;
    }

    @Override
    public void touchUpEvent(int eventX, int eventY, long eventTime) {
        if (tracking) {
            touchpadHandler.stopTracking();
            // Send stop signal for right stick only
            conn.sendControllerInput((short)getActionIndex(), (short)1, 0,
                    (byte)0, (byte)0,
                    (short)0, (short)0,  // Left stick untouched
                    (short)0, (short)0); // Right stick stopped
            tracking = false;
        }
    }

    @Override
    public boolean touchMoveEvent(int eventX, int eventY, long eventTime) {
        if (tracking) {
            // Throttle updates to reduce stuttering and conflicts with left stick
            if (eventTime - lastUpdateTime < 20) { // Limit to ~50 FPS to avoid overwhelming system
                return true;
            }
            lastUpdateTime = eventTime;
            
            // Update touchpad position and get delta
            float normalizedX = eventX / 1000.0f; // Rough approximation
            float normalizedY = eventY / 1000.0f;
            
            // Get movement delta and convert to stick values
            float[] delta = touchpadHandler.getDelta(normalizedX, normalizedY);
            float[] stickValues = touchpadHandler.convertTouchpadDeltaToStick(delta[0], delta[1]);
            
            // Convert to controller stick values
            short stickX = (short)(stickValues[0] * 0x7FFE);
            short stickY = (short)(-stickValues[1] * 0x7FFE); // Invert Y to match controller
            
            // Send only right stick values with throttling for smooth operation
            conn.sendControllerInput((short)getActionIndex(), (short)1, 0,
                    (byte)0, (byte)0,
                    (short)0, (short)0,  // Left stick zeroed
                    stickX, stickY);  // Right stick only
            return true;
        }
        return false;
    }

    @Override
    public void cancelTouch() {
        if (tracking) {
            touchpadHandler.stopTracking();
            // Send stop signal for right stick only  
            conn.sendControllerInput((short)getActionIndex(), (short)1, 0,
                    (byte)0, (byte)0,
                    (short)0, (short)0,  // Left stick untouched
                    (short)0, (short)0); // Right stick stopped
            tracking = false;
            cancelled = true;
        }
    }

    @Override
    public boolean isCancelled() {
        return cancelled;
    }

    @Override
    public boolean isConfirmedMove() {
        return tracking;
    }
}
