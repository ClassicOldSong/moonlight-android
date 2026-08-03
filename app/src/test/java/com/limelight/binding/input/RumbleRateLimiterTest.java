package com.limelight.binding.input;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;

import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

public class RumbleRateLimiterTest {
    private long now;
    private final List<Runnable> scheduled = new ArrayList<>();
    private final List<short[]> outputs = new ArrayList<>();
    private RumbleRateLimiter limiter;

    @Before
    public void setup() {
        now = 1000;
        scheduled.clear();
        outputs.clear();
        limiter = new RumbleRateLimiter(50, () -> now, new RumbleRateLimiter.Scheduler() {
            @Override
            public void postDelayed(Runnable runnable, long delayMs) {
                scheduled.add(runnable);
            }

            @Override
            public void removeCallbacks(Runnable runnable) {
                scheduled.remove(runnable);
            }
        }, (controllerNumber, firstMotor, secondMotor) ->
                outputs.add(new short[] {controllerNumber, firstMotor, secondMotor}));
    }

    @Test
    public void coalescesToLatestPendingValues() {
        limiter.submit((short) 0, (short) 1, (short) 2);
        now += 10;
        limiter.submit((short) 0, (short) 3, (short) 4);
        limiter.submit((short) 0, (short) 5, (short) 6);

        assertEquals(1, outputs.size());
        assertEquals(1, scheduled.size());

        now += 40;
        scheduled.remove(0).run();

        assertEquals(2, outputs.size());
        assertArrayEquals(new short[] {0, 5, 6}, outputs.get(1));
    }

    @Test
    public void sendsStopImmediatelyAndDropsPendingValues() {
        limiter.submit((short) 0, (short) 1, (short) 2);
        now += 10;
        limiter.submit((short) 0, (short) 3, (short) 4);
        limiter.submit((short) 0, (short) 0, (short) 0);

        assertEquals(2, outputs.size());
        assertArrayEquals(new short[] {0, 0, 0}, outputs.get(1));
        assertEquals(0, scheduled.size());
    }

    @Test
    public void cancelPreventsPendingDispatch() {
        limiter.submit((short) 0, (short) 1, (short) 2);
        now += 10;
        limiter.submit((short) 0, (short) 3, (short) 4);

        limiter.cancel((short) 0);

        assertEquals(0, scheduled.size());
        assertEquals(1, outputs.size());
    }

    @Test
    public void disabledLimiterDispatchesEveryUpdate() {
        limiter = new RumbleRateLimiter(0, () -> now, new RumbleRateLimiter.Scheduler() {
            @Override
            public void postDelayed(Runnable runnable, long delayMs) {
                scheduled.add(runnable);
            }

            @Override
            public void removeCallbacks(Runnable runnable) {
                scheduled.remove(runnable);
            }
        }, (controllerNumber, firstMotor, secondMotor) ->
                outputs.add(new short[] {controllerNumber, firstMotor, secondMotor}));

        limiter.submit((short) 0, (short) 1, (short) 2);
        limiter.submit((short) 0, (short) 3, (short) 4);

        assertEquals(2, outputs.size());
        assertEquals(0, scheduled.size());
    }
}
