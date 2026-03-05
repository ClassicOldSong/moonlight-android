package com.limelight.binding.input;

import android.content.Context;
import android.os.Handler;
import android.widget.Toast;

import com.limelight.GameMenu;
import com.limelight.R;
import com.limelight.nvstream.NvConnection;
import com.limelight.nvstream.input.ControllerPacket;
import com.limelight.nvstream.input.MouseButtonPacket;
import com.limelight.preferences.PreferenceConfiguration;
import com.limelight.utils.Vector2d;

import java.util.ArrayList;
import java.util.List;

public class MouseEmulationHandler {

    private static final int MOUSE_EMULATION_REPORT_TICK_PERIOD_MS = 50;
    private static final float RAW_STICK_AXIS_MAX = 32766.0f; // Limit is Short.MAX_VALUE - 1
    private static final float MOUSE_EMULATION_BASE_SPEED_PX_PER_TICK = 4.0f;

    public interface StickValueProvider {
        short getLeftStickX();
        short getLeftStickY();
        short getRightStickX();
        short getRightStickY();
    }

    private final NvConnection conn;
    private final PreferenceConfiguration prefConfig;
    private final Handler handler;
    private final Context activityContext;
    private final StickValueProvider stickProvider;

    private boolean active;
    private int lastInputMap;

    public MouseEmulationHandler(NvConnection conn, PreferenceConfiguration prefConfig,
                                  Handler handler, Context activityContext,
                                  StickValueProvider stickProvider) {
        this.conn = conn;
        this.prefConfig = prefConfig;
        this.handler = handler;
        this.activityContext = activityContext;
        this.stickProvider = stickProvider;
    }

    private final Runnable tickRunnable = new Runnable() {
        @Override
        public void run() {
            if (!active) {
                return;
            }

            short leftStickX = stickProvider.getLeftStickX();
            short leftStickY = stickProvider.getLeftStickY();
            short rightStickX = stickProvider.getRightStickX();
            short rightStickY = stickProvider.getRightStickY();

            switch (prefConfig.analogStickForScrolling) {
            case RIGHT:
                sendEmulatedMouseMove(leftStickX, leftStickY);
                sendEmulatedMouseScroll(rightStickX, rightStickY);
            break;
            case LEFT:
                sendEmulatedMouseMove(rightStickX, rightStickY);
                sendEmulatedMouseScroll(leftStickX, leftStickY);
            break;
            case NONE:
            default:
                sendEmulatedMouseMove(leftStickX, leftStickY);
                sendEmulatedMouseMove(rightStickX, rightStickY);
            break;
            }

            handler.postDelayed(this, MOUSE_EMULATION_REPORT_TICK_PERIOD_MS);
        }
    };

    public boolean isActive() {
        return active;
    }

    public void toggle() {
        handler.removeCallbacks(tickRunnable);
        active = !active;
        Toast.makeText(activityContext, "Mouse emulation is: " + (active ? "ON" : "OFF"),
                Toast.LENGTH_SHORT).show();
        if (active) {
            handler.postDelayed(tickRunnable, MOUSE_EMULATION_REPORT_TICK_PERIOD_MS);
        }
    }

    public void destroy() {
        active = false;
        handler.removeCallbacks(tickRunnable);
    }

    public List<GameMenu.MenuOption> getMenuOptions() {
        List<GameMenu.MenuOption> options = new ArrayList<>();
        options.add(new GameMenu.MenuOption(activityContext.getString(active ?
                R.string.game_menu_toggle_mouse_off : R.string.game_menu_toggle_mouse_on),
                true, this::toggle));
        return options;
    }

    private Vector2d convertRawStickAxisToPixelMovement(short stickX, short stickY) {
        Vector2d vector = new Vector2d();
        vector.initialize(stickX, stickY);
        vector.scalarMultiply(MOUSE_EMULATION_BASE_SPEED_PX_PER_TICK / RAW_STICK_AXIS_MAX);
        if (vector.getMagnitude() > 0) {
            // Cubic acceleration: ramp up speed as stick moves further from center
            vector.scalarMultiply(Math.pow(vector.getMagnitude(), 2));
        }
        return vector;
    }

    private void sendEmulatedMouseMove(short x, short y) {
        Vector2d vector = convertRawStickAxisToPixelMovement(x, y);
        vector.scalarMultiply(prefConfig.mouseEmulationSensitivity / 100.0f);  // user sensitivity
        if (vector.getMagnitude() >= 1) {
            conn.sendMouseMove((short) vector.getX(), (short) -vector.getY());
        }
    }

    private void sendEmulatedMouseScroll(short x, short y) {
        Vector2d vector = convertRawStickAxisToPixelMovement(x, y);
        if (vector.getMagnitude() >= 1) {
            conn.sendMouseHighResScroll((short) vector.getY());
            conn.sendMouseHighResHScroll((short) vector.getX());
        }
    }

    public void handleButtonInput(int inputMap, short controllerNumber, short activeControllerMask) {
        if (!active) {
            return;
        }

        int changedMask = inputMap ^ lastInputMap;
        boolean aDown = (inputMap & ControllerPacket.A_FLAG) != 0;
        boolean bDown = (inputMap & ControllerPacket.B_FLAG) != 0;
        lastInputMap = inputMap;

        if ((changedMask & ControllerPacket.A_FLAG) != 0) {
            if (aDown) {
                conn.sendMouseButtonDown(MouseButtonPacket.BUTTON_LEFT);
            }
            else {
                conn.sendMouseButtonUp(MouseButtonPacket.BUTTON_LEFT);
            }
        }
        if ((changedMask & ControllerPacket.B_FLAG) != 0) {
            if (bDown) {
                conn.sendMouseButtonDown(MouseButtonPacket.BUTTON_RIGHT);
            }
            else {
                conn.sendMouseButtonUp(MouseButtonPacket.BUTTON_RIGHT);
            }
        }
        if ((changedMask & ControllerPacket.UP_FLAG) != 0) {
            if ((inputMap & ControllerPacket.UP_FLAG) != 0) {
                conn.sendMouseScroll((byte) 1);
            }
        }
        if ((changedMask & ControllerPacket.DOWN_FLAG) != 0) {
            if ((inputMap & ControllerPacket.DOWN_FLAG) != 0) {
                conn.sendMouseScroll((byte) -1);
            }
        }
        if ((changedMask & ControllerPacket.RIGHT_FLAG) != 0) {
            if ((inputMap & ControllerPacket.RIGHT_FLAG) != 0) {
                conn.sendMouseHScroll((byte) 1);
            }
        }
        if ((changedMask & ControllerPacket.LEFT_FLAG) != 0) {
            if ((inputMap & ControllerPacket.LEFT_FLAG) != 0) {
                conn.sendMouseHScroll((byte) -1);
            }
        }

        conn.sendControllerInput(controllerNumber, activeControllerMask,
                (short) 0, (byte) 0, (byte) 0, (short) 0, (short) 0, (short) 0, (short) 0);
    }
}
