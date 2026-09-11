package com.limelight.binding.audio;

/**
 * Detects sustained queue pressure without allowing isolated drops to accumulate forever.
 */
final class QueueOverloadMonitor {
    static final long WINDOW_NANOS = 10_000_000_000L;
    static final int DROP_THRESHOLD = 12;

    private long windowStartNanos;
    private int windowDrops;

    boolean recordDrops(int droppedFrames, long nowNanos) {
        if (droppedFrames <= 0) {
            return false;
        }
        if (windowStartNanos == 0 || nowNanos - windowStartNanos >= WINDOW_NANOS) {
            windowStartNanos = nowNanos;
            windowDrops = 0;
        }
        windowDrops += droppedFrames;
        return windowDrops >= DROP_THRESHOLD;
    }

    int getWindowDrops() {
        return windowDrops;
    }

    void reset() {
        windowStartNanos = 0;
        windowDrops = 0;
    }
}
