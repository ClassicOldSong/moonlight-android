package com.limelight;

import android.app.Service;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.text.TextUtils;
import android.util.SparseArray;
import android.util.TypedValue;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import com.limelight.binding.input.ControllerHandler;
import com.limelight.binding.input.driver.SteamTritonController;
import com.limelight.binding.input.driver.UsbDriverService;
import com.limelight.nvstream.NvConnection;
import com.limelight.nvstream.input.ControllerPacket;
import com.limelight.nvstream.jni.MoonBridge;
import com.limelight.preferences.PreferenceConfiguration;

import java.util.ArrayDeque;
import java.util.Locale;

// Controller tester: runs the real ControllerHandler (and the USB/Bluetooth drivers) against a
// connection that captures packets instead of sending them, so what's drawn here is exactly
// what a host would receive with the current settings.
public class ControllerTestActivity extends AppCompatActivity {
    private static final int LOG_LINES = 10;
    private static final int COLOR_ON = 0xFF4CAF50;
    private static final int COLOR_LINE = 0xFFE0E0E0;

    static final class Pad {
        boolean arrived;
        byte type;
        int supported;
        short caps;
        int buttons;
        float lt, rt, lx, ly, rx, ry;
        final float[] accel = new float[3], gyro = new float[3];
        final SparseArray<float[]> touches = new SparseArray<>(); // pointer id -> x, y, pressure
    }

    private final Pad[] pads = new Pad[16]; // by controller number; also the lock for all of them
    private volatile int activePad;
    private volatile short activeMask;
    private volatile String rawAxes = "";
    private volatile String steamRaw = "";
    private final ArrayDeque<String> log = new ArrayDeque<>();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private ControllerHandler controllerHandler;
    private PadView padView;
    private TextView logView;
    private boolean boundUsb;
    private UsbDriverService.UsbDriverBinder usbBinder;

    private final ServiceConnection usbConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            UsbDriverService.UsbDriverBinder binder = (UsbDriverService.UsbDriverBinder) service;
            usbBinder = binder;
            binder.setListener(controllerHandler);
            binder.start();
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {}
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setTitle(R.string.title_controller_test);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        for (int i = 0; i < pads.length; i++) {
            pads[i] = new Pad();
        }

        padView = new PadView(this);
        logView = new TextView(this);
        logView.setTypeface(Typeface.MONOSPACE);
        logView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        logView.setLines(LOG_LINES);
        logView.setPadding(16, 8, 16, 8);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.addView(padView, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1));
        root.addView(logView, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        setContentView(root);

        PreferenceConfiguration prefConfig = PreferenceConfiguration.readPreferences(this);
        controllerHandler = new ControllerHandler(this, new CaptureConnection(this), () -> {}, prefConfig);

        SteamTritonController.rawListener = (buttons, lpx, lpy, lpp, rpx, rpy, rpp) -> {
            steamRaw = String.format(Locale.US, "Steam raw: buttons %08x  L pad %d,%d p%d  R pad %d,%d p%d",
                    buttons, lpx, lpy, lpp, rpx, rpy, rpp);
            padView.postInvalidate();
        };

        if (prefConfig.usbDriver) {
            boundUsb = bindService(new Intent(this, UsbDriverService.class), usbConnection, Service.BIND_AUTO_CREATE);
        }

        addLog(getString(R.string.controller_test_hint));

        // Tap to rumble the controller shown, through the same path a host's rumble takes
        padView.setOnClickListener(v -> {
            short n = (short) activePad;
            controllerHandler.handleRumble(n, (short) 0xFFFF, (short) 0xFFFF);
            mainHandler.postDelayed(() -> controllerHandler.handleRumble(n, (short) 0, (short) 0), 500);
            addLog("#" + n + " rumble");
        });
    }

    @Override
    protected void onDestroy() {
        SteamTritonController.rawListener = null;
        if (usbBinder != null) {
            usbBinder.setListener(null); // back to driving the menus
        }
        controllerHandler.destroy();
        if (boundUsb) {
            unbindService(usbConnection);
        }
        super.onDestroy();
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (!ControllerHandler.isGameControllerDevice(event.getDevice())) {
            return super.dispatchKeyEvent(event);
        }

        boolean handled;
        if (event.getAction() == KeyEvent.ACTION_DOWN) {
            handled = controllerHandler.handleButtonDown(event);
        } else if (event.getAction() == KeyEvent.ACTION_UP) {
            handled = controllerHandler.handleButtonUp(event);
        } else {
            handled = false;
        }

        if (event.getRepeatCount() == 0 && event.getAction() != KeyEvent.ACTION_MULTIPLE) {
            addLog(String.format(Locale.US, "%s %s [%s]%s",
                    KeyEvent.keyCodeToString(event.getKeyCode()),
                    event.getAction() == KeyEvent.ACTION_DOWN ? "down" : "up",
                    event.getDevice() != null ? event.getDevice().getName() : "?",
                    handled ? "" : " (unmapped)"));
        }
        return handled || super.dispatchKeyEvent(event);
    }

    @Override
    public boolean dispatchGenericMotionEvent(MotionEvent event) {
        InputDevice dev = event.getDevice();
        if ((event.getSource() & InputDevice.SOURCE_CLASS_JOYSTICK) != 0) {
            if (dev != null) {
                rawAxes = describeAxes(dev, event);
                padView.invalidate();
            }
            if (controllerHandler.handleMotionEvent(event)) {
                return true;
            }
        } else if (dev != null && (dev.getSources() & InputDevice.SOURCE_CLASS_JOYSTICK) != 0 &&
                controllerHandler.tryHandleTouchpadEvent(event)) {
            return true;
        }
        return super.dispatchGenericMotionEvent(event);
    }

    private static String describeAxes(InputDevice dev, MotionEvent event) {
        StringBuilder sb = new StringBuilder("Android axes:");
        for (InputDevice.MotionRange range : dev.getMotionRanges()) {
            if ((range.getSource() & InputDevice.SOURCE_CLASS_JOYSTICK) == 0) {
                continue;
            }
            float v = event.getAxisValue(range.getAxis());
            if (Math.abs(v) > 0.05f) {
                sb.append(' ').append(MotionEvent.axisToString(range.getAxis()).replace("AXIS_", ""))
                        .append('=').append(String.format(Locale.US, "%.2f", v));
            }
        }
        return sb.toString();
    }

    private void addLog(String line) {
        runOnUiThread(() -> {
            if (log.size() == LOG_LINES) {
                log.removeFirst();
            }
            log.addLast(line);
            logView.setText(TextUtils.join("\n", log));
        });
    }

    static String buttonName(int flag) {
        switch (flag) {
            case ControllerPacket.A_FLAG: return "A";
            case ControllerPacket.B_FLAG: return "B";
            case ControllerPacket.X_FLAG: return "X";
            case ControllerPacket.Y_FLAG: return "Y";
            case ControllerPacket.UP_FLAG: return "Up";
            case ControllerPacket.DOWN_FLAG: return "Down";
            case ControllerPacket.LEFT_FLAG: return "Left";
            case ControllerPacket.RIGHT_FLAG: return "Right";
            case ControllerPacket.LB_FLAG: return "LB";
            case ControllerPacket.RB_FLAG: return "RB";
            case ControllerPacket.PLAY_FLAG: return "Start";
            case ControllerPacket.BACK_FLAG: return "Back";
            case ControllerPacket.LS_CLK_FLAG: return "LS";
            case ControllerPacket.RS_CLK_FLAG: return "RS";
            case ControllerPacket.SPECIAL_BUTTON_FLAG: return "Guide";
            case ControllerPacket.PADDLE1_FLAG: return "Paddle1";
            case ControllerPacket.PADDLE2_FLAG: return "Paddle2";
            case ControllerPacket.PADDLE3_FLAG: return "Paddle3";
            case ControllerPacket.PADDLE4_FLAG: return "Paddle4";
            case ControllerPacket.TOUCHPAD_FLAG: return "Touchpad";
            case ControllerPacket.MISC_FLAG: return "Misc";
            default: return String.format("0x%x", flag);
        }
    }

    // "+A -B" for each bit that changed
    static String describeChange(int buttons, int changed) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 32; i++) {
            int flag = 1 << i;
            if ((changed & flag) != 0) {
                sb.append((buttons & flag) != 0 ? " +" : " -").append(buttonName(flag));
            }
        }
        return sb.toString();
    }

    private static String typeName(byte type) {
        switch (type) {
            case MoonBridge.LI_CTYPE_XBOX: return "Xbox";
            case MoonBridge.LI_CTYPE_PS: return "PlayStation";
            case MoonBridge.LI_CTYPE_NINTENDO: return "Nintendo";
            default: return "Unknown type";
        }
    }

    private static String capsNames(short caps) {
        String[] names = {"analog triggers", "rumble", "trigger rumble", "touchpad", "accel", "gyro", "battery", "RGB LED"};
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < names.length; i++) {
            if ((caps & (1 << i)) != 0) {
                sb.append(sb.length() == 0 ? "" : ", ").append(names[i]);
            }
        }
        return sb.length() == 0 ? "none" : sb.toString();
    }

    private class CaptureConnection extends NvConnection {
        CaptureConnection(Context context) {
            super(context, null, 0, null, null, null, null);
        }

        @Override
        public void sendControllerInput(short controllerNumber, short activeGamepadMask, int buttonFlags,
                                        byte leftTrigger, byte rightTrigger,
                                        short leftStickX, short leftStickY,
                                        short rightStickX, short rightStickY) {
            int changed;
            synchronized (pads) {
                Pad p = pads[controllerNumber & 15];
                changed = p.buttons ^ buttonFlags;
                p.buttons = buttonFlags;
                p.lt = (leftTrigger & 0xFF) / 255f;
                p.rt = (rightTrigger & 0xFF) / 255f;
                p.lx = leftStickX / 32767f;
                p.ly = leftStickY / 32767f;
                p.rx = rightStickX / 32767f;
                p.ry = rightStickY / 32767f;
            }
            activePad = controllerNumber & 15;
            activeMask = activeGamepadMask;
            if (changed != 0) {
                addLog("#" + controllerNumber + describeChange(buttonFlags, changed));
            }
            padView.postInvalidate();
        }

        @Override
        public int sendControllerArrivalEvent(byte controllerNumber, short activeGamepadMask, byte type,
                                              int supportedButtonFlags, short capabilities) {
            synchronized (pads) {
                Pad p = pads[controllerNumber & 15];
                p.arrived = true;
                p.type = type;
                p.supported = supportedButtonFlags;
                p.caps = capabilities;
            }
            activeMask = activeGamepadMask;
            addLog(String.format(Locale.US, "#%d arrived: %s, caps: %s", controllerNumber, typeName(type), capsNames(capabilities)));

            // Ask for motion data the way a host would
            mainHandler.post(() -> {
                controllerHandler.handleSetMotionEventState(controllerNumber, MoonBridge.LI_MOTION_TYPE_ACCEL, (short) 100);
                controllerHandler.handleSetMotionEventState(controllerNumber, MoonBridge.LI_MOTION_TYPE_GYRO, (short) 100);
            });
            return 0;
        }

        @Override
        public int sendControllerTouchEvent(byte controllerNumber, byte eventType, int pointerId,
                                            float x, float y, float pressure) {
            synchronized (pads) {
                SparseArray<float[]> touches = pads[controllerNumber & 15].touches;
                switch (eventType) {
                    case MoonBridge.LI_TOUCH_EVENT_DOWN:
                    case MoonBridge.LI_TOUCH_EVENT_MOVE:
                    case MoonBridge.LI_TOUCH_EVENT_HOVER:
                        touches.put(pointerId, new float[] {x, y, pressure});
                        break;
                    case MoonBridge.LI_TOUCH_EVENT_UP:
                    case MoonBridge.LI_TOUCH_EVENT_CANCEL:
                    case MoonBridge.LI_TOUCH_EVENT_HOVER_LEAVE:
                        touches.remove(pointerId);
                        break;
                    case MoonBridge.LI_TOUCH_EVENT_CANCEL_ALL:
                        touches.clear();
                        break;
                }
            }
            if (eventType == MoonBridge.LI_TOUCH_EVENT_DOWN || eventType == MoonBridge.LI_TOUCH_EVENT_UP) {
                addLog(String.format(Locale.US, "#%d touch %d %s at %.2f,%.2f", controllerNumber, pointerId,
                        eventType == MoonBridge.LI_TOUCH_EVENT_DOWN ? "down" : "up", x, y));
            }
            activePad = controllerNumber & 15;
            padView.postInvalidate();
            return 0;
        }

        @Override
        public int sendControllerMotionEvent(byte controllerNumber, byte motionType, float x, float y, float z) {
            synchronized (pads) {
                Pad p = pads[controllerNumber & 15];
                float[] v = motionType == MoonBridge.LI_MOTION_TYPE_ACCEL ? p.accel : p.gyro;
                v[0] = x;
                v[1] = y;
                v[2] = z;
            }
            padView.postInvalidate();
            return 0;
        }

        // Mouse emulation and battery reports: log the discrete ones, swallow the rest
        @Override public void sendControllerBatteryEvent(byte controllerNumber, byte batteryState, byte batteryPercentage) {}
        @Override public void sendMouseMove(short deltaX, short deltaY) {}
        @Override public void sendMouseButtonDown(byte mouseButton) { addLog("mouse button " + mouseButton + " down"); }
        @Override public void sendMouseButtonUp(byte mouseButton) { addLog("mouse button " + mouseButton + " up"); }
        @Override public void sendMouseScroll(byte scrollClicks) { addLog("mouse scroll " + scrollClicks); }
        @Override public void sendMouseHScroll(byte scrollClicks) { addLog("mouse hscroll " + scrollClicks); }
        @Override public void sendMouseHighResScroll(short scrollAmount) {}
        @Override public void sendMouseHighResHScroll(short scrollAmount) {}
    }

    // Draws the current packet state on a 100x62 grid laid out roughly like a gamepad
    private class PadView extends View {
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint label = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint info = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF rect = new RectF();
        private float s, ox, oy;
        private int buttons, supported;

        PadView(Context context) {
            super(context);
            stroke.setStyle(Paint.Style.STROKE);
            stroke.setStrokeWidth(3);
            label.setTextAlign(Paint.Align.CENTER);
            info.setColor(COLOR_LINE);
            info.setTextSize(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 13, getResources().getDisplayMetrics()));
            info.setTypeface(Typeface.MONOSPACE);
        }

        private float x(float gx) { return ox + gx * s; }
        private float y(float gy) { return oy + gy * s; }

        private void setColors(int flag) {
            int alpha = supported == -1 || (supported & flag) != 0 ? 255 : 60; // dim buttons the device lacks
            fill.setColor(COLOR_ON);
            fill.setAlpha(alpha);
            stroke.setColor(COLOR_LINE);
            stroke.setAlpha(alpha);
            label.setColor(COLOR_LINE);
            label.setAlpha(alpha);
            label.setTextSize(2.4f * s);
        }

        private void text(Canvas c, String text, float gx, float gy) {
            c.drawText(text, x(gx), y(gy) - (label.descent() + label.ascent()) / 2, label);
        }

        private void button(Canvas c, String text, int flag, float gx, float gy, float r) {
            setColors(flag);
            if ((buttons & flag) != 0) {
                c.drawCircle(x(gx), y(gy), r * s, fill);
            }
            c.drawCircle(x(gx), y(gy), r * s, stroke);
            text(c, text, gx, gy);
        }

        private void bumper(Canvas c, String text, int flag, float gx, float gy) {
            setColors(flag);
            rect.set(x(gx), y(gy), x(gx + 22), y(gy + 5));
            if ((buttons & flag) != 0) {
                c.drawRoundRect(rect, s, s, fill);
            }
            c.drawRoundRect(rect, s, s, stroke);
            text(c, text, gx + 11, gy + 2.5f);
        }

        private void trigger(Canvas c, String text, float value, float gx, float gy) {
            setColors(-1);
            rect.set(x(gx), y(gy), x(gx + 22 * value), y(gy + 5));
            c.drawRect(rect, fill);
            rect.set(x(gx), y(gy), x(gx + 22), y(gy + 5));
            c.drawRect(rect, stroke);
            text(c, String.format(Locale.US, "%s %d%%", text, Math.round(value * 100)), gx + 11, gy + 2.5f);
        }

        private void stick(Canvas c, String text, int clickFlag, float sx, float sy, float gx, float gy) {
            float r = 9;
            setColors(clickFlag);
            if ((buttons & clickFlag) != 0) {
                c.drawCircle(x(gx), y(gy), r * s, fill);
            }
            c.drawCircle(x(gx), y(gy), r * s, stroke);
            c.drawLine(x(gx - r), y(gy), x(gx + r), y(gy), stroke);
            c.drawLine(x(gx), y(gy - r), x(gx), y(gy + r), stroke);
            // Packet Y is up-positive
            c.drawCircle(x(gx + sx * r), y(gy - sy * r), 1.6f * s, label);
            text(c, String.format(Locale.US, "%s %.2f,%.2f", text, sx, sy), gx, gy + r + 2.5f);
        }

        @Override
        protected void onDraw(Canvas c) {
            c.drawColor(0xFF121212);

            float lineHeight = info.getTextSize() * 1.3f;
            float top = lineHeight * 4.5f;
            s = Math.min(getWidth() / 100f, (getHeight() - top) / 62f);
            ox = (getWidth() - 100 * s) / 2;
            oy = top;

            synchronized (pads) {
                Pad p = pads[activePad];
                buttons = p.buttons;
                supported = p.arrived ? p.supported : -1;

                StringBuilder active = new StringBuilder();
                for (int i = 0; i < 16; i++) {
                    if ((activeMask & (1 << i)) != 0) {
                        active.append(" #").append(i);
                    }
                }
                String[] lines = {
                        p.arrived ? String.format(Locale.US, "Controller #%d (%s, %s)  active:%s",
                                activePad, typeName(p.type), capsNames(p.caps), active) :
                                getString(R.string.controller_test_hint),
                        rawAxes,
                        steamRaw,
                        String.format(Locale.US, "Accel %.1f %.1f %.1f m/s²   Gyro %.0f %.0f %.0f °/s",
                                p.accel[0], p.accel[1], p.accel[2], p.gyro[0], p.gyro[1], p.gyro[2]),
                };
                for (int i = 0; i < lines.length; i++) {
                    c.drawText(lines[i], 16, lineHeight * (i + 1), info);
                }

                trigger(c, "LT", p.lt, 6, 1);
                trigger(c, "RT", p.rt, 72, 1);
                bumper(c, "LB", ControllerPacket.LB_FLAG, 6, 8);
                bumper(c, "RB", ControllerPacket.RB_FLAG, 72, 8);

                stick(c, "LS", ControllerPacket.LS_CLK_FLAG, p.lx, p.ly, 18, 26);
                stick(c, "RS", ControllerPacket.RS_CLK_FLAG, p.rx, p.ry, 66, 46);

                button(c, "↑", ControllerPacket.UP_FLAG, 36, 40, 3.5f);
                button(c, "↓", ControllerPacket.DOWN_FLAG, 36, 52, 3.5f);
                button(c, "←", ControllerPacket.LEFT_FLAG, 30, 46, 3.5f);
                button(c, "→", ControllerPacket.RIGHT_FLAG, 42, 46, 3.5f);

                button(c, "Y", ControllerPacket.Y_FLAG, 82, 20, 4);
                button(c, "A", ControllerPacket.A_FLAG, 82, 34, 4);
                button(c, "X", ControllerPacket.X_FLAG, 75, 27, 4);
                button(c, "B", ControllerPacket.B_FLAG, 89, 27, 4);

                button(c, "Back", ControllerPacket.BACK_FLAG, 40, 18, 3.5f);
                button(c, "Guide", ControllerPacket.SPECIAL_BUTTON_FLAG, 50, 15, 3.5f);
                button(c, "Start", ControllerPacket.PLAY_FLAG, 60, 18, 3.5f);
                button(c, "Misc", ControllerPacket.MISC_FLAG, 50, 22, 3);

                // Touchpad: border lights for the click, dots for each finger
                setColors(ControllerPacket.TOUCHPAD_FLAG);
                rect.set(x(38), y(27), x(62), y(36));
                if ((buttons & ControllerPacket.TOUCHPAD_FLAG) != 0) {
                    c.drawRect(rect, fill);
                }
                c.drawRect(rect, stroke);
                for (int i = 0; i < p.touches.size(); i++) {
                    float[] t = p.touches.valueAt(i);
                    c.drawCircle(x(38 + 24 * t[0]), y(27 + 9 * t[1]), 1.2f * s, label);
                }

                // Steam Controller: L4=Paddle2, L5=Paddle4, R4=Paddle1, R5=Paddle3
                button(c, "P2", ControllerPacket.PADDLE2_FLAG, 10, 58, 3);
                button(c, "P4", ControllerPacket.PADDLE4_FLAG, 18, 58, 3);
                button(c, "P1", ControllerPacket.PADDLE1_FLAG, 82, 58, 3);
                button(c, "P3", ControllerPacket.PADDLE3_FLAG, 90, 58, 3);
            }
        }
    }
}
