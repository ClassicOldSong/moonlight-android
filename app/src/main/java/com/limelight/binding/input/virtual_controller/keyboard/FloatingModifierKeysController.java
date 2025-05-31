package com.limelight.binding.input.virtual_controller.keyboard;

import android.content.Context;
import android.view.Gravity;
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

    private Button ctrlButton;
    private Button altButton;
    private Button shiftButton;

    public FloatingModifierKeysController(NvConnection conn, FrameLayout layout, Context context) {
        this.context = context;
        this.conn = conn;
        this.frame_layout = layout;
        this.prefConfig = PreferenceConfiguration.readPreferences(context);
        
        createModifierKeysView();
    }

    private void createModifierKeysView() {
        modifierKeysView = new LinearLayout(context);
        modifierKeysView.setOrientation(LinearLayout.HORIZONTAL);
        
        ctrlButton = createModifierButton("Ctrl", (short)KeyboardTranslator.VK_LCONTROL);
        altButton = createModifierButton("Alt", (short)KeyboardTranslator.VK_LMENU);
        shiftButton = createModifierButton("Shift", (short)KeyboardTranslator.VK_LSHIFT);

        modifierKeysView.addView(ctrlButton);
        modifierKeysView.addView(altButton);
        modifierKeysView.addView(shiftButton);

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
        
        // Get default button height and reduce it by 30%
        int defaultHeight = context.getResources().getDimensionPixelSize(android.R.dimen.app_icon_size);
        int reducedHeight = (int)(defaultHeight * 0.7); // Reduce height by 30%
        
        LinearLayout.LayoutParams buttonParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                reducedHeight // Set fixed reduced height
        );
        buttonParams.setMargins(2, 0, 2, 0);
        button.setLayoutParams(buttonParams);

        button.setOnClickListener(v -> {
            byte modifier = getModifierForKey(keyCode);
            if ((modifierState & modifier) != 0) {
                // Key is active, deactivate it
                modifierState &= ~modifier;
                button.setAlpha(0.7f);
                conn.sendKeyboardInput(keyCode, KeyboardPacket.KEY_UP, (byte)modifierState, (byte)0);
            } else {
                // Key is inactive, activate it
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
