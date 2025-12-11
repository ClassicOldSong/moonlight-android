package com.limelight.binding.input.touch;

public interface TouchContext {
    int getActionIndex();
    void setPointerCount(int pointerCount);
    boolean touchDownEvent(float eventX, float eventY, long eventTime, boolean isNewFinger);
    boolean touchMoveEvent(float eventX, float eventY, long eventTime);
    void touchUpEvent(float eventX, float eventY, long eventTime);
    void cancelTouch();
    boolean isCancelled();
}
