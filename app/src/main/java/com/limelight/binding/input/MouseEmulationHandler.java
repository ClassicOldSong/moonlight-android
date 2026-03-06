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

    private static final int MOUSE_EMULATION_TICK_RATE_HZ = 120; // Fluid & responsive cursor emulation, matches modern screens refresh rate
    private static final float RAW_STICK_AXIS_MAX = 32766.0f; // Limit is Short.MAX_VALUE - 1
    private static final float MOUSE_MOVE_BASE_SPEED_PX_PER_S = 1200.0f; // Base cursor speed with max analog stick deflection

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
    private long lastTickTimeNs;
    private float mouseMoveAccumX, mouseMoveAccumY;
    private float scrollAccumX, scrollAccumY;
    private final Vector2d moveVector = new Vector2d();
    private final Vector2d scrollVector = new Vector2d();

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

            long now = System.nanoTime();
            float deltaTimeSec = (now - lastTickTimeNs) / 1_000_000_000.0f;
            lastTickTimeNs = now;

            short leftStickX = stickProvider.getLeftStickX();
            short leftStickY = stickProvider.getLeftStickY();
            short rightStickX = stickProvider.getRightStickX();
            short rightStickY = stickProvider.getRightStickY();

            switch (prefConfig.analogStickForScrolling) {
            case RIGHT:
                sendEmulatedMouseMove(leftStickX, leftStickY, deltaTimeSec);
                sendEmulatedMouseScroll(rightStickX, rightStickY, deltaTimeSec);
            break;
            case LEFT:
                sendEmulatedMouseMove(rightStickX, rightStickY, deltaTimeSec);
                sendEmulatedMouseScroll(leftStickX, leftStickY, deltaTimeSec);
            break;
            case NONE:
            default:
                sendEmulatedMouseMove(leftStickX, leftStickY, deltaTimeSec);
                sendEmulatedMouseMove(rightStickX, rightStickY, deltaTimeSec);
            break;
            }

            handler.postDelayed(this, 1000 / MOUSE_EMULATION_TICK_RATE_HZ);
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
            lastTickTimeNs = System.nanoTime();
            handler.postDelayed(tickRunnable, 1000 / MOUSE_EMULATION_TICK_RATE_HZ);
        }
        else {
            mouseMoveAccumX = mouseMoveAccumY = scrollAccumX = scrollAccumY = 0;
        }
    }

    public void destroy() {
        active = false;
        handler.removeCallbacks(tickRunnable);
        mouseMoveAccumX = mouseMoveAccumY = scrollAccumX = scrollAccumY = 0;
    }

    public List<GameMenu.MenuOption> getMenuOptions() {
        List<GameMenu.MenuOption> options = new ArrayList<>();
        options.add(new GameMenu.MenuOption(activityContext.getString(active ?
                R.string.game_menu_toggle_mouse_off : R.string.game_menu_toggle_mouse_on),
                true, this::toggle));
        return options;
    }

    private void convertRawStickAxisToSpeedPxPerSec(short stickX, short stickY, Vector2d out) {
        out.initialize(stickX, stickY);
        double normalizedMag = out.getMagnitude() / RAW_STICK_AXIS_MAX;
        if (normalizedMag > 0) {
            // Cubic response curve: speed = MAX_SPEED * (deflection / max)^3 in px/s
            // Fine precision at low deflections, fast movement at full tilt
            double targetSpeed = MOUSE_MOVE_BASE_SPEED_PX_PER_S * normalizedMag * normalizedMag * normalizedMag;
            out.scalarMultiply(targetSpeed / out.getMagnitude());
        }
    }

    private void sendEmulatedMouseMove(short x, short y, float deltaTimeSec) {
        convertRawStickAxisToSpeedPxPerSec(x, y, moveVector);
        moveVector.scalarMultiply(deltaTimeSec);  // px/s * s = px
        moveVector.scalarMultiply(prefConfig.mouseEmulationSensitivity / 100.0f); // user sensitivity

        // Accumulate fractional pixels across ticks: sub-pixel deltas that would
        // be lost by the short cast are carried over and compound until they reach 1 px
        mouseMoveAccumX += moveVector.getX();
        mouseMoveAccumY += moveVector.getY();
        short dx = (short) mouseMoveAccumX;
        short dy = (short) mouseMoveAccumY;

        if (dx != 0 || dy != 0) {
            conn.sendMouseMove(dx, (short) -dy);

            mouseMoveAccumX -= dx;
            mouseMoveAccumY -= dy;
        }
    }

    private void sendEmulatedMouseScroll(short x, short y, float deltaTimeSec) {
        convertRawStickAxisToSpeedPxPerSec(x, y, scrollVector);
        scrollVector.scalarMultiply(deltaTimeSec);  // px/s * s = px

        // Same fractional accumulation as mouse move, applied per scroll axis independently
        scrollAccumX += scrollVector.getX();
        scrollAccumY += scrollVector.getY();
        short sx = (short) scrollAccumX;
        short sy = (short) scrollAccumY;

        if (sy != 0) {
            conn.sendMouseHighResScroll(sy);

            scrollAccumY -= sy;
        }
        if (sx != 0) {
            conn.sendMouseHighResHScroll(sx);

            scrollAccumX -= sx;
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
