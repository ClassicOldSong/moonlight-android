package com.limelight.binding.input.driver;

import com.limelight.nvstream.input.ControllerPacket;
import com.limelight.nvstream.jni.MoonBridge;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

// Shared protocol for the 2026 Steam Controller ("Triton"), whatever the transport.
// Ported from SDL: SDL_hidapi_steam_triton.c and steam/controller_structs.h.
public abstract class SteamTritonController extends AbstractController {
    public static final int VALVE_VID = 0x28de;

    // Input report IDs
    static final int ID_STATE = 0x42;
    static final int ID_STATE_BLE = 0x45;
    static final int ID_STATE_TIMESTAMP = 0x47;
    static final int ID_WIRELESS_STATUS_X = 0x46;
    static final int ID_WIRELESS_STATUS = 0x79;
    static final int WIRELESS_DISCONNECT = 1;

    static final int ID_OUT_RUMBLE = 0x80;

    // Feature report body (after the report ID byte): ID_SET_SETTINGS_VALUES with
    // SETTING_LIZARD_MODE(9)=OFF and SETTING_IMU_MODE(48)=RAW_ACCEL|RAW_GYRO.
    // The controller re-enables lizard mode on its own if this isn't resent within a few
    // seconds, so it goes back to mouse/keyboard mode once streaming stops.
    static final byte[] SETTINGS = new byte[62];
    static {
        byte[] s = {(byte) 0x87, 6, 9, 0, 0, 48, 0x18, 0};
        System.arraycopy(s, 0, SETTINGS, 0, s.length);
    }
    static final int SETTINGS_INTERVAL_MS = 3000;
    static final int RUMBLE_RESEND_MS = 40; // hardware rumble safety timeout is ~50ms

    private static final float GYRO_SCALE = 2000.0f / 32768.0f;          // -> deg/s
    private static final float ACCEL_SCALE = 2.0f * 9.80665f / 32768.0f; // -> m/s^2

    protected volatile short lowFreqMotor, highFreqMotor;

    protected SteamTritonController(int deviceId, UsbDriverListener listener, int productId) {
        super(deviceId, listener, VALVE_VID, productId);
        this.type = MoonBridge.LI_CTYPE_XBOX;
        this.capabilities = MoonBridge.LI_CCAP_ANALOG_TRIGGERS | MoonBridge.LI_CCAP_RUMBLE |
                MoonBridge.LI_CCAP_GYRO | MoonBridge.LI_CCAP_ACCEL;
        this.supportedButtonFlags = ControllerPacket.A_FLAG | ControllerPacket.B_FLAG |
                ControllerPacket.X_FLAG | ControllerPacket.Y_FLAG |
                ControllerPacket.UP_FLAG | ControllerPacket.DOWN_FLAG |
                ControllerPacket.LEFT_FLAG | ControllerPacket.RIGHT_FLAG |
                ControllerPacket.LB_FLAG | ControllerPacket.RB_FLAG |
                ControllerPacket.PLAY_FLAG | ControllerPacket.BACK_FLAG |
                ControllerPacket.LS_CLK_FLAG | ControllerPacket.RS_CLK_FLAG |
                ControllerPacket.SPECIAL_BUTTON_FLAG | ControllerPacket.MISC_FLAG |
                ControllerPacket.PADDLE1_FLAG | ControllerPacket.PADDLE2_FLAG |
                ControllerPacket.PADDLE3_FLAG | ControllerPacket.PADDLE4_FLAG;
    }

    @Override
    public void rumbleTriggers(short leftTrigger, short rightTrigger) {
        // No trigger motors
    }

    // MsgHapticRumble without the report ID: type, intensity(2), left speed(2), left gain, right speed(2), right gain
    protected byte[] rumbleBody() {
        int low = lowFreqMotor & 0xFFFF, high = highFreqMotor & 0xFFFF;
        return new byte[] {0, 0, 0, (byte) low, (byte) (low >> 8), 0, (byte) high, (byte) (high >> 8), 0};
    }

    // Parses a TritonMTUNoQuat_t (0x42/0x45) or TritonMTUNoQuat32TS_t (0x47) starting at off.
    // Both layouts share every offset used here.
    protected void handleState(byte[] data, int off, int len) {
        if (len < 17) {
            return;
        }
        ByteBuffer b = ByteBuffer.wrap(data, off, len).slice().order(ByteOrder.LITTLE_ENDIAN);
        int buttons = b.getInt(1);

        setButtonFlag(ControllerPacket.A_FLAG, buttons & 0x00000001);
        setButtonFlag(ControllerPacket.B_FLAG, buttons & 0x00000002);
        setButtonFlag(ControllerPacket.X_FLAG, buttons & 0x00000004);
        setButtonFlag(ControllerPacket.Y_FLAG, buttons & 0x00000008);
        setButtonFlag(ControllerPacket.MISC_FLAG, buttons & 0x00000010); // QAM (...)
        setButtonFlag(ControllerPacket.RS_CLK_FLAG, buttons & 0x00000020);
        setButtonFlag(ControllerPacket.PLAY_FLAG, buttons & 0x00000040); // SDL maps this bit to Start
        setButtonFlag(ControllerPacket.PADDLE1_FLAG, buttons & 0x00000080); // R4
        setButtonFlag(ControllerPacket.PADDLE3_FLAG, buttons & 0x00000100); // R5
        setButtonFlag(ControllerPacket.RB_FLAG, buttons & 0x00000200);
        setButtonFlag(ControllerPacket.DOWN_FLAG, buttons & 0x00000400);
        setButtonFlag(ControllerPacket.RIGHT_FLAG, buttons & 0x00000800);
        setButtonFlag(ControllerPacket.LEFT_FLAG, buttons & 0x00001000);
        setButtonFlag(ControllerPacket.UP_FLAG, buttons & 0x00002000);
        setButtonFlag(ControllerPacket.BACK_FLAG, buttons & 0x00004000); // SDL maps this bit to Back
        setButtonFlag(ControllerPacket.LS_CLK_FLAG, buttons & 0x00008000);
        setButtonFlag(ControllerPacket.SPECIAL_BUTTON_FLAG, buttons & 0x00010000); // Steam
        setButtonFlag(ControllerPacket.PADDLE2_FLAG, buttons & 0x00020000); // L4
        setButtonFlag(ControllerPacket.PADDLE4_FLAG, buttons & 0x00040000); // L5
        setButtonFlag(ControllerPacket.LB_FLAG, buttons & 0x00080000);

        leftTrigger = Math.max(0, b.getShort(5)) / 32767.0f;
        rightTrigger = Math.max(0, b.getShort(7)) / 32767.0f;
        // Controller reports Y up-positive; Moonlight drivers report down-positive
        leftStickX = b.getShort(9) / 32767.0f;
        leftStickY = -b.getShort(11) / 32767.0f;
        rightStickX = b.getShort(13) / 32767.0f;
        rightStickY = -b.getShort(15) / 32767.0f;
        reportInput();

        if (len >= 45) {
            // Same axis remap SDL applies, which is the convention Moonlight hosts expect
            accelX = b.getShort(33) * ACCEL_SCALE;
            accelY = b.getShort(37) * ACCEL_SCALE;
            accelZ = -b.getShort(35) * ACCEL_SCALE;
            gyroX = b.getShort(39) * GYRO_SCALE;
            gyroY = b.getShort(43) * GYRO_SCALE;
            gyroZ = -b.getShort(41) * GYRO_SCALE;
            reportMotion();
        }
    }

    // Controller went out of range or to sleep: release everything so nothing sticks held
    protected void reportNeutral() {
        buttonFlags = 0;
        leftTrigger = rightTrigger = 0;
        leftStickX = leftStickY = rightStickX = rightStickY = 0;
        reportInput();
    }
}
