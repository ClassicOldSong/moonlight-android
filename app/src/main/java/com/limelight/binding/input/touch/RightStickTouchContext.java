package com.limelight.binding.input.touch;

import android.os.Handler;
import android.os.Looper;
import com.limelight.binding.input.ControllerHandler;

/**
 * 處理「右搖桿向量觸控板」模式的核心邏輯。
 * <p>
 * 功能實現：
 * 1. 改用 float 介面以支援亞像素級精準度。
 * 2. 實作圓形限制 (Circular Clamping)，避免角落輸出超界。
 * 3. 實作徑向死區補償 (Radial Anti-Deadzone)，提升微瞄指向性。
 * 4. 限制為單點觸控 (Single Touch)，避免多指衝突。
 */
public class RightStickTouchContext implements TouchContext {
    private final ControllerHandler controllerHandler;
    private final int pointerId;
    private final float density;

    private final float sensX;
    private final float sensY;

    // 死區補償值
    private final short ANTI_DEADZONE_OFFSET;

    private final Handler handler = new Handler(Looper.getMainLooper());

    private float lastX, lastY;

    // [設定] 時間死區：超過 50ms 沒更新視為停止
    private static final int STOP_THRESHOLD_MS = 50;

    // [設定] 雙擊判定
    private static final int DOUBLE_TAP_TIMEOUT_MS = 200;
    private static final float DOUBLE_TAP_SLOP_PX = 100.0f;

    // [設定] 基礎倍率
    private static final float BASE_MULTIPLIER = 32767.0f * 0.2f; // 速率:1dp/幀 = 20%搖桿值

    // 雙擊偵測狀態
    private long lastTouchDownTime = 0;
    private float lastTouchDownX = 0;
    private float lastTouchDownY = 0;
    private boolean isR3Active = false;

    private final Runnable resetTask = new Runnable() {
        @Override
        public void run() {
            sendStickOutput((short) 0, (short) 0);
        }
    };

    /**
     * @param controllerHandler   控制器處理器
     * @param pointerId           觸控點 ID
     * @param sensX               X 軸靈敏度
     * @param sensY               Y 軸靈敏度
     * @param antiDeadzoneFactor  反死區補償係數 (0.0 ~ 0.5)
     * @param density             螢幕密度
     */
    public RightStickTouchContext(ControllerHandler controllerHandler, int pointerId, float sensX, float sensY, float antiDeadzoneFactor, float density) {
        this.controllerHandler = controllerHandler;
        this.pointerId = pointerId;
        this.density = density;

        this.sensX = sensX;
        this.sensY = sensY;

        // [核心邏輯] 計算反死區補償值
        // 公式：32767 * 設定百分比
        this.ANTI_DEADZONE_OFFSET = (short) (32767 * antiDeadzoneFactor);
    }

    @Override
    public int getActionIndex() { return pointerId; }

    @Override
    public void setPointerCount(int pointerCount) {}

    // [修正] 參數改為 float
    @Override
    public boolean touchDownEvent(float eventX, float eventY, long eventTime, boolean isNewFinger) {
        // 單點觸控限制
        if (pointerId != 0) {
            return true;
        }

        boolean isDoubleTap = false;
        if (eventTime - lastTouchDownTime < DOUBLE_TAP_TIMEOUT_MS) {
            float deltaX = eventX - lastTouchDownX;
            float deltaY = eventY - lastTouchDownY;
            if ((deltaX * deltaX + deltaY * deltaY) < (DOUBLE_TAP_SLOP_PX * DOUBLE_TAP_SLOP_PX)) {
                isDoubleTap = true;
            }
        }

        if (isDoubleTap) {
            isR3Active = true;
            sendStickOutput((short) 0, (short) 0);
        } else {
            isR3Active = false;
        }

        lastTouchDownTime = eventTime;
        lastTouchDownX = eventX;
        lastTouchDownY = eventY;

        lastX = eventX;
        lastY = eventY;
        return true;
    }

    // [修正] 參數改為 float
    @Override
    public boolean touchMoveEvent(float eventX, float eventY, long eventTime) {
        // 單點觸控限制
        if (pointerId != 0) {
            return true;
        }

        float deltaX = eventX - lastX;
        float deltaY = eventY - lastY;

        lastX = eventX;
        lastY = eventY;

        // --- 核心運算 ---
        // 先計算純粹由位移產生的向量 (尚未包含死區補償與 Clamp)
        float outputX = (deltaX / density) * sensX;
        float outputY = -(deltaY / density) * sensY;

        // --- 向量運算 (Radial Calculation) ---
        // 計算向量長度 (Magnitude)
        double rawLength = Math.sqrt(outputX * outputX + outputY * outputY);

        // 只有當長度 > 0 時才進行縮放，避免除以零 (NaN)
        if (rawLength > 0) {
            // 1. 應用基礎倍率
            double targetLength = rawLength * BASE_MULTIPLIER;

            // 2. 加上徑向死區補償 (Radial Anti-Deadzone)
            //    這會讓向量長度至少為 ANTI_DEADZONE_OFFSET
            targetLength += ANTI_DEADZONE_OFFSET;

            // 3. 圓形限制 (Circular Clamping)
            //    確保向量長度不超過物理極限 (32767)
            if (targetLength > Short.MAX_VALUE) {
                targetLength = Short.MAX_VALUE;
            }

            // 4. 計算最終縮放比例並應用回 X/Y
            //    Scale = 最終目標長度 / 原始長度
            double scale = targetLength / rawLength;
            outputX *= scale;
            outputY *= scale;
        } else {
            // 如果 rawLength 為 0 (手指完全靜止)，直接歸零
            outputX = 0;
            outputY = 0;
        }

        // 雖然已有 Circular Clamping，但保留 min/max 以防浮點數誤差導致溢出
        short stickX = (short) Math.max(Short.MIN_VALUE, Math.min(Short.MAX_VALUE, outputX));
        short stickY = (short) Math.max(Short.MIN_VALUE, Math.min(Short.MAX_VALUE, outputY));

        sendStickOutput(stickX, stickY);

        handler.removeCallbacks(resetTask);
        handler.postDelayed(resetTask, STOP_THRESHOLD_MS);

        return true;
    }

    // [修正] 參數改為 float
    @Override
    public void touchUpEvent(float eventX, float eventY, long eventTime) {
        // 單點觸控限制
        if (pointerId != 0) {
            return;
        }

        isR3Active = false;
        handler.removeCallbacks(resetTask);
        sendStickOutput((short) 0, (short) 0);
    }

    @Override
    public void cancelTouch() {
        // 單點觸控限制
        if (pointerId != 0) {
            return;
        }

        isR3Active = false;
        handler.removeCallbacks(resetTask);
        sendStickOutput((short) 0, (short) 0);
    }

    @Override
    public boolean isCancelled() { return false; }

    private void sendStickOutput(short rx, short ry) {
        controllerHandler.reportTouchAimState(rx, ry, isR3Active);
    }
}