package com.limelight.ui;

import android.content.Context;
import android.os.Build;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.widget.FrameLayout;

import androidx.annotation.NonNull;

import com.limelight.BuildConfig;

public class ExternalControllerView extends FrameLayout {
    private InputCallbacks inputCallbacks;
    private Runnable userActivityCallback;

    // When enabled, we expose an InputConnection so that soft keyboards can send
    // commitText() events (e.g. swipe typing). Default disabled.
    private boolean commitTextEnabled = false;

    public void setInputCallbacks(InputCallbacks callbacks) {
        this.inputCallbacks = callbacks;
    }

    public void setUserActivityCallback(Runnable callback) {
        this.userActivityCallback = callback;
    }

    public void setCommitTextEnabled(boolean enabled) {
        this.commitTextEnabled = enabled;
        // Request focus so that IME targets this view when enabled
        if (enabled) {
            setFocusableInTouchMode(true);
            requestFocus();
        }
    }

    public ExternalControllerView(@NonNull Context context) {
        super(context);
    }

    private static boolean shouldOfferToGame(MotionEvent event) {
        int source = event.getSource();
        boolean pointerOrPosition = (source & InputDevice.SOURCE_CLASS_POINTER) != 0
                || (source & InputDevice.SOURCE_CLASS_POSITION) != 0
                || source == InputDevice.SOURCE_MOUSE_RELATIVE;
        if (!pointerOrPosition) {
            return false;
        }

        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_HOVER_ENTER:
            case MotionEvent.ACTION_HOVER_MOVE:
            case MotionEvent.ACTION_HOVER_EXIT:
            case MotionEvent.ACTION_MOVE:
            case MotionEvent.ACTION_SCROLL:
            case MotionEvent.ACTION_BUTTON_PRESS:
            case MotionEvent.ACTION_BUTTON_RELEASE:
                return true;
            default:
                return false;
        }
    }

    private void logMotionBoundary(String boundary, MotionEvent event) {
        if (!BuildConfig.DEBUG || event == null) {
            return;
        }

        android.util.Log.i("MoonlightInput", boundary
                + " action=" + MotionEvent.actionToString(event.getActionMasked())
                + " source=0x" + Integer.toHexString(event.getSource())
                + " pointerCapture=" + (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && hasPointerCapture()));
    }

    private void reportUserActivity(MotionEvent event) {
        if (userActivityCallback != null && shouldOfferToGame(event)) {
            userActivityCallback.run();
        }
    }

    @Override
    public boolean dispatchGenericMotionEvent(MotionEvent event) {
        logMotionBoundary("ExternalControllerView.dispatchGenericMotionEvent", event);
        reportUserActivity(event);

        if (inputCallbacks != null && shouldOfferToGame(event) &&
                inputCallbacks.handleGenericMotion(this, event)) {
            return true;
        }

        return super.dispatchGenericMotionEvent(event);
    }

    @Override
    public boolean onCapturedPointerEvent(MotionEvent event) {
        logMotionBoundary("ExternalControllerView.onCapturedPointerEvent", event);
        reportUserActivity(event);
        if (inputCallbacks != null && shouldOfferToGame(event) &&
                inputCallbacks.handleGenericMotion(this, event)) {
            return true;
        }

        return super.onCapturedPointerEvent(event);
    }

    @Override
    public void onPointerCaptureChange(boolean hasCapture) {
        super.onPointerCaptureChange(hasCapture);
        if (BuildConfig.DEBUG) {
            android.util.Log.i("MoonlightInput", "ExternalControllerView pointerCapture="
                    + hasCapture + " attached=" + isAttachedToWindow()
                    + " windowFocus=" + hasWindowFocus());
        }
    }

    @Override
    public boolean onKeyPreIme(int keyCode, KeyEvent event) {
        // This callbacks allows us to override dumb IME behavior like when
        // Samsung's default keyboard consumes Shift+Space.
        if (inputCallbacks != null) {
            if (event.getAction() == KeyEvent.ACTION_DOWN) {
                if (inputCallbacks.handleKeyDown(event)) {
                    return true;
                }
            }
            else if (event.getAction() == KeyEvent.ACTION_UP) {
                if (inputCallbacks.handleKeyUp(event)) {
                    return true;
                }
            }
        }

        return super.onKeyPreIme(keyCode, event);
    }

    @Override
    public boolean onCheckIsTextEditor() {
        return commitTextEnabled || super.onCheckIsTextEditor();
    }

    @Override
    public android.view.inputmethod.InputConnection onCreateInputConnection(android.view.inputmethod.EditorInfo outAttrs) {
        if (!commitTextEnabled) {
            return super.onCreateInputConnection(outAttrs);
        }

        // Basic text editor flags – we don't need extract UI or enter action
        outAttrs.inputType = android.text.InputType.TYPE_CLASS_TEXT;
        outAttrs.imeOptions = android.view.inputmethod.EditorInfo.IME_FLAG_NO_EXTRACT_UI;

        return new android.view.inputmethod.BaseInputConnection(this, false) {
            @Override
            public boolean commitText(CharSequence text, int newCursorPosition) {
                if (inputCallbacks != null && inputCallbacks.handleCommitText(text)) {
                    return true;
                }
                return super.commitText(text, newCursorPosition);
            }

            @Override
            public boolean deleteSurroundingText(int beforeLength, int afterLength) {
                if (inputCallbacks != null && inputCallbacks.handleDeleteSurroundingText(beforeLength, afterLength)) {
                    return true;
                }
                return super.deleteSurroundingText(beforeLength, afterLength);
            }
        };
    }

    public interface InputCallbacks {
        boolean handleGenericMotion(View view, MotionEvent event);
        boolean handleKeyUp(KeyEvent event);
        boolean handleKeyDown(KeyEvent event);
        boolean handleCommitText(CharSequence text);
        boolean handleDeleteSurroundingText(int beforeLength, int afterLength);
    }
}
