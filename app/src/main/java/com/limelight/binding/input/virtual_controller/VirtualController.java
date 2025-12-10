/**
 * Created by Karim Mreisi.
 */

package com.limelight.binding.input.virtual_controller;

import android.content.Context;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.util.DisplayMetrics;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.Toast;

import com.limelight.LimeLog;
import com.limelight.R;
import com.limelight.binding.input.ControllerHandler;
import com.limelight.preferences.PreferenceConfiguration;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public class VirtualController {
    public static class ControllerInputContext {
//        public short inputMap = 0x0000;
        public int inputMap = 0;
        public byte leftTrigger = 0x00;
        public byte rightTrigger = 0x00;
        public short rightStickX = 0x0000;
        public short rightStickY = 0x0000;
        public short leftStickX = 0x0000;
        public short leftStickY = 0x0000;
    }

    public enum ControllerMode {
        Active,
        MoveButtons,
        ResizeButtons,
        DisableEnableButtons
    }

    public interface ModeChangeListener {
        void onModeChanged(ControllerMode newMode);
    }

    public interface KeyboardInputListener {
        void onKeyboardInput(short keyCode, byte keyAction, byte modifiers);
    }

    public interface ProfileSwitchListener {
        void onProfileSwitched();
    }

    private static final boolean _PRINT_DEBUG_INFORMATION = false;

    private final ControllerHandler controllerHandler;
    private final Context context;
    private final Handler handler;

    private final Runnable delayedRetransmitRunnable = new Runnable() {
        @Override
        public void run() {
            sendControllerInputContextInternal();
        }
    };

    private FrameLayout frame_layout = null;

    ControllerMode currentMode = ControllerMode.Active;
    ControllerInputContext inputContext = new ControllerInputContext();

    private Button buttonConfigure = null;
    private boolean configButtonEnabled = true;
    private float configButtonPressedX = 0;
    private float configButtonPressedY = 0;
    private int configButtonStartWidth = 0;
    private int configButtonStartHeight = 0;
    private boolean configButtonBeingEdited = false;
    private long configButtonPressTime = 0;
    private static final long LONG_PRESS_DURATION = 500; // milliseconds

    private List<VirtualControllerElement> elements = new ArrayList<>();

    private Vibrator vibrator;

    private ModeChangeListener modeChangeListener = null;
    private KeyboardInputListener keyboardInputListener = null;
    private ProfileSwitchListener profileSwitchListener = null;

    private final VibrationEffect defaultVibrationEffect;

    // Snapping state
    private boolean snappingEnabled = true;

    // Paired sizing state
    private boolean pairedSizingEnabled = true;

    public VirtualController(final ControllerHandler controllerHandler, FrameLayout layout, final Context context) {
        this.controllerHandler = controllerHandler;
        this.frame_layout = layout;
        this.context = context;
        this.handler = new Handler(Looper.getMainLooper());

        this.vibrator = (Vibrator) context.getSystemService(Context.VIBRATOR_SERVICE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            defaultVibrationEffect = VibrationEffect.createOneShot(10, VibrationEffect.DEFAULT_AMPLITUDE);
        } else {
            defaultVibrationEffect = null;
        }

        buttonConfigure = new Button(context);
        buttonConfigure.setAlpha(0.25f);
        buttonConfigure.setFocusable(false);
        buttonConfigure.setBackgroundResource(R.drawable.ic_settings);
        buttonConfigure.setOnTouchListener(new View.OnTouchListener() {
            private final Handler longPressHandler = new Handler(Looper.getMainLooper());
            private Runnable longPressRunnable;

            @Override
            public boolean onTouch(View v, MotionEvent event) {
                switch (event.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        configButtonPressedX = event.getX();
                        configButtonPressedY = event.getY();
                        configButtonStartWidth = buttonConfigure.getWidth();
                        configButtonStartHeight = buttonConfigure.getHeight();
                        configButtonPressTime = System.currentTimeMillis();

                        // Set up long press detection for DisableEnable/Move/Resize modes
                        if ((currentMode == ControllerMode.DisableEnableButtons ||
                             currentMode == ControllerMode.MoveButtons ||
                             currentMode == ControllerMode.ResizeButtons)
                            && !configButtonBeingEdited) {
                            longPressRunnable = new Runnable() {
                                @Override
                                public void run() {
                                    if (currentMode == ControllerMode.DisableEnableButtons) {
                                        // In DisableEnable mode, long press toggles visibility
                                        configButtonEnabled = !configButtonEnabled;
                                        buttonConfigure.setVisibility(configButtonEnabled ? View.VISIBLE : View.GONE);
                                        if (vibrator != null) {
                                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && defaultVibrationEffect != null) {
                                                vibrator.vibrate(defaultVibrationEffect);
                                            } else {
                                                vibrator.vibrate(10);
                                            }
                                        }
                                    } else {
                                        // In Move/Resize modes, long press enters edit mode
                                        configButtonBeingEdited = true;
                                        updateConfigButtonBorder();
                                        if (vibrator != null) {
                                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && defaultVibrationEffect != null) {
                                                vibrator.vibrate(defaultVibrationEffect);
                                            } else {
                                                vibrator.vibrate(10);
                                            }
                                        }
                                    }
                                }
                            };
                            longPressHandler.postDelayed(longPressRunnable, LONG_PRESS_DURATION);
                        }
                        return true;

                    case MotionEvent.ACTION_MOVE:
                        // Only allow movement/resize if in edit mode
                        if (configButtonBeingEdited) {
                            if (currentMode == ControllerMode.MoveButtons) {
                                moveConfigButton(
                                    (int) configButtonPressedX,
                                    (int) configButtonPressedY,
                                    (int) event.getX(),
                                    (int) event.getY()
                                );
                            } else if (currentMode == ControllerMode.ResizeButtons) {
                                resizeConfigButton(
                                    (int) configButtonPressedX,
                                    (int) configButtonPressedY,
                                    (int) event.getX(),
                                    (int) event.getY()
                                );
                            }
                        } else {
                            // Cancel long press if user moves before timeout
                            if (Math.abs(event.getX() - configButtonPressedX) > 10 ||
                                Math.abs(event.getY() - configButtonPressedY) > 10) {
                                if (longPressRunnable != null) {
                                    longPressHandler.removeCallbacks(longPressRunnable);
                                    longPressRunnable = null;
                                }
                            }
                        }
                        return true;

                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL:
                        // Cancel long press detection
                        if (longPressRunnable != null) {
                            longPressHandler.removeCallbacks(longPressRunnable);
                            longPressRunnable = null;
                        }

                        // If already in edit mode, exit it
                        if (configButtonBeingEdited) {
                            configButtonBeingEdited = false;
                            updateConfigButtonBorder();
                            return true;
                        }

                        // Normal tap behavior - only if not dragging and not in edit mode
                        long pressDuration = System.currentTimeMillis() - configButtonPressTime;
                        if (Math.abs(event.getX() - configButtonPressedX) < 10 &&
                            Math.abs(event.getY() - configButtonPressedY) < 10 &&
                            pressDuration < LONG_PRESS_DURATION) {

                            String message;
                            if (currentMode == ControllerMode.Active) {
                                currentMode = ControllerMode.DisableEnableButtons;
                                showElements();
                                message = context.getString(R.string.configuration_mode_disable_enable_buttons);
                            } else if (currentMode == ControllerMode.DisableEnableButtons) {
                                currentMode = ControllerMode.MoveButtons;
                                showEnabledElements();
                                message = context.getString(R.string.configuration_mode_move_buttons);
                            } else if (currentMode == ControllerMode.MoveButtons) {
                                currentMode = ControllerMode.ResizeButtons;
                                message = context.getString(R.string.configuration_mode_resize_buttons);
                            } else {
                                currentMode = ControllerMode.Active;
                                message = context.getString(R.string.configuration_mode_exiting);
                            }

                            Toast.makeText(context, message, Toast.LENGTH_SHORT).show();

                            buttonConfigure.invalidate();

                            for (VirtualControllerElement element : elements) {
                                element.invalidate();
                            }

                            // Notify listener of mode change
                            if (modeChangeListener != null) {
                                modeChangeListener.onModeChanged(currentMode);
                            }
                        }
                        return true;
                }
                return false;
            }
        });

    }

    private void moveConfigButton(int pressed_x, int pressed_y, int x, int y) {
        int newPos_x = (int) buttonConfigure.getX() + x - pressed_x;
        int newPos_y = (int) buttonConfigure.getY() + y - pressed_y;

        FrameLayout.LayoutParams layoutParams = (FrameLayout.LayoutParams) buttonConfigure.getLayoutParams();
        if (layoutParams != null) {
            layoutParams.leftMargin = newPos_x > 0 ? newPos_x : 0;
            layoutParams.topMargin = newPos_y > 0 ? newPos_y : 0;
            layoutParams.rightMargin = 0;
            layoutParams.bottomMargin = 0;
            buttonConfigure.requestLayout();
        }
    }

    private void resizeConfigButton(int pressed_x, int pressed_y, int width, int height) {
        FrameLayout.LayoutParams layoutParams = (FrameLayout.LayoutParams) buttonConfigure.getLayoutParams();
        if (layoutParams != null) {
            int newHeight = height + (configButtonStartHeight - pressed_y);
            int newWidth = width + (configButtonStartWidth - pressed_x);

            // Ensure minimum size
            newHeight = newHeight > 20 ? newHeight : 20;
            newWidth = newWidth > 20 ? newWidth : 20;

            layoutParams.height = newHeight;
            layoutParams.width = newWidth;
            buttonConfigure.requestLayout();
        }
    }

    private void updateConfigButtonBorder() {
        if (configButtonBeingEdited) {
            // Show colored border based on mode
            if (currentMode == ControllerMode.MoveButtons) {
                // Red border for Move mode
                buttonConfigure.setBackgroundColor(0xF0FF0000);
            } else if (currentMode == ControllerMode.ResizeButtons) {
                // Magenta border for Resize mode
                buttonConfigure.setBackgroundColor(0xF0FF00FF);
            }
            buttonConfigure.setAlpha(0.5f);
        } else {
            // Restore normal appearance
            buttonConfigure.setBackgroundResource(R.drawable.ic_settings);
            buttonConfigure.setAlpha(0.25f);
        }
        buttonConfigure.invalidate();
    }

    Handler getHandler() {
        return handler;
    }

    public void hide() {
        for (VirtualControllerElement element : elements) {
            element.setVisibility(View.GONE);
        }

        buttonConfigure.setVisibility(View.GONE);
    }

    public void show() {
        showEnabledElements();

        buttonConfigure.setVisibility(configButtonEnabled ? View.VISIBLE : View.GONE);
    }

    public int switchShowHide() {
        if (buttonConfigure.getVisibility() == View.VISIBLE) {
            hide();
            return 0;
        } else {
            show();
            return 1;
        }
    }

    public void showElements(){
        for(VirtualControllerElement element : elements){
            element.setVisibility(View.VISIBLE);
        }
    }

    public void showEnabledElements(){
        for(VirtualControllerElement element: elements){
            element.setVisibility( element.enabled ? View.VISIBLE : View.GONE );
        }
    }

    public void removeElements() {
        for (VirtualControllerElement element : elements) {
            frame_layout.removeView(element);
        }
        elements.clear();

        frame_layout.removeView(buttonConfigure);
    }

    public void setOpacity(int opacity) {
        for (VirtualControllerElement element : elements) {
            element.setOpacity(opacity);
        }
    }


    public void addElement(VirtualControllerElement element, int x, int y, int width, int height) {
        elements.add(element);
        FrameLayout.LayoutParams layoutParams = new FrameLayout.LayoutParams(width, height);
        layoutParams.setMargins(x, y, 0, 0);

        frame_layout.addView(element, layoutParams);
    }

    public List<VirtualControllerElement> getElements() {
        return elements;
    }

    public org.json.JSONObject getConfigButtonConfiguration() throws org.json.JSONException {
        org.json.JSONObject configuration = new org.json.JSONObject();
        FrameLayout.LayoutParams layoutParams = (FrameLayout.LayoutParams) buttonConfigure.getLayoutParams();

        if (layoutParams != null) {
            configuration.put("LEFT", layoutParams.leftMargin);
            configuration.put("TOP", layoutParams.topMargin);
            configuration.put("WIDTH", layoutParams.width);
            configuration.put("HEIGHT", layoutParams.height);
        }
        configuration.put("ENABLED", configButtonEnabled);
        return configuration;
    }

    public void setConfigButtonConfiguration(org.json.JSONObject configuration) throws org.json.JSONException {
        FrameLayout.LayoutParams layoutParams = (FrameLayout.LayoutParams) buttonConfigure.getLayoutParams();

        if (layoutParams != null && configuration != null) {
            layoutParams.leftMargin = configuration.getInt("LEFT");
            layoutParams.topMargin = configuration.getInt("TOP");
            layoutParams.width = configuration.getInt("WIDTH");
            layoutParams.height = configuration.getInt("HEIGHT");
            buttonConfigure.requestLayout();
        }
        configButtonEnabled = configuration.getBoolean("ENABLED");
        buttonConfigure.setVisibility(configButtonEnabled ? View.VISIBLE : View.GONE);
    }

    private static final void _DBG(String text) {
        if (_PRINT_DEBUG_INFORMATION) {
            LimeLog.info("VirtualController: " + text);
        }
    }

    public void refreshLayout() {
        removeElements();

        DisplayMetrics screen = context.getResources().getDisplayMetrics();

        int buttonSize = (int)(screen.heightPixels*0.06f);
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(buttonSize, buttonSize);
        params.leftMargin = 15;
        params.topMargin = 15;
        frame_layout.addView(buttonConfigure, params);

        // Start with the default layout
        VirtualControllerConfigurationLoader.createDefaultLayout(this, context);

        // Apply user preferences onto the default layout
        VirtualControllerConfigurationLoader.loadFromPreferences(this, context);
    }

    public ControllerMode getControllerMode() {
        return currentMode;
    }

    public void setModeChangeListener(ModeChangeListener listener) {
        this.modeChangeListener = listener;
    }

    public void setKeyboardInputListener(KeyboardInputListener listener) {
        this.keyboardInputListener = listener;
    }

    public void setProfileSwitchListener(ProfileSwitchListener listener) {
        this.profileSwitchListener = listener;
    }

    public void sendKeyboardInput(short keyCode, byte keyAction, byte modifiers) {
        if (keyboardInputListener != null) {
            keyboardInputListener.onKeyboardInput(keyCode, keyAction, modifiers);
        }
    }

    public void setControllerMode(ControllerMode mode, boolean notifyListener) {
        if (currentMode == mode) {
            return; // Already in this mode
        }

        ControllerMode previousMode = currentMode;
        currentMode = mode;

        // Exit config button edit mode when changing modes
        if (configButtonBeingEdited) {
            configButtonBeingEdited = false;
            updateConfigButtonBorder();
        }

        // Handle visibility based on mode
        if (mode == ControllerMode.DisableEnableButtons) {
            showElements();
        } else if (mode == ControllerMode.MoveButtons || mode == ControllerMode.ResizeButtons) {
            showEnabledElements();
        } else if (mode == ControllerMode.Active) {
            // No longer auto-save - user must manually save via OSC Profiles menu
            showEnabledElements();
        }

        // Invalidate all elements to redraw with new mode
        buttonConfigure.invalidate();
        for (VirtualControllerElement element : elements) {
            element.invalidate();
        }

        // Notify listener if requested (used for synchronization)
        if (notifyListener && modeChangeListener != null) {
            modeChangeListener.onModeChanged(currentMode);
        }
    }

    public ControllerInputContext getControllerInputContext() {
        return inputContext;
    }

    private void sendControllerInputContextInternal() {
        _DBG("INPUT_MAP + " + inputContext.inputMap);
        _DBG("LEFT_TRIGGER " + inputContext.leftTrigger);
        _DBG("RIGHT_TRIGGER " + inputContext.rightTrigger);
        _DBG("LEFT STICK X: " + inputContext.leftStickX + " Y: " + inputContext.leftStickY);
        _DBG("RIGHT STICK X: " + inputContext.rightStickX + " Y: " + inputContext.rightStickY);

        if (controllerHandler != null) {
            controllerHandler.reportOscState(
                    inputContext.inputMap,
                    inputContext.leftStickX,
                    inputContext.leftStickY,
                    inputContext.rightStickX,
                    inputContext.rightStickY,
                    inputContext.leftTrigger,
                    inputContext.rightTrigger
            );
        }
    }

    public void sendControllerInputContext(long vibrationDuration, int vibrationAmplitude) {
        // Cancel retransmissions of prior gamepad inputs
        handler.removeCallbacks(delayedRetransmitRunnable);

        sendControllerInputContextInternal();
        if (frame_layout != null && PreferenceConfiguration.readPreferences(context).enableKeyboardVibrate) {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                VibrationEffect effect;
                if (vibrationDuration == 0) {
                    effect = defaultVibrationEffect;
                } else {
                    effect = VibrationEffect.createOneShot(vibrationDuration, vibrationAmplitude);
                }
                vibrator.vibrate(effect);
            } else {
                if (vibrationDuration == 0) {
                    vibrationDuration = 10;
                }
                vibrator.vibrate(vibrationDuration);
            }
        }
        // HACK: GFE sometimes discards gamepad packets when they are received
        // very shortly after another. This can be critical if an axis zeroing packet
        // is lost and causes an analog stick to get stuck. To avoid this, we retransmit
        // the gamepad state a few times unless another input event happens before then.
        handler.postDelayed(delayedRetransmitRunnable, 25);
        handler.postDelayed(delayedRetransmitRunnable, 50);
        handler.postDelayed(delayedRetransmitRunnable, 75);
    }

    public void sendControllerInputContext() {
        sendControllerInputContext(0, 0);
    }

    public boolean isSnappingEnabled() {
        return snappingEnabled;
    }

    public void setSnappingEnabled(boolean enabled) {
        this.snappingEnabled = enabled;
    }

    public void toggleSnapping() {
        snappingEnabled = !snappingEnabled;
        String message = context.getString(snappingEnabled ?
            R.string.snapping_enabled : R.string.snapping_disabled);
        Toast.makeText(context, message, Toast.LENGTH_SHORT).show();
    }

    public boolean isPairedSizingEnabled() {
        return pairedSizingEnabled;
    }

    public void setPairedSizingEnabled(boolean enabled) {
        this.pairedSizingEnabled = enabled;
    }

    public void togglePairedSizing() {
        pairedSizingEnabled = !pairedSizingEnabled;
        String message = context.getString(pairedSizingEnabled ?
            R.string.paired_sizing_enabled : R.string.paired_sizing_disabled);
        Toast.makeText(context, message, Toast.LENGTH_SHORT).show();
    }

    public DisplayMetrics getDisplayMetrics() {
        return context.getResources().getDisplayMetrics();
    }

    public void switchToProfile(UUID profileId) {
        OscProfilesManager manager = OscProfilesManager.getInstance();
        manager.setActive(profileId);

        // Reload the OSC with the new profile's configuration
        refreshLayout();

        // Notify listener to restore deposited buttons
        if (profileSwitchListener != null) {
            profileSwitchListener.onProfileSwitched();
        }

        Toast.makeText(context, "Switched to profile: " + manager.getActiveName(), Toast.LENGTH_SHORT).show();
    }
}
