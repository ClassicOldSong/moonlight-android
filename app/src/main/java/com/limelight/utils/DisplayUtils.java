package com.limelight.utils;

import android.graphics.Point;
import android.os.Build;
import android.view.Display;
import android.annotation.TargetApi; // Optional, for clarity

/**
 * Utility class for display information.
 */
public class DisplayUtils {

    /**
     * Simple data class to hold display information.
     */
    public static class DisplayInfo {
        public final int width;        // Guaranteed landscape width
        public final int height;       // Guaranteed landscape height
        public final float refreshRate;

        public DisplayInfo(int width, int height, float refreshRate) {
            this.width = width;
            this.height = height;
            this.refreshRate = refreshRate;
        }

        @Override
        public String toString() {
            return "DisplayInfo{" +
                    "width=" + width +
                    ", height=" + height +
                    ", refreshRate=" + refreshRate +
                    '}';
        }
    }

    /**
     * Gets the current display's physical dimensions (guaranteed landscape) and refresh rate.
     * Handles different Android API levels.
     *
     * @param display The Display object to query.
     * @return A DisplayInfo object containing width, height, and refresh rate,
     * or null if the display object is null.
     */
    public static DisplayInfo getDisplayInfo(Display display) {
        int oneAxeLength = 0;
        int secondAxeLength = 0;
        float displayRefreshRate = 0f; // Initialize with a default

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            // API 23+ (Marshmallow) - Use Display.Mode
            Display.Mode currentMode = display.getMode();
            if (currentMode != null) {
                oneAxeLength = currentMode.getPhysicalWidth();
                secondAxeLength = currentMode.getPhysicalHeight();
                displayRefreshRate = currentMode.getRefreshRate();
            } else {
                // Fallback if getMode() surprisingly returns null
                getLegacyDisplayInfo(display, sizePoint);
                oneAxeLength = sizePoint.x;
                secondAxeLength = sizePoint.y;
                displayRefreshRate = display.getRefreshRate(); // Still try to get refresh rate
            }
        } else {
            // API < 23 (pre-Marshmallow) - Use deprecated methods
            getLegacyDisplayInfo(display, sizePoint);
            oneAxeLength = sizePoint.x;
            secondAxeLength = sizePoint.y;
            displayRefreshRate = display.getRefreshRate();
        }

        // Ensure landscape dimensions regardless of current orientation
        int physicalWidth = Math.max(oneAxeLength, secondAxeLength);
        int physicalHeight = Math.min(oneAxeLength, secondAxeLength);

        return new DisplayInfo(physicalWidth, physicalHeight, displayRefreshRate);
    }

    // Helper for older APIs (using a static Point to avoid allocations in loops if called often)
    private static final Point sizePoint = new Point();
    private static synchronized void getLegacyDisplayInfo(Display display, Point outSize) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1) {
            // getRealSize() is API 17+
            display.getRealSize(outSize);
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.HONEYCOMB_MR2) {
            // getSize() is API 13+ (slightly less accurate, might exclude nav bar)
            display.getSize(outSize);
        } else {
            // Very old fallback (even less accurate)
            outSize.x = display.getWidth();  // Deprecated in API 13
            outSize.y = display.getHeight(); // Deprecated in API 13
        }
    }
}