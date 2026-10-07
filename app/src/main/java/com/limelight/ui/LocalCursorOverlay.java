package com.limelight.ui;

import android.view.View;

import com.limelight.nvstream.NvConnection;

// Local patch (2026-09-20): a cursor drawn by the app on top of the stream.
//
// While the pointer is captured Android draws no cursor, so the only pointer on screen is the one the host
// paints into the video - a full round trip behind the finger. This overlay moves with the touchpad at once.
//
// It also OWNS the pointer position: every move is sent to the host as an absolute position instead of a
// relative delta. That keeps the two cursors on the same spot (the host one simply arrives later, which the
// user asked for) and means clicks land where the drawn cursor is.
//
// The position is kept as a fraction of the video, and the fraction is turned into screen pixels with the
// RENDERED rectangle of the stream view (its layout size times its scale, at its current position). The
// layout size alone is wrong: measured 2026-09-20 on a Tab S7+, the view was laid out 2701x1690 and scaled
// by 1.037 to fill the 2800x1752 panel, and using the layout size put the host cursor 3.7 % further from
// the top left corner than the drawn one, growing with the distance.
//
// 2026-09-23: absolute positions bypass Windows' own pointer speed and "Enhance pointer precision", so the
// drawn cursor felt slow and linear next to the plain relative mode. The overlay now applies the host's
// acceleration itself. The curve was MEASURED on the host: single relative moves injected with SendInput
// (the same input Sunshine produces), 25 samples each, medians, with the user's settings (pointer speed
// 14/20, enhanced precision on). Diagonal moves confirmed that Windows takes max(|dx|,|dy|) + min/2 as the
// speed, not the vector length (the length model missed by up to 3 px at 10-16 units, this one fits every
// point). The speed setting is an extra factor on top; 100 % is the same as the PC's own mouse.
public class LocalCursorOverlay {
    // Reference size for the absolute position packets. The host only uses x/width, so this is just a grid
    // fine enough that rounding stays well below one pixel.
    private static final short REF = 16384;

    // One relative move of IN units -> OUT host pixels on WIN11-EDU (see above)
    private static final float[] IN = {0, 1, 2, 3, 4, 5, 6, 7, 8, 10, 12, 14, 16, 20, 24, 28, 32, 40, 50, 60, 80, 100, 127};
    private static final float[] OUT = {0, 2, 4, 6, 8, 12, 15, 19, 22, 30, 37, 46, 61, 91, 121, 152, 182, 242, 317, 392, 543, 693, 896};

    private final View cursorView;
    private final View streamView;
    private final NvConnection conn;
    private final float speed;
    private final float hostWidth;
    private final float hostHeight;

    private final int[] streamLocation = new int[2];
    private final int[] cursorParentLocation = new int[2];
    private float fx = 0.5f, fy = 0.5f;

    public LocalCursorOverlay(View cursorView, View streamView, NvConnection conn, float speed, int hostWidth, int hostHeight) {
        this.cursorView = cursorView;
        this.streamView = streamView;
        this.conn = conn;
        this.speed = speed;
        this.hostWidth = hostWidth > 0 ? hostWidth : 1920;
        this.hostHeight = hostHeight > 0 ? hostHeight : 1080;
    }

    // Deltas from a mouse (relative axes path)
    public void move(float deltaX, float deltaY) {
        moveAccelerated(deltaX, deltaY);
    }

    // Deltas from the trackpad contexts: exactly what would otherwise go to the host as relative moves
    public void moveScaled(float deltaX, float deltaY) {
        moveAccelerated(deltaX, deltaY);
    }

    // What Windows would have made of this relative move, in host pixels
    private void moveAccelerated(float deltaX, float deltaY) {
        float ax = Math.abs(deltaX);
        float ay = Math.abs(deltaY);
        float magnitude = Math.max(ax, ay) + Math.min(ax, ay) / 2;
        if (magnitude <= 0) {
            return;
        }

        float gain = hostOutput(magnitude) / magnitude * speed;
        moveBy(deltaX * gain, deltaY * gain);
    }

    private static float hostOutput(float magnitude) {
        int last = IN.length - 1;
        if (magnitude >= IN[last]) {
            // The measured curve is a straight line from about 60 units on
            float slope = (OUT[last] - OUT[last - 1]) / (IN[last] - IN[last - 1]);
            return OUT[last] + (magnitude - IN[last]) * slope;
        }
        for (int i = 1; i <= last; i++) {
            if (magnitude <= IN[i]) {
                float t = (magnitude - IN[i - 1]) / (IN[i] - IN[i - 1]);
                return OUT[i - 1] + t * (OUT[i] - OUT[i - 1]);
            }
        }
        return magnitude;
    }

    // Move by host pixels
    private void moveBy(float hostDeltaX, float hostDeltaY) {
        float scaleX = streamView.getScaleX();
        float scaleY = streamView.getScaleY();
        float width = streamView.getWidth() * scaleX;
        float height = streamView.getHeight() * scaleY;
        if (width <= 0 || height <= 0) {
            return;
        }

        fx = clamp(fx + hostDeltaX / hostWidth);
        fy = clamp(fy + hostDeltaY / hostHeight);

        conn.sendMousePosition((short) (fx * REF), (short) (fy * REF), REF, REF);

        // The video surface may be nested inside StreamContainer. Convert its rendered origin
        // into the overlay parent coordinates instead of assuming both views share a parent.
        View cursorParent = (View) cursorView.getParent();
        if (cursorParent == null) {
            return;
        }
        streamView.getLocationOnScreen(streamLocation);
        cursorParent.getLocationOnScreen(cursorParentLocation);
        float left = streamLocation[0] - cursorParentLocation[0];
        float top = streamLocation[1] - cursorParentLocation[1];

        cursorView.setTranslationX(left + fx * width);
        cursorView.setTranslationY(top + fy * height);
        if (cursorView.getVisibility() != View.VISIBLE) {
            cursorView.setVisibility(View.VISIBLE);
        }
    }

    private static float clamp(float v) {
        return v < 0 ? 0 : (v > 1 ? 1 : v);
    }

    public void hide() {
        cursorView.setVisibility(View.GONE);
    }
}
