package com.limelight.binding.audio;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class QueueOverloadMonitorTest {
    @Test
    public void concentratedDropsTriggerFallback() {
        QueueOverloadMonitor monitor = new QueueOverloadMonitor();
        assertFalse(monitor.recordDrops(QueueOverloadMonitor.DROP_THRESHOLD - 1, 1));
        assertTrue(monitor.recordDrops(1, 2));
    }

    @Test
    public void isolatedDropsDoNotAccumulateAcrossWindows() {
        QueueOverloadMonitor monitor = new QueueOverloadMonitor();
        assertFalse(monitor.recordDrops(6, 1));
        assertFalse(monitor.recordDrops(6, QueueOverloadMonitor.WINDOW_NANOS + 1));
    }

    @Test
    public void resetClearsCurrentWindow() {
        QueueOverloadMonitor monitor = new QueueOverloadMonitor();
        monitor.recordDrops(QueueOverloadMonitor.DROP_THRESHOLD - 1, 1);
        monitor.reset();
        assertFalse(monitor.recordDrops(1, 2));
    }
}
