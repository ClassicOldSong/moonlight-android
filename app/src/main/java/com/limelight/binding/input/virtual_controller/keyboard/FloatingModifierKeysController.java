package com.limelight.binding.input.virtual_controller.keyboard;

import android.content.Context;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;

import com.limelight.Game;
import com.limelight.binding.input.KeyboardTranslator;
import com.limelight.nvstream.NvConnection;
import com.limelight.nvstream.input.KeyboardPacket;
import com.limelight.preferences.PreferenceConfiguration;

public class FloatingModifierKeysController {
    private final Context context;
    private final NvConnection conn;
    private final FrameLayout frame_layout;
    private final PreferenceConfiguration prefConfig;
    private LinearLayout modifierKeysView;
    private boolean shown = false;
    private byte modifierState = 0;
    private float dX, dY;

    private Button ctrlButton;
    private Button altButton;
    private Button shiftButton;
    private Button leftHandle;
    private Button rightHandle;

    public FloatingModifierKeysController(NvConnection conn, FrameLayout layout, Context context) {
        this.context = context;
        this.conn = conn;
        this.frame_layout = layout;
        this.prefConfig = PreferenceConfiguration.readPreferences(context);
        
        createModifierKeysView();
    }

    private Button createHandleButton() {
        Button handle = new Button(context);
        handle.setText("");
        handle.setBackgroundColor(android.graphics.Color.TRANSPARENT);
        handle.setMinWidth(0);
        handle.setMinHeight(0);
        handle.setPadding(2, 0, 2, 0);
        
        int defaultHeight = context.getResources().getDimensionPixelSize(android.R.dimen.app_icon_size);
        int reducedHeight = (int)(defaultHeight * 0.7);
        
        LinearLayout.LayoutParams handleParams = new LinearLayout.LayoutParams(
                10,
                reducedHeight
        );
        handleParams.setMargins(0, 0, 0, 0);
        handle.setLayoutParams(handleParams);

        handle.setOnTouchListener((v, event) -> {
            switch (event.getAction()) {
                case MotionEvent.ACTION_DOWN:
                    dX = modifierKeysView.getX() - event.getRawX();
                    dY = modifierKeysView.getY() - event.getRawY();
                    return true;
                case MotionEvent.ACTION_MOVE:
                    modifierKeysView.setX(event.getRawX() + dX);
                    modifierKeysView.setY(event.getRawY() + dY);
                    return true;
                default:
                    return false;
            }
        });

        return handle;
    }

    private void createModifierKeysView() {
        modifierKeysView = new LinearLayout(context);
        modifierKeysView.setOrientation(LinearLayout.HORIZONTAL);
        
        leftHandle = createHandleButton();
        Button middleHandle1 = createHandleButton();
        Button middleHandle2 = createHandleButton();
        rightHandle = createHandleButton();
        
        ctrlButton = createModifierButton("Ctrl", (short)KeyboardTranslator.VK_LCONTROL);
        altButton = createModifierButton("Alt", (short)KeyboardTranslator.VK_LMENU);
        shiftButton = createModifierButton("Shift", (short)KeyboardTranslator.VK_LSHIFT);

        modifierKeysView.addView(leftHandle);
        modifierKeysView.addView(ctrlButton);
        modifierKeysView.addView(middleHandle1);
        modifierKeysView.addView(altButton);
        modifierKeysView.addView(middleHandle2);
        modifierKeysView.addView(shiftButton);
        modifierKeysView.addView(rightHandle);

        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
        );
        params.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
        params.topMargin = 0;
        
        modifierKeysView.setLayoutParams(params);
        modifierKeysView.setAlpha(prefConfig.oscOpacity / 100f);
        modifierKeysView.setVisibility(View.GONE);
    }

    private Button createModifierButton(String text, final short keyCode) {
        Button button = new Button(context);
        button.setText(text);
        button.setAlpha(0.7f);
        button.setTextSize(8);
        button.setMinWidth(0);
        button.setMinHeight(0);
        button.setPadding(10, 5, 10, 5);
        
        int defaultHeight = context.getResources().getDimensionPixelSize(android.R.dimen.app_icon_size);
        int reducedHeight = (int)(defaultHeight * 0.7);
        int defaultWidth = context.getResources().getDimensionPixelSize(android.R.dimen.app_icon_size);
        int reducedWidth = (int)(defaultWidth * 0.9);
        
        LinearLayout.LayoutParams buttonParams = new LinearLayout.LayoutParams(
                reducedWidth,
                reducedHeight
        );
        buttonParams.setMargins(0, 0, 0, 0);
        button.setLayoutParams(buttonParams);

        button.setOnClickListener(v -> {
            byte modifier = getModifierForKey(keyCode);
            if ((modifierState & modifier) != 0) {
                modifierState &= ~modifier;
                button.setAlpha(0.7f);
                conn.sendKeyboardInput(keyCode, KeyboardPacket.KEY_UP, (byte)modifierState, (byte)0);
            } else {
                modifierState |= modifier;
                button.setAlpha(1.0f);
                conn.sendKeyboardInput(keyCode, KeyboardPacket.KEY_DOWN, (byte)modifierState, (byte)0);
            }
        });

        return button;
    }

    private byte getModifierForKey(short keyCode) {
        switch (keyCode) {
            case KeyboardTranslator.VK_LSHIFT:
                return KeyboardPacket.MODIFIER_SHIFT;
            case KeyboardTranslator.VK_LCONTROL:
                return KeyboardPacket.MODIFIER_CTRL;
            case KeyboardTranslator.VK_LMENU:
                return KeyboardPacket.MODIFIER_ALT;
            default:
                return 0;
        }
    }

    public void show() {
        if (!shown) {
            frame_layout.addView(modifierKeysView);
            modifierKeysView.setVisibility(View.VISIBLE);
            shown = true;
        }
    }

    public void hide() {
        if (shown) {
            // Release all pressed modifier keys
            if ((modifierState & KeyboardPacket.MODIFIER_CTRL) != 0) {
                conn.sendKeyboardInput((short)KeyboardTranslator.VK_LCONTROL, KeyboardPacket.KEY_UP, (byte)0, (byte)0);
            }
            if ((modifierState & KeyboardPacket.MODIFIER_ALT) != 0) {
                conn.sendKeyboardInput((short)KeyboardTranslator.VK_LMENU, KeyboardPacket.KEY_UP, (byte)0, (byte)0);
            }
            if ((modifierState & KeyboardPacket.MODIFIER_SHIFT) != 0) {
                conn.sendKeyboardInput((short)KeyboardTranslator.VK_LSHIFT, KeyboardPacket.KEY_UP, (byte)0, (byte)0);
            }
            modifierState = 0;
            
            frame_layout.removeView(modifierKeysView);
            modifierKeysView.setVisibility(View.GONE);
            shown = false;
        }
    }

    public void toggle() {
        if (shown) {
            hide();
        } else {
            show();
        }
    }

    public boolean isShown() {
        return shown;
    }
} 
