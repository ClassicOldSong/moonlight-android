package com.limelight.utils;

import android.annotation.SuppressLint;
import android.app.Presentation;
import android.content.Context;
import android.os.Build;
import android.os.Bundle;
import android.view.Display;
import android.view.inputmethod.BaseInputConnection;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;
import android.view.inputmethod.InputMethodManager;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.LinearLayout;

import androidx.annotation.NonNull;
import androidx.annotation.RequiresApi;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;

import com.limelight.Game;
import com.limelight.LimeLog;
import com.limelight.R;
import com.limelight.nvstream.input.KeyboardPacket;
import com.limelight.preferences.PreferenceConfiguration;

/**
 * Presentation that displays a touchpad UI on a secondary display.
 * Uses the Android Presentation API which is specifically designed for secondary displays.
 */
@RequiresApi(api = Build.VERSION_CODES.R)
public class TouchpadPresentation extends Presentation {

    private final PreferenceConfiguration prefConfig;
    private FrameLayout rootLayout;

    public TouchpadPresentation(Context outerContext, Display display, PreferenceConfiguration config) {
        super(outerContext, display);
        this.prefConfig = config;
        LimeLog.info("TouchpadPresentation - Created for display: " + display.getDisplayId());
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        LimeLog.info("TouchpadPresentation - onCreate called");

        // Request fullscreen
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        Window window = getWindow();
        if (window != null) {
            window.setFlags(
                WindowManager.LayoutParams.FLAG_FULLSCREEN,
                WindowManager.LayoutParams.FLAG_FULLSCREEN
            );

            // Hide system bars
            WindowInsetsControllerCompat insetsController = WindowCompat.getInsetsController(window, window.getDecorView());
            insetsController.setSystemBarsBehavior(WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
            insetsController.hide(WindowInsetsCompat.Type.systemBars());
            insetsController.hide(WindowInsetsCompat.Type.navigationBars());

            WindowCompat.setDecorFitsSystemWindows(window, false);
        }

        createUI();

        LimeLog.info("TouchpadPresentation - UI created successfully");
    }

    @SuppressLint("ClickableViewAccessibility")
    private void createUI() {
        // Create root layout with keyboard input support
        rootLayout = new KeyboardSupportFrameLayout(getContext());
        rootLayout.setLayoutParams(new ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        ));
        rootLayout.setBackgroundColor(0xFF1A1A1A); // Dark gray background
        rootLayout.setFocusable(true);
        rootLayout.setFocusableInTouchMode(true);
        rootLayout.requestFocus(); // Request focus so keyboard can attach

        // Set up touch handling to forward events to Game
        rootLayout.setOnTouchListener((v, event) -> {
            if (Game.instance != null) {
                // Forward touch events to Game's handleMotionEvent
                return Game.instance.handleMotionEvent(v, event);
            }
            return false;
        });

        // Add control buttons
        addControlButtons();

        setContentView(rootLayout);
    }

    private void addControlButtons() {
        int buttonSize = 80; // dp
        int margin = 20; // dp
        float density = getContext().getResources().getDisplayMetrics().density;
        int buttonSizePx = (int) (buttonSize * density);
        int marginPx = (int) (margin * density);

        // Top-right: Menu and Close buttons
        LinearLayout topRightButtons = createButtonContainer(Gravity.TOP | Gravity.END, marginPx);
        ImageButton menuButton = createImageButton(R.drawable.ic_menu_external, buttonSizePx, v -> {
            LimeLog.info("TouchpadPresentation - Menu button clicked");
            if (Game.instance != null) {
                Game.instance.showGameMenu(null);
            }
        });
        ImageButton closeButton = createImageButton(R.drawable.ic_close, buttonSizePx, v -> {
            LimeLog.info("TouchpadPresentation - Close button clicked");
            if (Game.instance != null) {
                Game.instance.finish();
            }
            dismiss();
        });
        topRightButtons.addView(menuButton);
        topRightButtons.addView(closeButton);
        rootLayout.addView(topRightButtons);

        // Bottom-right: Android keyboard toggle
        LinearLayout bottomRightButtons = createButtonContainer(Gravity.BOTTOM | Gravity.END, marginPx);
        ImageButton keyboardButton = createImageButton(R.drawable.ic_android_keyboard, buttonSizePx, v -> {
            LimeLog.info("TouchpadPresentation - Keyboard button clicked");
            // Show the Android soft keyboard, ensuring it's bound to our rootLayout
            rootLayout.requestFocus();
            InputMethodManager imm = (InputMethodManager) getContext().getSystemService(Context.INPUT_METHOD_SERVICE);
            if (imm != null) {
                imm.showSoftInput(rootLayout, InputMethodManager.SHOW_FORCED);
            }
        });
        bottomRightButtons.addView(keyboardButton);
        rootLayout.addView(bottomRightButtons);

        LimeLog.info("TouchpadPresentation - Control buttons added");
    }

    private LinearLayout createButtonContainer(int gravity, int margin) {
        LinearLayout container = new LinearLayout(getContext());
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT
        );
        params.gravity = gravity;
        params.setMargins(margin, margin, margin, margin);
        container.setLayoutParams(params);
        container.setOrientation(LinearLayout.HORIZONTAL);
        container.setFocusable(false);
        return container;
    }

    private ImageButton createImageButton(int drawableRes, int sizePx, View.OnClickListener listener) {
        ImageButton button = new ImageButton(getContext());
        button.setImageResource(drawableRes);
        // Use a simple background for now
        button.setBackgroundColor(0x80000000); // Semi-transparent black
        button.setScaleType(ImageButton.ScaleType.FIT_CENTER);
        button.setOnClickListener(listener);
        button.setFocusable(false);

        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(sizePx, sizePx);
        params.setMargins(10, 10, 10, 10);
        button.setLayoutParams(params);

        return button;
    }

    @Override
    protected void onStart() {
        super.onStart();
        LimeLog.info("TouchpadPresentation - onStart");
    }

    @Override
    protected void onStop() {
        super.onStop();
        LimeLog.info("TouchpadPresentation - onStop");
        // Don't automatically dismiss - let Game activity manage lifecycle
        // The presentation will be recreated in Game.onStart() if needed
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        // Forward hardware keyboard events to Game instance
        if (Game.instance != null) {
            return Game.instance.onKeyDown(keyCode, event);
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override
    public boolean onKeyUp(int keyCode, KeyEvent event) {
        // Forward hardware keyboard events to Game instance
        if (Game.instance != null) {
            return Game.instance.onKeyUp(keyCode, event);
        }
        return super.onKeyUp(keyCode, event);
    }

    @Override
    public void dismiss() {
        LimeLog.info("TouchpadPresentation - dismiss called");
        super.dismiss();
        // Notify Game that presentation was dismissed
        if (Game.instance != null) {
            Game.instance.onTouchpadPresentationDismissed();
        }
    }

    /**
     * Custom FrameLayout that supports keyboard input via InputConnection.
     * This allows the soft keyboard to stay open and send key events to the game.
     * Based on ExternalControllerView implementation.
     */
    private static class KeyboardSupportFrameLayout extends FrameLayout {
        public KeyboardSupportFrameLayout(@NonNull Context context) {
            super(context);
        }

        @Override
        public boolean onKeyPreIme(int keyCode, KeyEvent event) {
            // Allow back button to dismiss keyboard
            if (keyCode == KeyEvent.KEYCODE_BACK) {
                return false;
            }

            // Forward hardware keyboard events to Game
            if (Game.instance != null) {
                if (event.getAction() == KeyEvent.ACTION_DOWN) {
                    if (Game.instance.handleKeyDown(event)) {
                        return true;
                    }
                }
                else if (event.getAction() == KeyEvent.ACTION_UP) {
                    if (Game.instance.handleKeyUp(event)) {
                        return true;
                    }
                }
            }
            return super.onKeyPreIme(keyCode, event);
        }

        @Override
        public boolean onCheckIsTextEditor() {
            // Tell Android this view can receive text input
            return true;
        }

        @Override
        public InputConnection onCreateInputConnection(EditorInfo outAttrs) {
            // Configure the IME for immediate character input (no buffering)
            // Use password type to disable predictions and autocomplete
            outAttrs.inputType = android.text.InputType.TYPE_CLASS_TEXT |
                                android.text.InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD |
                                android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS;
            outAttrs.imeOptions = EditorInfo.IME_FLAG_NO_EXTRACT_UI |
                                  EditorInfo.IME_FLAG_NO_FULLSCREEN;

            // Return a BaseInputConnection for commit text and delete operations
            return new BaseInputConnection(this, false) {
                @Override
                public boolean setComposingText(CharSequence text, int newCursorPosition) {
                    // Convert composing text to immediate commit for live typing
                    return commitText(text, newCursorPosition);
                }

                @Override
                public boolean commitText(CharSequence text, int newCursorPosition) {
                    // Send text directly to the streaming connection
                    if (Game.instance != null && Game.instance.conn != null) {
                        try {
                            for (int i = 0; i < text.length(); i++) {
                                Game.instance.conn.sendUtf8Text(String.valueOf(text.charAt(i)));
                            }
                            return true;
                        } catch (Exception e) {
                            LimeLog.severe("TouchpadPresentation - Error sending text: " + e.getMessage());
                        }
                    }
                    return false;
                }

                @Override
                public boolean deleteSurroundingText(int beforeLength, int afterLength) {
                    // Send backspace events for deleted characters
                    if (Game.instance != null && Game.instance.getKeyboardTranslator() != null && Game.instance.conn != null) {
                        try {
                            short backspaceCode = Game.instance.getKeyboardTranslator().translate(KeyEvent.KEYCODE_DEL, 0, -1);
                            for (int i = 0; i < beforeLength; i++) {
                                Game.instance.conn.sendKeyboardInput(backspaceCode,
                                    KeyboardPacket.KEY_DOWN, (byte)0, (byte)0);
                                Game.instance.conn.sendKeyboardInput(backspaceCode,
                                    KeyboardPacket.KEY_UP, (byte)0, (byte)0);
                            }
                            return true;
                        } catch (Exception e) {
                            LimeLog.severe("TouchpadPresentation - Error sending backspace: " + e.getMessage());
                        }
                    }
                    return false;
                }
            };
        }
    }
}
