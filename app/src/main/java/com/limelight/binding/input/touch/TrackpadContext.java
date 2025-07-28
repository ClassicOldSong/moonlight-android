package com.limelight.binding.input.touch;

import android.os.Handler;
import android.os.Looper;

import com.limelight.LimeLog;
import com.limelight.nvstream.NvConnection;
import com.limelight.nvstream.input.MouseButtonPacket;

public class TrackpadContext implements TouchContext {
    private double pendingDeltaX = 0;
    private double pendingDeltaY = 0;
    private int lastTouchX = 0;
    private int lastTouchY = 0;
    private int originalTouchX = 0;
    private int originalTouchY = 0;
    private long originalTouchTime = 0;
    private boolean cancelled;
    private boolean confirmedMove;
    private boolean confirmedDrag;
    private boolean confirmedScroll;
    private double distanceMoved;
    private int pointerCount;
    private int maxPointerCountInGesture;
    private boolean isClickPending;
    private boolean isDblClickPending;
    private boolean isFlicking;
    private double velocityX = 0.0;
    private double velocityY = 0.0;
    private long lastMoveTime;
    private boolean isScrollTransitioning = false;

    private final NvConnection conn;
    private final int actionIndex;
    private final Handler handler;

    private boolean swapAxis = false;
    private float sensitivityX = 1;
    private float sensitivityY = 1;

    private static final int TAP_MOVEMENT_THRESHOLD = 30;
    private static final int TAP_TIME_THRESHOLD = 230;
    private static final int CLICK_RELEASE_DELAY = TAP_TIME_THRESHOLD;
    private static final int SCROLL_SPEED_FACTOR_X = 2;
    private static final int SCROLL_SPEED_FACTOR_Y = 3;
    private static final double ACCELERATION_THRESHOLD = 8.0;
    private static final double FLICK_FRICTION = 0.93;
    // Unit: pixels/ms.
    private static final double FLICK_THRESHOLD = 0.8;
    private static final int MOMENTUM_FRAME_INTERVAL_MS = 10;
    private static final int FLICK_VELOCITY_DECAY_TIMEOUT_MS = 50;
    private static final int SCROLL_TRANSITION_TIMEOUT_MS = 200;

    public TrackpadContext(NvConnection conn, int actionIndex) {
        this.conn = conn;
        this.actionIndex = actionIndex;
        this.handler = new Handler(Looper.getMainLooper());
    }

    public TrackpadContext(NvConnection conn, int actionIndex, boolean swapAxis, int sensitivityX, int sensitivityY) {
        this(conn, actionIndex);
        this.swapAxis = swapAxis;
        this.sensitivityX = (float) sensitivityX / 100;
        this.sensitivityY = (float) sensitivityY / 100;
    }

    private final Runnable scrollTransitionRunnable = new Runnable() {
        @Override
        public void run() {
            isScrollTransitioning = false;
        }
    };

    private final Runnable momentumRunnable = new Runnable() {
        @Override
        public void run() {
            if (!isFlicking) {
                return;
            }

            pendingDeltaX += velocityX * MOMENTUM_FRAME_INTERVAL_MS;
            pendingDeltaY += velocityY * MOMENTUM_FRAME_INTERVAL_MS;

            short intDeltaX = (short) pendingDeltaX;
            short intDeltaY = (short) pendingDeltaY;

            if (intDeltaX != 0 || intDeltaY != 0) {
                conn.sendMouseMove(intDeltaX, intDeltaY);
                pendingDeltaX -= intDeltaX;
                pendingDeltaY -= intDeltaY;
            }

            velocityX *= FLICK_FRICTION;
            velocityY *= FLICK_FRICTION;

            if (Math.sqrt(velocityX * velocityX + velocityY * velocityY) * MOMENTUM_FRAME_INTERVAL_MS < 0.5) {
                isFlicking = false;
                if (confirmedDrag) {
                    conn.sendMouseButtonUp(getMouseButtonIndex());
                    confirmedDrag = false;
                }
            }

            if (isFlicking) {
                handler.postDelayed(this, MOMENTUM_FRAME_INTERVAL_MS);
            }
        }
    };

    private final Runnable scrollMomentumRunnable = new Runnable() {
        @Override
        public void run() {
            if (!isFlicking) {
                return;
            }

            double frameVelocityX = velocityX * MOMENTUM_FRAME_INTERVAL_MS;
            double frameVelocityY = velocityY * MOMENTUM_FRAME_INTERVAL_MS;

            if (Math.abs(frameVelocityX) > Math.abs(frameVelocityY)) {
                conn.sendMouseHighResHScroll((short)(-frameVelocityX * SCROLL_SPEED_FACTOR_X));
                if (Math.abs(frameVelocityY) * 1.05 > Math.abs(frameVelocityX)) {
                    conn.sendMouseHighResScroll((short)(frameVelocityY * SCROLL_SPEED_FACTOR_Y));
                }
            } else {
                conn.sendMouseHighResScroll((short)(frameVelocityY * SCROLL_SPEED_FACTOR_Y));
                if (Math.abs(frameVelocityX) * 1.05 >= Math.abs(frameVelocityY)) {
                    conn.sendMouseHighResHScroll((short)(-frameVelocityX * SCROLL_SPEED_FACTOR_X));
                }
            }

            velocityX *= FLICK_FRICTION;
            velocityY *= FLICK_FRICTION;

            if (Math.sqrt(velocityX * velocityX + velocityY * velocityY) * MOMENTUM_FRAME_INTERVAL_MS < 0.5) {
                isFlicking = false;
            }

            if (isFlicking) {
                handler.postDelayed(this, MOMENTUM_FRAME_INTERVAL_MS);
            }
        }
    };

    @Override
    public int getActionIndex() {
        return actionIndex;
    }

    private boolean isWithinTapBounds(int touchX, int touchY) {
        int xDelta = Math.abs(touchX - originalTouchX);
        int yDelta = Math.abs(touchY - originalTouchY);
        return xDelta <= TAP_MOVEMENT_THRESHOLD && yDelta <= TAP_MOVEMENT_THRESHOLD;
    }

    private boolean isTap(long eventTime) {
        if (confirmedDrag || confirmedMove || confirmedScroll) {
            return false;
        }

        if (actionIndex + 1 != maxPointerCountInGesture) {
            return false;
        }

        long timeDelta = eventTime - originalTouchTime;
        return isWithinTapBounds(lastTouchX, lastTouchY) && timeDelta <= TAP_TIME_THRESHOLD;
    }

    private byte getMouseButtonIndex() {
        if (pointerCount == 2) {
            return MouseButtonPacket.BUTTON_RIGHT;
        } else {
            return MouseButtonPacket.BUTTON_LEFT;
        }
    }

    @Override
    public boolean touchDownEvent(int eventX, int eventY, long eventTime, boolean isNewFinger) {
        if (isFlicking) {
            isFlicking = false;
            handler.removeCallbacksAndMessages(null);
        }

        originalTouchX = lastTouchX = eventX;
        originalTouchY = lastTouchY = eventY;

        pendingDeltaX = pendingDeltaY = 0;

        if (isNewFinger) {
            // A completely new gesture has started.
            // Cancel any pending scroll->move transition.
            handler.removeCallbacks(scrollTransitionRunnable);
            isScrollTransitioning = false;
            maxPointerCountInGesture = pointerCount;
            originalTouchTime = eventTime;
            cancelled = confirmedMove = confirmedScroll = false;
            distanceMoved = 0;
            velocityX = 0;
            velocityY = 0;
            lastMoveTime = eventTime;
            if (isClickPending) {
                isClickPending = false;
                isDblClickPending = true;
                confirmedDrag = true;
            }
        } else {
            // Second finger released, should trigger right click immediately
            if (pointerCount == 1 && !confirmedMove) {
                conn.sendMouseButtonDown(MouseButtonPacket.BUTTON_RIGHT);
                conn.sendMouseButtonUp(MouseButtonPacket.BUTTON_RIGHT);
                isClickPending = false;
                isDblClickPending = false;
                confirmedDrag = false;
            }
        }

        originalTouchX = lastTouchX = eventX;
        originalTouchY = lastTouchY = eventY;

        pendingDeltaX = pendingDeltaY = 0;

        return true;
    }

    @Override
    public void touchUpEvent(int eventX, int eventY, long eventTime) {
        if (cancelled) {
            return;
        }

        // Decay velocity based on time since last move event to avoid
        // flicks when the user pauses before lifting their finger.
        long timeSinceLastMove = eventTime - lastMoveTime;
        if (timeSinceLastMove > 0) {
            double decay = Math.max(0.0, 1.0 - (double)timeSinceLastMove / FLICK_VELOCITY_DECAY_TIMEOUT_MS);
            velocityX *= decay;
            velocityY *= decay;
        }

        byte buttonIndex = getMouseButtonIndex();

        if (isDblClickPending) {
            handler.removeCallbacksAndMessages(null);
            conn.sendMouseButtonUp(buttonIndex);
            conn.sendMouseButtonDown(buttonIndex);
            conn.sendMouseButtonUp(buttonIndex);
            isClickPending = false;
            confirmedDrag = false;
        }
        else if (confirmedDrag) {
            handler.removeCallbacksAndMessages(null);

            double speed = Math.sqrt(velocityX * velocityX + velocityY * velocityY);
            if (speed > FLICK_THRESHOLD) {
                isFlicking = true;
                handler.post(momentumRunnable);
            } else {
                conn.sendMouseButtonUp(buttonIndex);
                confirmedDrag = false;
            }
        }
        else if (isTap(eventTime)) {
            conn.sendMouseButtonDown(buttonIndex);
            isClickPending = true;

            handler.removeCallbacksAndMessages(null);
            handler.postDelayed(() -> {
                if (isClickPending) {
                    conn.sendMouseButtonUp(buttonIndex);
                    isClickPending = false;
                }
                isDblClickPending = false;
            }, CLICK_RELEASE_DELAY);
        }
        else if (confirmedMove) {
            // This was a move/scroll that wasn't a drag or tap. Let's see if we should flick.
            double speed = Math.sqrt(velocityX * velocityX + velocityY * velocityY);
            if (speed > FLICK_THRESHOLD) {
                isFlicking = true;
                if (confirmedScroll) {
                    handler.post(scrollMomentumRunnable);
                } else {
                    // A 1-finger move can flick. A >1 finger move that wasn't a scroll shouldn't cause a mouse move flick.
                    if (maxPointerCountInGesture == 1) {
                        handler.post(momentumRunnable);
                    }
                }
            }
        }
    }

    @Override
    public boolean touchMoveEvent(int eventX, int eventY, long eventTime) {
        if (cancelled) {
            return true;
        }

        if (eventX != lastTouchX || eventY != lastTouchY) {
            long deltaTime = eventTime - lastMoveTime;

            checkForConfirmedMove(eventX, eventY);

            if (isDblClickPending) {
                isDblClickPending = false;
                confirmedDrag = true;
            }

            int rawDeltaX = eventX - lastTouchX;
            int rawDeltaY = eventY - lastTouchY;
            int absDeltaX = Math.abs(rawDeltaX);
            int absDeltaY = Math.abs(rawDeltaY);

            double magnitude = Math.sqrt(rawDeltaX * rawDeltaX + rawDeltaY * rawDeltaY);
            double precisionMultiplier = 1.0; // Disable acceleration for consistent movement

            float deltaX, deltaY;
            if (swapAxis) {
                deltaY = rawDeltaX;
                deltaX = rawDeltaY;
            } else {
                deltaX = rawDeltaX;
                deltaY = rawDeltaY;
            }

            deltaX *= precisionMultiplier;
            deltaY *= precisionMultiplier;

            deltaX *= sensitivityX;
            deltaY *= sensitivityY;

            // Update velocity for flicking
            if (deltaTime > 0 && (confirmedMove || confirmedDrag)) {
                double currentVelocityX = deltaX / deltaTime;
                double currentVelocityY = deltaY / deltaTime;

                if (velocityX == 0 && velocityY == 0) {
                    // First measurement
                    velocityX = currentVelocityX;
                    velocityY = currentVelocityY;
                } else {
                    // Direct velocity assignment for immediate response (no smoothing)
                    velocityX = currentVelocityX;
                    velocityY = currentVelocityY;
                }
            }

            lastMoveTime = eventTime;

            pendingDeltaX += deltaX;
            pendingDeltaY += deltaY;

            lastTouchX = eventX;
            lastTouchY = eventY;

            short sendDeltaX = (short)pendingDeltaX;
            short sendDeltaY = (short)pendingDeltaY;

            if (pointerCount == 1) {
                // Don't move the mouse immediately after a scroll
                if (!isScrollTransitioning) {
                    if (sendDeltaX != 0 || sendDeltaY != 0) {
                        conn.sendMouseMove(sendDeltaX, sendDeltaY);
                    }
                }
            } else {
                if (actionIndex == 1) {
                    if (confirmedDrag) {
                        if (sendDeltaX != 0 || sendDeltaY != 0) {
                            conn.sendMouseMove(sendDeltaX, sendDeltaY);
                        }
                    } else if (pointerCount == 2) {
                        checkForConfirmedScroll();
                        if (confirmedScroll) {
                            if (absDeltaX > absDeltaY) {
                                conn.sendMouseHighResHScroll((short)(-sendDeltaX * SCROLL_SPEED_FACTOR_X));
                                if (absDeltaY * 1.05 > absDeltaX) {
                                    conn.sendMouseHighResScroll((short)(sendDeltaY * SCROLL_SPEED_FACTOR_Y));
                                }
                            } else {
                                conn.sendMouseHighResScroll((short)(sendDeltaY * SCROLL_SPEED_FACTOR_Y));
                                if (absDeltaX * 1.05 >= absDeltaY) {
                                    conn.sendMouseHighResHScroll((short)(-sendDeltaX * SCROLL_SPEED_FACTOR_X));
                                }
                            }
                        }
                    }
                }
            }

            pendingDeltaX -= sendDeltaX;
            pendingDeltaY -= sendDeltaY;
        }

        return true;
    }

    @Override
    public void cancelTouch() {
        cancelled = true;

        if (isFlicking) {
            isFlicking = false;
            handler.removeCallbacksAndMessages(null);
        }

        if (confirmedDrag) {
            conn.sendMouseButtonUp(getMouseButtonIndex());
        }
    }

    @Override
    public boolean isCancelled() {
        return cancelled;
    }

    @Override
    public void setPointerCount(int pointerCount) {
        if (this.pointerCount == 2 && pointerCount == 1) {
            // We just finished a 2-finger scroll.
            // Block mouse movement for a short period to avoid stray movement
            // from the remaining finger before it's lifted.
            isScrollTransitioning = true;
            handler.postDelayed(scrollTransitionRunnable, SCROLL_TRANSITION_TIMEOUT_MS);
        } else if (this.pointerCount == 1 && pointerCount == 2) {
            // User put a second finger down. If we were in a scroll transition, cancel it.
            handler.removeCallbacks(scrollTransitionRunnable);
            isScrollTransitioning = false;
        }

        if (pointerCount < this.pointerCount && confirmedDrag && !isFlicking) {
            conn.sendMouseButtonUp(getMouseButtonIndex());
            confirmedDrag = false;
            confirmedMove = false;
            confirmedScroll = false;
            isClickPending = false;
            isDblClickPending = false;
        }

        this.pointerCount = pointerCount;

        if (pointerCount > maxPointerCountInGesture) {
            maxPointerCountInGesture = pointerCount;
        }
    }

    private void checkForConfirmedMove(int eventX, int eventY) {
        // If we've already confirmed something, get out now
        if (confirmedMove || confirmedDrag) {
            return;
        }

        // If it leaves the tap bounds before the drag time expires, it's a move.
        if (!isWithinTapBounds(eventX, eventY)) {
            confirmedMove = true;
            return;
        }

        // Check if we've exceeded the maximum distance moved
        distanceMoved += Math.sqrt(Math.pow(eventX - lastTouchX, 2) + Math.pow(eventY - lastTouchY, 2));
        if (distanceMoved >= TAP_MOVEMENT_THRESHOLD) {
            confirmedMove = true;
        }
    }

    private void checkForConfirmedScroll() {
        confirmedScroll = (actionIndex == 1 && pointerCount == 2 && confirmedMove);
    }

    @Override
    public boolean isConfirmedMove() {
        return confirmedMove;
    }
}
