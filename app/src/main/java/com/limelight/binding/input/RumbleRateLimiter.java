package com.limelight.binding.input;

import java.util.HashMap;
import java.util.Map;
final class RumbleRateLimiter {
    interface Clock {
        long uptimeMillis();
    }

    interface Scheduler {
        void postDelayed(Runnable runnable, long delayMs);
        void removeCallbacks(Runnable runnable);
    }

    interface Output {
        void rumble(short controllerNumber, short firstMotor, short secondMotor);
    }

    private static class State {
        short firstMotor;
        short secondMotor;
        long lastDispatchTime;
        boolean pending;
        Runnable runnable;
    }

    private final int intervalMs;
    private final Clock clock;
    private final Scheduler scheduler;
    private final Output output;
    private final Map<Short, State> states = new HashMap<>();

    RumbleRateLimiter(int intervalMs, Clock clock, Scheduler scheduler, Output output) {
        this.intervalMs = intervalMs;
        this.clock = clock;
        this.scheduler = scheduler;
        this.output = output;
    }

    synchronized void submit(short controllerNumber, short firstMotor, short secondMotor) {
        if (intervalMs <= 0) {
            output.rumble(controllerNumber, firstMotor, secondMotor);
            return;
        }

        State state = states.computeIfAbsent(controllerNumber, unused -> createState(controllerNumber));
        state.firstMotor = firstMotor;
        state.secondMotor = secondMotor;

        long now = clock.uptimeMillis();
        long delayMs = intervalMs - (now - state.lastDispatchTime);
        if ((firstMotor == 0 && secondMotor == 0) || delayMs <= 0) {
            if (state.pending) {
                scheduler.removeCallbacks(state.runnable);
            }
            dispatch(controllerNumber, state);
        }
        else if (!state.pending) {
            state.pending = true;
            scheduler.postDelayed(state.runnable, delayMs);
        }
    }

    synchronized void cancel(short controllerNumber) {
        State state = states.remove(controllerNumber);
        if (state != null && state.pending) {
            scheduler.removeCallbacks(state.runnable);
        }
    }

    synchronized void cancelAll() {
        for (State state : states.values()) {
            if (state.pending) {
                scheduler.removeCallbacks(state.runnable);
            }
        }
        states.clear();
    }

    private State createState(short controllerNumber) {
        State state = new State();
        state.runnable = () -> {
            synchronized (RumbleRateLimiter.this) {
                if (states.get(controllerNumber) == state) {
                    dispatch(controllerNumber, state);
                }
            }
        };
        return state;
    }

    private void dispatch(short controllerNumber, State state) {
        state.pending = false;
        state.lastDispatchTime = clock.uptimeMillis();
        output.rumble(controllerNumber, state.firstMotor, state.secondMotor);
    }
}
