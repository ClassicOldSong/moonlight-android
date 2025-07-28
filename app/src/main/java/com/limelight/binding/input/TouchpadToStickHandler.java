package com.limelight.binding.input;

/**
 * Handles converting touchpad input to analog stick movements
 */
public class TouchpadToStickHandler {
    private static final float TOUCHPAD_SENSITIVITY = 2.5f; // Higher sensitivity for responsive feel
    private static final float MAX_STICK_MAGNITUDE = 0x7FFE;
    private static final float SMOOTHING_FACTOR = 0.2f; // Some smoothing for stability
    private static final float DEADZONE = 0.05f; // Small deadzone to prevent drift
    private static final float SCALE_FACTOR = 0.01f; // Fine control scaling
    private static final float MAX_ACCUMULATION = 1.0f; // Maximum accumulated stick value
    
    private float accumulatedX = 0; // Accumulated stick position
    private float accumulatedY = 0;
    private float lastX = 0;
    private float lastY = 0;
    private boolean isTracking = false;

    /**
     * Converts a touchpad delta to stick movement with accumulation
     * Only affects right stick (camera) - never interferes with left stick (movement)
     */
    public float[] convertTouchpadDeltaToStick(float deltaX, float deltaY) {
        // Apply initial scaling
        float x = deltaX * TOUCHPAD_SENSITIVITY * SCALE_FACTOR;
        float y = deltaY * TOUCHPAD_SENSITIVITY * SCALE_FACTOR;
        
        // Accumulate movement
        accumulatedX += x;
        accumulatedY += y;
        
        // Apply deadzone to accumulated values
        float magnitude = (float)Math.sqrt(accumulatedX * accumulatedX + accumulatedY * accumulatedY);
        if (magnitude < DEADZONE) {
            accumulatedX = 0;
            accumulatedY = 0;
        }
        
        // Clamp accumulated values to maximum range
        accumulatedX = Math.max(-MAX_ACCUMULATION, Math.min(MAX_ACCUMULATION, accumulatedX));
        accumulatedY = Math.max(-MAX_ACCUMULATION, Math.min(MAX_ACCUMULATION, accumulatedY));
        
        // Apply decay for natural return to center when not moving
        if (Math.abs(deltaX) < 0.001f && Math.abs(deltaY) < 0.001f) {
            // Only apply decay when there's no movement
            accumulatedX *= (1.0f - SMOOTHING_FACTOR);
            accumulatedY *= (1.0f - SMOOTHING_FACTOR);
        }
        
        return new float[]{accumulatedX, accumulatedY};
    }

    public void startTracking(float x, float y) {
        lastX = x;
        lastY = y;
        accumulatedX = 0;
        accumulatedY = 0;
        isTracking = true;
    }

    public void stopTracking() {
        isTracking = false;
        lastX = 0;
        lastY = 0;
        accumulatedX = 0;
        accumulatedY = 0;
    }

    public boolean isTracking() {
        return isTracking;
    }

    public float[] getDelta(float currentX, float currentY) {
        if (!isTracking) {
            return new float[]{0, 0};
        }

        // Calculate raw deltas
        float deltaX = currentX - lastX;
        float deltaY = currentY - lastY;

        // Update last positions
        lastX = currentX;
        lastY = currentY;

        return new float[]{deltaX, deltaY};
    }

    public float[] getCurrentStickPosition() {
        return new float[]{accumulatedX, accumulatedY};
    }
}
