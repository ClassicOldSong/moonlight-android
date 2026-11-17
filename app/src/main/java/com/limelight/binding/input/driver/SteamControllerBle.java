package com.limelight.binding.input.driver;

import android.annotation.SuppressLint;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothProfile;
import android.content.Context;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;

import com.limelight.LimeLog;
import com.limelight.nvstream.input.ControllerPacket;
import com.limelight.nvstream.jni.MoonBridge;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayDeque;
import java.util.UUID;

// Steam Controller (2026, "Triton") over Bluetooth LE, talking to Valve's GATT service directly.
// Protocol ported from SDL: SDL_hidapi_steam_triton.c and HIDDeviceBLESteamController.java.
@SuppressLint("MissingPermission") // UsbDriverService only creates us once BLUETOOTH_CONNECT is granted
public class SteamControllerBle extends AbstractController {
    public static final int VALVE_VID = 0x28de;
    private static final int TRITON_BLE_PID = 0x1303;

    private static final UUID SERVICE = UUID.fromString("100F6C32-1735-4313-B402-38567131E5F3");
    private static final UUID INPUT_0x45 = UUID.fromString("100F6C7A-1735-4313-B402-38567131E5F3");
    private static final UUID INPUT_0x47 = UUID.fromString("100F6C7C-1735-4313-B402-38567131E5F3");
    private static final UUID FEATURE_REPORT = UUID.fromString("100F6C34-1735-4313-B402-38567131E5F3");
    // Output report N lives at characteristic 100F6C(N + 0x35); rumble is report 0x80
    private static final UUID RUMBLE_REPORT = UUID.fromString("100F6CB5-1735-4313-B402-38567131E5F3");
    private static final UUID CCCD = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");

    // ID_SET_SETTINGS_VALUES: SETTING_LIZARD_MODE(9)=OFF, SETTING_IMU_MODE(48)=RAW_ACCEL|RAW_GYRO.
    // The controller re-enables lizard mode on its own if this isn't resent within a few seconds,
    // so it goes back to TV-navigation mode once streaming stops.
    private static final byte[] SETTINGS = new byte[62];
    static {
        byte[] s = {(byte) 0x87, 6, 9, 0, 0, 48, 0x18, 0};
        System.arraycopy(s, 0, SETTINGS, 0, s.length);
    }

    private static final int SETTINGS_INTERVAL_MS = 3000;
    private static final int RUMBLE_RESEND_MS = 40; // hardware rumble safety timeout is ~50ms
    private static final int GATT_OP_TIMEOUT_MS = 1000;

    private static final float GYRO_SCALE = 2000.0f / 32768.0f;          // -> deg/s
    private static final float ACCEL_SCALE = 2.0f * 9.80665f / 32768.0f; // -> m/s^2

    private final Context context;
    private final BluetoothDevice device;
    private final Handler handler = new Handler(Looper.getMainLooper());

    private interface GattOp { boolean start(); }
    private final ArrayDeque<GattOp> ops = new ArrayDeque<>();
    private boolean opInFlight, rumbleQueued;

    private BluetoothGatt gatt;
    private BluetoothGattCharacteristic featureChr, rumbleChr;
    private volatile boolean ready, stopped;
    private volatile short lowFreqMotor, highFreqMotor;

    public static boolean isSteamController(BluetoothDevice dev) {
        // Same match SDL uses: Triton advertises as "Steam Ctrl ..."
        String name = dev.getName();
        return name != null && name.startsWith("Steam Ctrl") &&
                (dev.getType() & BluetoothDevice.DEVICE_TYPE_LE) != 0;
    }

    public SteamControllerBle(Context context, BluetoothDevice device, int deviceId, UsbDriverListener listener) {
        super(deviceId, listener, VALVE_VID, TRITON_BLE_PID);
        this.context = context;
        this.device = device;
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

    public String getAddress() {
        return device.getAddress();
    }

    @Override
    public boolean start() {
        LimeLog.info("Steam Controller BLE: connecting to " + device.getAddress());
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            gatt = device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE);
        }
        else {
            gatt = device.connectGatt(context, false, callback);
        }
        return gatt != null;
    }

    // Main thread only (UsbDriverService calls it there, and we post our own calls)
    @Override
    public void stop() {
        synchronized (this) {
            if (stopped) {
                return;
            }
            stopped = true;
            ops.clear();
        }
        handler.removeCallbacksAndMessages(null);
        if (gatt != null) {
            gatt.disconnect();
            gatt.close();
        }
        notifyDeviceRemoved();
    }

    @Override
    public void rumble(short lowFreqMotor, short highFreqMotor) {
        this.lowFreqMotor = lowFreqMotor;
        this.highFreqMotor = highFreqMotor;
        handler.removeCallbacks(rumbleRefresh);
        handler.post(rumbleRefresh);
    }

    @Override
    public void rumbleTriggers(short leftTrigger, short rightTrigger) {
        // No trigger motors
    }

    private final Runnable rumbleRefresh = new Runnable() {
        @Override
        public void run() {
            queueRumble();
            if (lowFreqMotor != 0 || highFreqMotor != 0) {
                handler.postDelayed(this, RUMBLE_RESEND_MS);
            }
        }
    };

    private final Runnable maintainSettings = new Runnable() {
        @Override
        public void run() {
            enqueue(() -> {
                featureChr.setValue(SETTINGS);
                return gatt.writeCharacteristic(featureChr);
            });
            handler.postDelayed(this, SETTINGS_INTERVAL_MS);
        }
    };

    private void onReady() {
        if (ready || stopped) {
            return;
        }
        ready = true;
        LimeLog.info("Steam Controller BLE: ready");
        handler.post(maintainSettings);
        notifyDeviceAdded();
    }

    private void queueRumble() {
        synchronized (this) {
            if (rumbleQueued || rumbleChr == null) {
                return;
            }
            rumbleQueued = true;
        }
        enqueue(() -> {
            synchronized (this) {
                rumbleQueued = false;
            }
            int low = lowFreqMotor & 0xFFFF, high = highFreqMotor & 0xFFFF;
            // MsgHapticRumble minus report ID: type, intensity(2), left speed(2), left gain, right speed(2).
            // SDL's BLE path drops the trailing right gain byte too, so match it.
            rumbleChr.setValue(new byte[] {0, 0, 0, (byte) low, (byte) (low >> 8), 0, (byte) high, (byte) (high >> 8)});
            return gatt.writeCharacteristic(rumbleChr);
        });
    }

    // Android allows one outstanding GATT operation at a time, so everything goes through here.
    private synchronized void enqueue(GattOp op) {
        ops.add(op);
        pump();
    }

    private synchronized void opDone() {
        handler.removeCallbacks(opTimeout);
        opInFlight = false;
        pump();
    }

    // ponytail: some stacks (Fire TV) silently drop callbacks; time out so the queue never wedges.
    // A late callback after a timeout can complete the next op early, which is harmless here.
    private final Runnable opTimeout = this::opDone;

    private synchronized void pump() {
        while (!opInFlight && !stopped && !ops.isEmpty()) {
            opInFlight = ops.poll().start();
            if (opInFlight) {
                handler.postDelayed(opTimeout, GATT_OP_TIMEOUT_MS);
            }
        }
    }

    void handleInput(byte[] data) { // package-private for SteamControllerBleTest
        // TritonMTUNoQuat_t (0x45) / TritonMTUNoQuat32TS_t (0x47) share these offsets
        if (data == null || data.length < 17) {
            return;
        }
        ByteBuffer b = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
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

        if (data.length >= 45) {
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

    private final BluetoothGattCallback callback = new BluetoothGattCallback() {
        @Override
        public void onConnectionStateChange(BluetoothGatt g, int status, int newState) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                handler.post(() -> {
                    if (!stopped) {
                        g.discoverServices();
                    }
                });
            }
            else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                LimeLog.info("Steam Controller BLE: disconnected (status " + status + ")");
                handler.post(SteamControllerBle.this::stop);
            }
        }

        @Override
        public void onServicesDiscovered(BluetoothGatt g, int status) {
            BluetoothGattService svc = g.getService(SERVICE);
            BluetoothGattCharacteristic input = null;
            if (svc != null) {
                input = svc.getCharacteristic(INPUT_0x45);
                if (input == null) {
                    input = svc.getCharacteristic(INPUT_0x47);
                }
                featureChr = svc.getCharacteristic(FEATURE_REPORT);
                rumbleChr = svc.getCharacteristic(RUMBLE_REPORT);
            }
            if (status != BluetoothGatt.GATT_SUCCESS || input == null || featureChr == null) {
                LimeLog.warning("Steam Controller BLE: Valve service not found (status " + status + ")");
                handler.post(SteamControllerBle.this::stop);
                return;
            }

            // Triton needs Data Length Extensions; 517 is the value that makes Android enable them
            enqueue(() -> g.requestMtu(517));

            final BluetoothGattCharacteristic inputChr = input;
            enqueue(() -> {
                BluetoothGattDescriptor cccd = inputChr.getDescriptor(CCCD);
                if (cccd == null || !g.setCharacteristicNotification(inputChr, true)) {
                    return false;
                }
                cccd.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
                return g.writeDescriptor(cccd);
            });
        }

        @Override
        public void onMtuChanged(BluetoothGatt g, int mtu, int status) {
            opDone();
        }

        @Override
        public void onDescriptorWrite(BluetoothGatt g, BluetoothGattDescriptor descriptor, int status) {
            opDone();
            handler.post(SteamControllerBle.this::onReady);
        }

        @Override
        public void onCharacteristicWrite(BluetoothGatt g, BluetoothGattCharacteristic characteristic, int status) {
            opDone();
        }

        @Override
        public void onCharacteristicChanged(BluetoothGatt g, BluetoothGattCharacteristic characteristic) {
            if (!ready) {
                // Some stacks never deliver onDescriptorWrite even though notifications are flowing
                handler.post(SteamControllerBle.this::onReady);
                return;
            }
            handleInput(characteristic.getValue());
        }
    };
}
