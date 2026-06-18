package com.limelight.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.os.Build;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;

import com.limelight.binding.input.virtual_controller.VirtualController;
import com.limelight.nvstream.input.ControllerPacket;

import java.util.HashMap;
import java.util.Map;

/**
 * Foldable panel showing L1/L2/R1/R2 buttons rendered on the right half of a Z Fold screen.
 * Integrates with VirtualController.ControllerInputContext to send bumper/trigger state.
 */
public class FoldableTriggerView extends View {

    public interface InputListener {
        void onStateChanged(int inputMapDelta, byte leftTrigger, byte rightTrigger);
    }

    private static final int BTN_L1 = 0;
    private static final int BTN_L2 = 1;
    private static final int BTN_R1 = 2;
    private static final int BTN_R2 = 3;
    private static final int BTN_COUNT = 4;

    private static final String[] LABELS = {"L1", "L2", "R1", "R2"};
    // Map each button to its ControllerPacket flag (L2/R2 use analog triggers, not flags)
    private static final int[] INPUT_FLAGS = {
        ControllerPacket.LB_FLAG,
        0, // L2 is analog
        ControllerPacket.RB_FLAG,
        0  // R2 is analog
    };

    private final RectF[] btnRects = new RectF[BTN_COUNT];
    private final boolean[] pressed = new boolean[BTN_COUNT];
    // Track which pointer is holding each button
    private final Map<Integer, Integer> pointerToBtn = new HashMap<>();

    private final Paint bgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pressedPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint borderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private InputListener inputListener;
    private Vibrator vibrator;

    public FoldableTriggerView(Context context) {
        super(context);
        init(context);
    }

    public FoldableTriggerView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init(context);
    }

    private void init(Context context) {
        vibrator = (Vibrator) context.getSystemService(Context.VIBRATOR_SERVICE);

        bgPaint.setColor(Color.argb(180, 30, 30, 40));
        bgPaint.setStyle(Paint.Style.FILL);

        pressedPaint.setColor(Color.argb(220, 70, 130, 200));
        pressedPaint.setStyle(Paint.Style.FILL);

        borderPaint.setColor(Color.argb(200, 120, 160, 220));
        borderPaint.setStyle(Paint.Style.STROKE);
        borderPaint.setStrokeWidth(4f);

        textPaint.setColor(Color.WHITE);
        textPaint.setStyle(Paint.Style.FILL);
        textPaint.setTextAlign(Paint.Align.CENTER);

        for (int i = 0; i < BTN_COUNT; i++) {
            btnRects[i] = new RectF();
        }
    }

    public void setInputListener(InputListener listener) {
        this.inputListener = listener;
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldW, int oldH) {
        super.onSizeChanged(w, h, oldW, oldH);
        layoutButtons(w, h);
    }

    private void layoutButtons(int w, int h) {
        // Left column: L1 (top), L2 (bottom)
        // Right column: R1 (top), R2 (bottom)
        float pad = w * 0.06f;
        float colW = (w - pad * 3) / 2f;
        float rowH = (h - pad * 3) / 2f;

        textPaint.setTextSize(Math.min(colW, rowH) * 0.3f);

        // L1
        btnRects[BTN_L1].set(pad, pad, pad + colW, pad + rowH);
        // L2
        btnRects[BTN_L2].set(pad, pad * 2 + rowH, pad + colW, pad * 2 + rowH * 2);
        // R1
        btnRects[BTN_R1].set(pad * 2 + colW, pad, pad * 2 + colW * 2, pad + rowH);
        // R2
        btnRects[BTN_R2].set(pad * 2 + colW, pad * 2 + rowH, pad * 2 + colW * 2, pad * 2 + rowH * 2);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        for (int i = 0; i < BTN_COUNT; i++) {
            canvas.drawRoundRect(btnRects[i], 20f, 20f, pressed[i] ? pressedPaint : bgPaint);
            canvas.drawRoundRect(btnRects[i], 20f, 20f, borderPaint);
            canvas.drawText(
                LABELS[i],
                btnRects[i].centerX(),
                btnRects[i].centerY() - (textPaint.descent() + textPaint.ascent()) / 2f,
                textPaint
            );
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        int action = event.getActionMasked();
        int pointerIdx = event.getActionIndex();
        int pointerId = event.getPointerId(pointerIdx);

        switch (action) {
            case MotionEvent.ACTION_DOWN:
            case MotionEvent.ACTION_POINTER_DOWN: {
                float x = event.getX(pointerIdx);
                float y = event.getY(pointerIdx);
                int btn = hitTest(x, y);
                if (btn >= 0) {
                    pointerToBtn.put(pointerId, btn);
                    setPressed(btn, true);
                }
                break;
            }
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_POINTER_UP:
            case MotionEvent.ACTION_CANCEL: {
                Integer btn = pointerToBtn.remove(pointerId);
                if (btn != null) {
                    setPressed(btn, false);
                }
                break;
            }
        }
        return true;
    }

    private int hitTest(float x, float y) {
        for (int i = 0; i < BTN_COUNT; i++) {
            if (btnRects[i].contains(x, y)) return i;
        }
        return -1;
    }

    private void setPressed(int btn, boolean down) {
        if (pressed[btn] == down) return;
        pressed[btn] = down;
        invalidate();

        if (down) {
            haptic();
        }

        notifyListener();
    }

    private void haptic() {
        if (vibrator == null) return;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator.vibrate(VibrationEffect.createOneShot(30, VibrationEffect.DEFAULT_AMPLITUDE));
        } else {
            vibrator.vibrate(30);
        }
    }

    private void notifyListener() {
        if (inputListener == null) return;

        int inputMapDelta = 0;
        for (int i = 0; i < BTN_COUNT; i++) {
            if (pressed[i] && INPUT_FLAGS[i] != 0) {
                inputMapDelta |= INPUT_FLAGS[i];
            }
        }

        // L2 = index 1, R2 = index 3 — use full analog value when pressed
        byte leftTrigger  = pressed[BTN_L2] ? (byte) 0xFF : 0;
        byte rightTrigger = pressed[BTN_R2] ? (byte) 0xFF : 0;

        inputListener.onStateChanged(inputMapDelta, leftTrigger, rightTrigger);
    }

    /** Reset all buttons (e.g. when panel hides so we don't leave stuck inputs). */
    public void releaseAll() {
        boolean changed = false;
        for (int i = 0; i < BTN_COUNT; i++) {
            if (pressed[i]) { pressed[i] = false; changed = true; }
        }
        pointerToBtn.clear();
        if (changed) {
            invalidate();
            notifyListener();
        }
    }
}
