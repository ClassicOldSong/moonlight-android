package com.limelight.binding.input.driver;

import android.Manifest;
import android.bluetooth.*;
import android.content.Context;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;

import android.util.Log;
import android.widget.Toast;
import androidx.annotation.Nullable;
import androidx.annotation.RequiresPermission;
import com.limelight.LimeLog;
import com.limelight.R;
import com.limelight.nvstream.input.ControllerPacket;
import com.limelight.nvstream.jni.MoonBridge;
import com.limelight.preferences.PreferenceConfiguration;
import org.bouncycastle.util.encoders.Hex;
import org.jspecify.annotations.NonNull;

import java.io.Closeable;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static android.bluetooth.BluetoothDevice.TRANSPORT_LE;
import static android.util.Log.VERBOSE;

/**
 * The methods in this class are inspired by SDL's Steam Controller HID device:
 * <a href="https://github.com/libsdl-org/SDL/blob/bbcc205de97a1b3e53257af2a77f5f5d3d59c4f8/android-project/app/src/main/java/org/libsdl/app/HIDDeviceBLESteamController.java">HIDDeviceBLESteamController.java</a>
 *  <p>
 * Additional resources:
 * <ul>
 * <li><a href="https://github.com/torvalds/linux/blob/master/drivers/hid/hid-steam.c">HID driver for Valve Steam Controller</a>
 * <li><a href="https://github.com/rodrigorc/steamctrl/blob/master/src/steamctrl.c">steamctrl: a utility to setup Valve Steam Controller</a>
 * </ul>
 */
public class SteamController extends AbstractController {

    private static final String TAG = "steamcontroller";

    private static final int BLEButtonChunk1 = 0x10;
    private static final int BLEButtonChunk2 = 0x20;
    private static final int BLEButtonChunk3 = 0x40;
    private static final int BLELeftJoystickChunk = 0x80;
    private static final int BLELeftTrackpadChunk = 0x100;
    private static final int BLERightTrackpadChunk = 0x200;
    private static final int BLEIMUAccelChunk = 0x400;
    private static final int BLEIMUGyroChunk = 0x800;
    private static final int BLEIMUQuatChunk = 0x1000;

    // Feature Report Command Reference: https://github.com/torvalds/linux/blob/master/drivers/hid/hid-steam.c#L85
    private static final int FEATURE_REPORT_ID = 0xC0;
    private static final int FEATURE_REPORT_ID_FRAGMENT_START = 0x80;
    private static final int FEATURE_REPORT_ID_FRAGMENT_END = 0xC1;
    private static final byte SETTING_LPAD_MODE = (byte)0x07;
    private static final byte SETTING_RPAD_MODE = (byte)0x08;
    private static final byte SETTING_RPAD_MARGIN = (byte)0x18;
    private static final byte SETTING_LED_USER_BRIGHTNESS = (byte)0x2D;
    private static final byte SETTING_GYRO_MODE = (byte)0x30;

    private static final short TRACKPAD_MODE_NONE = 0x07;

    // Accelerometer has 16 bit resolution and a range of +/- 2g
    private static final float ACCEL_RES_PER_G = 16_384.0f;
    // Gyroscope has 16 bit resolution and a range of +/- 2000 dps
    private static final float GYRO_RES_PER_DPS = 16.0f;

    // Valve Corporation
    private static final int VALVE_USB_VID = 0x28DE;

    static final UUID steamControllerService = UUID.fromString("100F6C32-1735-4313-B402-38567131E5F3");
    static final UUID inputCharacteristicD0G = UUID.fromString("100F6C33-1735-4313-B402-38567131E5F3");
    static final UUID inputCharacteristicTriton = UUID.fromString("100F6C7A-1735-4313-B402-38567131E5F3");
    static final UUID reportCharacteristic = UUID.fromString("100F6C34-1735-4313-B402-38567131E5F3");

    private final Callback mCallback;

    private int lastReportId;
    private final List<ByteBuffer> reassembledReport = new LinkedList<>();

    public SteamController(ControllerDriverListener listener, BluetoothDriverService manager, int deviceId,
                           BluetoothDevice device, PreferenceConfiguration.SteamControllerEmulation emulationMode) {
        super(deviceId, listener, VALVE_USB_VID, 0);
        type = emulationMode == PreferenceConfiguration.SteamControllerEmulation.PS5 ? MoonBridge.LI_CTYPE_PS : MoonBridge.LI_CTYPE_XBOX;
        capabilities =
                MoonBridge.LI_CCAP_ANALOG_TRIGGERS | MoonBridge.LI_CCAP_RUMBLE | MoonBridge.LI_CCAP_TRIGGER_RUMBLE |
                        MoonBridge.LI_CCAP_BATTERY_STATE;
        if (emulationMode == PreferenceConfiguration.SteamControllerEmulation.PS5) {
            capabilities |= MoonBridge.LI_CCAP_ACCEL | MoonBridge.LI_CCAP_GYRO | MoonBridge.LI_CCAP_RGB_LED;
        }
        buttonFlags =
                ControllerPacket.RS_CLK_FLAG | ControllerPacket.LS_CLK_FLAG |
                        ControllerPacket.RB_FLAG | ControllerPacket.LB_FLAG |
                        ControllerPacket.Y_FLAG | ControllerPacket.B_FLAG | ControllerPacket.X_FLAG | ControllerPacket.A_FLAG |
                        ControllerPacket.UP_FLAG | ControllerPacket.RIGHT_FLAG | ControllerPacket.LEFT_FLAG | ControllerPacket.DOWN_FLAG |
                        ControllerPacket.BACK_FLAG | ControllerPacket.SPECIAL_BUTTON_FLAG | ControllerPacket.PLAY_FLAG;
                        //| ControllerPacket.PADDLE2_FLAG | ControllerPacket.PADDLE1_FLAG | ControllerPacket.TOUCHPAD_FLAG;

        mCallback = new Callback(manager, device);
    }

    protected void handleRead(ByteBuffer buffer) {
        buffer.order(ByteOrder.LITTLE_ENDIAN);
        int reportId = Byte.toUnsignedInt(buffer.get());
        if (reportId < FEATURE_REPORT_ID || reportId == FEATURE_REPORT_ID_FRAGMENT_END) {
            handleReportFragment(reportId, buffer);
            return;
        }
        if (reportId != FEATURE_REPORT_ID) {
            Log.d(TAG, "Unknown feature report ID: " + reportId);
            return;
        }

        int type = Short.toUnsignedInt(buffer.getShort());
        if (type == 0x04) { // Compare the whole short value on purpose
            handleIdleEvent(buffer);
            return;
        }

        if ((byte)type == 0x05) {
            handleBatteryEvent(type, buffer);
            return;
        }

        //Log.v(TAG, "handleRead type=" + type + " remaining=" + buffer.remaining());
        handleInputEvent(type, buffer);
        handleGyroEvent(type, buffer);
    }

    // Partial reports are described here: https://gist.github.com/tiehichi/aa714f976fab5f608996dc2a27ba94b3#input-report-notify-packet-format
    private void handleReportFragment(int reportId, ByteBuffer buffer) {
        if (reportId == FEATURE_REPORT_ID_FRAGMENT_START) {
            resetReportFragmentation();
            lastReportId = reportId;
            ByteBuffer reportFragment = ByteBuffer.allocate(buffer.capacity()).order(ByteOrder.LITTLE_ENDIAN);
            reportFragment.put((byte)FEATURE_REPORT_ID);
            reportFragment.put(buffer);
            reassembledReport.add(reportFragment);
            return;
        }

        if (reportId != lastReportId + 1 && reportId != FEATURE_REPORT_ID_FRAGMENT_END) {
            Log.d(TAG, "Received out-of-order report fragment, expected report ID " + (lastReportId + 1) + " but got " + reportId);
            resetReportFragmentation();
            return;
        }

        lastReportId = reportId;
        ByteBuffer reportFragment = ByteBuffer.allocate(buffer.remaining()).order(ByteOrder.LITTLE_ENDIAN);
        reportFragment.put(buffer);
        reassembledReport.add(reportFragment);

        if (reportId == FEATURE_REPORT_ID_FRAGMENT_END) {
            int totalSize = 0;
            for (ByteBuffer fragment : reassembledReport) {
                totalSize += fragment.capacity();
            }
            ByteBuffer fullReport = ByteBuffer.allocate(totalSize).order(ByteOrder.LITTLE_ENDIAN);
            for (ByteBuffer fragment : reassembledReport) {
                fragment.rewind();
                fullReport.put(fragment);
            }
            fullReport.rewind();
            handleRead(fullReport);
            resetReportFragmentation();
        }
    }

    private void resetReportFragmentation() {
        lastReportId = 0;
        reassembledReport.clear();
    }

    private void handleIdleEvent(ByteBuffer buffer) {
        byte[] payload = new byte[buffer.remaining()];
        buffer.get(payload);

        // This is a special keep-alive report that should be otherwise empty, but check just in case.
        long sum = 0;
        for (byte imuDatum : payload) {
            sum += Byte.toUnsignedInt(imuDatum);
        }
        if (sum != 0) {
            Log.d(TAG, "Received non-empty keep-alive report, ignoring. Payload: " + Arrays.toString(payload));
        }

    }

    private void handleBatteryEvent(int type, ByteBuffer buffer) {
        if ((type >> 8) != 0x55) {
            Log.d(TAG, "Unknown battery report type: " + Integer.toHexString(type));
            return;
        }

        buffer.get();   // Skip (unknown) status flags
        long sequenceNumber = Integer.toUnsignedLong(buffer.getInt());    // Skip sequence number
        long alwaysZero = Integer.toUnsignedLong(buffer.getInt());
        if (alwaysZero != 0) {
            Log.d(TAG, "Received battery report with non-zero reserved field (was " + alwaysZero + "), this is unusual.");
        }

        int voltage = Short.toUnsignedInt(buffer.getShort());   // Voltage in mV
        // Estimate 3.3V .. 100%, 2.5V .. 0%: https://batteryskills.com/aa-battery-voltage-chart/
        byte percentage = (byte)Math.max(Math.min(100, ((voltage - 2500.0) / (3300.0 - 2500.0)) * 100.0), 0);
        //Log.v(TAG, "Battery report " + sequenceNumber + ": " + voltage + "mV, " + percentage + "%");
        reportBatteryState(MoonBridge.LI_BATTERY_STATE_DISCHARGING, percentage);
    }

    private void handleInputEvent(int type, ByteBuffer buffer) {
        boolean inputReceived = false;
        if((type & BLEButtonChunk1) != 0) {
            byte[] buttons = new byte[3];
            buffer.get(buttons);

            long b = Byte.toUnsignedLong(buttons[0]) | Byte.toUnsignedLong(buttons[1]) << 8 | Byte.toUnsignedLong(buttons[2]) << 16;
            // Set stick click flags both for back paddle click and actual stick click state
            setButtonFlag(ControllerPacket.RS_CLK_FLAG, (int) ((b & 0x00010000) | (b & 0x00040000)));
            setButtonFlag(ControllerPacket.LS_CLK_FLAG, (int) ((b & 0x00008000) | (b & 0x00400000)));

            setButtonFlag(ControllerPacket.RB_FLAG, (int) (b & 0x00000004));
            setButtonFlag(ControllerPacket.LB_FLAG, (int) (b & 0x00000008));

            setButtonFlag(ControllerPacket.Y_FLAG, (int) (b & 0x00000010));
            setButtonFlag(ControllerPacket.B_FLAG, (int) (b & 0x00000020));
            setButtonFlag(ControllerPacket.X_FLAG, (int) (b & 0x00000040));
            setButtonFlag(ControllerPacket.A_FLAG, (int) (b & 0x00000080));

            setButtonFlag(ControllerPacket.UP_FLAG, (int) (b & 0x00000100));
            setButtonFlag(ControllerPacket.RIGHT_FLAG, (int) (b & 0x00000200));
            setButtonFlag(ControllerPacket.LEFT_FLAG, (int) (b & 0x00000400));
            setButtonFlag(ControllerPacket.DOWN_FLAG, (int) (b & 0x00000800));

            setButtonFlag(ControllerPacket.BACK_FLAG, (int) (b & 0x00001000));
            setButtonFlag(ControllerPacket.SPECIAL_BUTTON_FLAG, (int) (b & 0x00002000));
            setButtonFlag(ControllerPacket.PLAY_FLAG, (int) (b & 0x00004000));

            if ((b & 0x00000001) != 0) {
                rightTrigger = 1.0f;
            }
            if ((b & 0x00000002) != 0) {
                leftTrigger = 1.0f;
            }

            /*setButtonFlag(ControllerPacket.PADDLE2_FLAG, (int) (b & 0x00008000));
            setButtonFlag(ControllerPacket.PADDLE1_FLAG, (int) (b & 0x00010000));
            setButtonFlag(ControllerPacket.TOUCHPAD_FLAG, (int) (b & 0x00020000));*/

            Log.d(TAG, "Buttons: " + Long.toBinaryString(b));
            inputReceived = true;
        }
        if((type & BLEButtonChunk2) != 0) {
            int left = Byte.toUnsignedInt(buffer.get());
            int right = Byte.toUnsignedInt(buffer.get());
            Log.d(TAG, "Triggers: " + left + " | " + right);
            leftTrigger = left / 255.0f;
            rightTrigger = right / 255.0f;

            inputReceived = true;
        }
        if((type & BLEButtonChunk3) != 0) {
            byte[] buttons = new byte[3];
            buffer.get(buttons);

            inputReceived = true;
        }
        if((type & BLELeftJoystickChunk) != 0) {
            int x = buffer.getShort();
            int y = ~buffer.getShort();
            Log.d(TAG, "Joystick: " + x + " | " + y);
            leftStickX = x / (float)Short.MAX_VALUE;
            leftStickY = y / (float)Short.MAX_VALUE;

            inputReceived = true;
        }
        if((type & BLELeftTrackpadChunk) != 0) {
            int x = buffer.getShort();
            int y = ~buffer.getShort();

            Log.v(TAG, "IGNORED Left Trackpad: " + x + " | " + y);
            inputReceived = true;
        }
        if((type & BLERightTrackpadChunk) != 0) {
            int x = buffer.getShort();
            int y = ~buffer.getShort();
            Log.d(TAG, "Right Pad: " + x + " | " + y);
            rightStickX = x / (float) Short.MAX_VALUE;
            rightStickY = y / (float) Short.MAX_VALUE;

            inputReceived = true;
        }

        if (inputReceived) {
            reportInput();
        }
    }

    private void handleGyroEvent(int type, ByteBuffer buffer) {
        boolean gyroReceived = false;
        if ((type & BLEIMUAccelChunk) != 0) {
            accelX = buffer.getShort() / ACCEL_RES_PER_G;
            accelY = buffer.getShort() / ACCEL_RES_PER_G;
            accelZ = buffer.getShort() / ACCEL_RES_PER_G;

            //Log.d(TAG, "Accel: " + accelX + " | " + accelY + " | " + accelZ);
            gyroReceived = true;
        }
        if ((type & BLEIMUGyroChunk) != 0) {
            gyroX = buffer.getShort() / GYRO_RES_PER_DPS;
            gyroY = buffer.getShort() / GYRO_RES_PER_DPS;
            gyroZ = buffer.getShort() / GYRO_RES_PER_DPS;

            //Log.d(TAG, "Gyro: " + gyroX + " | " + gyroY + " | " + gyroZ);
            gyroReceived = true;
        }
        if ((type & BLEIMUQuatChunk) != 0) {
            int w = buffer.getShort();
            int x = buffer.getShort();
            int y = buffer.getShort();
            int z = buffer.getShort();

            //Log.v(TAG, "IGNORED Gyro Quat: " + w + " | " + x + " | " + y + " | " + z);
            gyroReceived = true;
        }

        if (gyroReceived) {
            reportMotion();
        }
    }

    @Override
    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    public boolean start() {
        LimeLog.info(TAG + ": Starting Steam Controller with address " + mCallback.mDevice.getAddress());
        return mCallback.start();
    }

    @Override
    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    public void stop() {
        LimeLog.info(TAG + ": Stopping Steam Controller with address " + mCallback.mDevice.getAddress());
        resetReportFragmentation();
        mCallback.close();
    }

    @Override
    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    public int getProductId() {
        return mCallback.getProductId();
    }

    @Override
    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    public void setMotionEventState(byte motionType, short reportRateHz) {
        LimeLog.info(TAG + ": Enabling gyro with motionType=" + motionType + ", reportRateHz=" + reportRateHz);
        if (reportRateHz > 0) {
            mCallback.enableGyroscope();
        } else {
            mCallback.disableGyroscope();
        }
    }

    @Override
    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    public void setControllerLED(byte r, byte g, byte b) {
        // The Steam Controller doesn't support setting specific RGB values for its LED, but it does support setting the overall brightness of the LED.
        int brightness = Math.max(Byte.toUnsignedInt(r), Math.max(Byte.toUnsignedInt(g), Byte.toUnsignedInt(b)));
        brightness = (int)(brightness / 255.0 * 100);
        LimeLog.info(TAG + ": Setting LED brightness to " + brightness + "%");
        mCallback.setLEDBrightness((byte)brightness);
    }

    @Override
    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    public void rumble(short lowFreqMotor, short highFreqMotor) {
        Log.d(TAG, "Rumbling: lowFreq=" + lowFreqMotor + ", highFreq=" + highFreqMotor);
        mCallback.rumble(lowFreqMotor, highFreqMotor);
    }

    @Override
    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    public void rumbleTriggers(short leftTrigger, short rightTrigger) {
        Log.d(TAG, "Rumbling triggers: leftTrigger=" + leftTrigger + ", rightTrigger=" + rightTrigger);
        mCallback.rumble(leftTrigger, rightTrigger);
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    public void reconnect() {
        mCallback.reconnect();
        resetReportFragmentation();
    }

    private class Callback extends BluetoothGattCallback implements Closeable {
        private static final int D0G_BLE2_PID = 0x1106;
        private static final int TRITON_BLE_PID = 0x1303;

        private final BluetoothDriverService mManager;
        private final BluetoothDevice mDevice;
        private BluetoothGatt mGatt;
        private boolean mIsRegistered;
        private boolean mIsConnected = false;
        private final boolean mIsChromebook;
        private boolean mIsReconnecting = false;
        private final LinkedList<GattOperation> mOperations;
        GattOperation mCurrentOperation = null;
        private final Handler mHandler;
        private int mProductId = -1;

        private final HashMap<Integer, BluetoothGattCharacteristic> mOutputReportChars = new HashMap<>();

        public Callback(BluetoothDriverService manager, BluetoothDevice device) {
            mManager = manager;
            mDevice = device;
            mIsRegistered = false;
            mIsChromebook = isChromebook();
            mOperations = new LinkedList<>();
            mHandler = new Handler(Looper.getMainLooper());
        }

        private boolean isChromebook() {
            // https://stackoverflow.com/questions/39784415/how-to-detect-programmatically-if-android-app-is-running-in-chrome-book-or-in
            if (mManager != null) {
                if (mManager.getPackageManager().hasSystemFeature("org.chromium.arc")
                        || mManager.getPackageManager().hasSystemFeature("org.chromium.arc.device_management")) {
                    return true;
                }
            }

            // Running on AVD emulator
            boolean isChromebookEmulator = (Build.MODEL != null && Build.MODEL.startsWith("sdk_gpc_"));
            return isChromebookEmulator;
        }

        @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
        public boolean start() {
            mGatt = connectGatt();
            if (mGatt == null) {
                LimeLog.severe("Failed to connect GATT for Steam Controller");
                return false;
            }
            return true;
        }

        // Because on Chromebooks we show up as a dual-mode device, it will attempt to connect TRANSPORT_AUTO, which will use TRANSPORT_BREDR instead
        // of TRANSPORT_LE.  Let's force ourselves to connect low energy.
        @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
        private BluetoothGatt connectGatt(boolean autoConnect) {
            if (Build.VERSION.SDK_INT >= 23 /* Android 6.0 (M) */) {
                try {
                    return mDevice.connectGatt(mManager, autoConnect, mCallback, TRANSPORT_LE);
                } catch (Exception e) {
                    return mDevice.connectGatt(mManager, autoConnect, mCallback);
                }
            } else {
                return mDevice.connectGatt(mManager, autoConnect, mCallback);
            }
        }

        @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
        private BluetoothGatt connectGatt() {
            return connectGatt(false);
        }

        @Override
        @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
        public void onConnectionStateChange(BluetoothGatt gatt, int status, int newState) {
            //Log.v(TAG, "onConnectionStateChange status=" + status + " newState=" + newState);
            mIsReconnecting = false;
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                mIsConnected = true;
                // Run directly, without GattOperation
                if (!isRegistered()) {
                    mHandler.post(() -> mGatt.discoverServices());
                }
            }
            else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                mIsConnected = false;
                notifyDeviceRemoved();
            }

            // Disconnection is handled in SteamLink using the ACTION_ACL_DISCONNECTED Intent.
        }

        @Override
        @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
        public void onServicesDiscovered(BluetoothGatt gatt, int status) {
            //Log.v(TAG, "onServicesDiscovered status=" + status);
            if (status == 0) {
                if (gatt.getServices().isEmpty()) {
                    LimeLog.severe("onServicesDiscovered returned zero services; something has gone horribly wrong down in Android's Bluetooth stack.");
                    mIsReconnecting = true;
                    mIsConnected = false;
                    gatt.disconnect();
                    mGatt = connectGatt();
                } else {
                    if (getProductId() == TRITON_BLE_PID) {
                        // Android will not properly play well with Data Length Extensions without manually requesting a large MTU,
                        // and Triton controllers require DLE support.
                        //
                        // 517 is basically a "magic number" as far as Android's bluetooth code is concerned, so do not change
                        // this value. It is functionally "please enable data length extensions" on some Android builds.
                        mGatt.requestMtu(517);
                    }

                    probeService(SteamController.this);
                }
            }
        }

        @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
        private void probeService(SteamController controller) {
            if (isRegistered()) {
                return;
            }

            if (!mIsConnected) {
                return;
            }

            Log.d(TAG, "probeService controller=" + controller);

            for (BluetoothGattService service : mGatt.getServices()) {
                if (service.getUuid().equals(steamControllerService)) {
                    LimeLog.info(TAG + ": Found Valve steam controller service " + service.getUuid());

                    for (BluetoothGattCharacteristic chr : service.getCharacteristics()) {
                        boolean bShouldStartNotifications = false;

                        if (chr.getUuid().equals(inputCharacteristicTriton)) {
                            LimeLog.info(TAG + ": Found Triton input characteristic");
                            mProductId = TRITON_BLE_PID;
                            // TODO: Support Steam Controller 2
                            //bShouldStartNotifications = true;

                            Toast.makeText(mManager, R.string.toast_steam_controller_unsupported, Toast.LENGTH_LONG).show();
                            mHandler.post(SteamController.this::stop);
                        } else if (chr.getUuid().equals(inputCharacteristicD0G)) {
                            LimeLog.info(TAG + ": Found D0G input characteristic");
                            mProductId = D0G_BLE2_PID;
                            bShouldStartNotifications = true;
                        } else {
                            Pattern reportPattern = Pattern.compile("100F6C([0-9A-Z]{2})", Pattern.CASE_INSENSITIVE);
                            Matcher matcher = reportPattern.matcher(chr.getUuid().toString());

                            if (matcher.find()) {
                                try {
                                    String reportIdGroup = Objects.requireNonNull(matcher.group(1));
                                    int reportId = Integer.parseInt(reportIdGroup, 16);

                                    reportId -= 0x35;
                                    if (reportId >= 0x80) {
                                        // This is a Triton output report characteristic that we need to care about.
                                        LimeLog.info(TAG + ": Found Triton output report 0x" + Integer.toString(reportId, 16));
                                        mOutputReportChars.put(reportId, chr);
                                    }
                                }
                                catch (NumberFormatException nfe) {
                                    LimeLog.warning("Could not parse report characteristic " + chr.getUuid().toString() + ": " + nfe.toString());
                                }
                            }
                        }

                        if (bShouldStartNotifications) {
                            // Start notifications
                            BluetoothGattDescriptor cccd = chr.getDescriptor(UUID.fromString("00002902-0000-1000-8000-00805f9b34fb"));
                            if (cccd != null) {
                                enableNotification(chr.getUuid());
                            }
                        }
                    }
                    return;
                }
            }

            if ((mGatt.getServices().isEmpty()) && mIsChromebook && !mIsReconnecting) {
                LimeLog.severe("Chromebook: Discovered services were empty; this almost certainly means the BtGatt.ContextMap bug has bitten us.");
                mIsConnected = false;
                mIsReconnecting = true;
                mGatt.disconnect();
                mGatt = connectGatt();
            }
        }

        @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
        private void enableNotification(UUID chrUuid) {
            GattOperation op = GattOperation.enableNotification(mGatt, chrUuid);
            queueGattOperation(op);
        }

        @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
        private void queueGattOperation(GattOperation op) {
            synchronized (mOperations) {
                mOperations.add(op);
            }
            executeNextGattOperation();
        }

        @Override
        @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
        public void onCharacteristicRead(@NonNull BluetoothGatt gatt, BluetoothGattCharacteristic characteristic, byte @NonNull [] value, int status) {
            //Log.v(TAG, "onCharacteristicRead status=" + status + " uuid=" + characteristic.getUuid());

            if (characteristic.getUuid().equals(reportCharacteristic)) {
                //mManager.HIDDeviceReportResponse(getId(), characteristic.getValue());
            }

            finishCurrentGattOperation();
        }

        @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
        private void finishCurrentGattOperation() {
            GattOperation op = null;
            synchronized (mOperations) {
                if (mCurrentOperation != null) {
                    op = mCurrentOperation;
                    mCurrentOperation = null;
                }
            }
            if (op != null) {
                boolean result = op.finish(); // TODO: Maybe in main thread as well?

                // Our operation failed, let's add it back to the beginning of our queue.
                if (!result) {
                    mOperations.addFirst(op);
                }
            }
            executeNextGattOperation();
        }

        @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
        private void executeNextGattOperation() {
            synchronized (mOperations) {
                if (mCurrentOperation != null)
                    return;

                if (mOperations.isEmpty())
                    return;

                mCurrentOperation = mOperations.removeFirst();
            }

            // Run in main thread
            mHandler.post(() -> {
                synchronized (mOperations) {
                    if (mCurrentOperation == null) {
                        Log.w(TAG, "Current operation null in executor?");
                        return;
                    }

                    mCurrentOperation.run();
                    // now wait for the GATT callback and when it comes, finish this operation
                }
            });
        }

        @Override
        @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
        public void onCharacteristicWrite(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic, int status) {
            //Log.v(TAG, "onCharacteristicWrite status=" + status + " uuid=" + characteristic.getUuid());

            if (characteristic.getUuid().equals(reportCharacteristic)) {
                // Only register controller with the native side once it has been fully configured
                if (!isRegistered()) {
                    LimeLog.info(TAG + ": Registering Steam Controller with ID: " + getIdentifier());
                    //mManager.HIDDeviceConnected(getId(), getIdentifier(), getVendorId(), getProductId(), getSerialNumber(), getVersion(), getManufacturerName(), getProductName(), 0, 0, 0, 0, true);
                    setRegistered();
                    notifyDeviceAdded();
                }
            }

            finishCurrentGattOperation();
        }

        private String getIdentifier() {
            return String.format("SteamController.%s", mDevice.getAddress());
        }

        @Override
        @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
        public void onCharacteristicChanged(@NonNull BluetoothGatt gatt, BluetoothGattCharacteristic characteristic, byte @NonNull [] value) {
            // Enable this for verbose logging of controller input reports
            //Log.v(TAG, "onCharacteristicChanged uuid=" + characteristic.getUuid() + " data=" + Arrays.toString(characteristic.getValue()));

            if (characteristic.getUuid().equals(getInputCharacteristic())) {
                //mManager.HIDDeviceInputReport(getId(), characteristic.getValue());
                handleRead(ByteBuffer.wrap(characteristic.getValue()));
            }
        }

        @Override
        public void onDescriptorRead(@NonNull BluetoothGatt gatt, @NonNull BluetoothGattDescriptor descriptor, int status, byte @NonNull [] value) {
            //Log.v(TAG, "onDescriptorRead status=" + status);
        }

        @Override
        @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
        public void onDescriptorWrite(BluetoothGatt gatt, BluetoothGattDescriptor descriptor, int status) {
            BluetoothGattCharacteristic chr = descriptor.getCharacteristic();
            //Log.v(TAG, "onDescriptorWrite status=" + status + " uuid=" + chr.getUuid() + " descriptor=" + descriptor.getUuid());

            if (chr.getUuid().equals(getInputCharacteristic())) {
                BluetoothGattCharacteristic reportChr = chr.getService().getCharacteristic(reportCharacteristic);
                if (reportChr != null && !isRegistered()) {
                    if (getProductId() == TRITON_BLE_PID) {
                        // For Triton we just mark things registered.
                        LimeLog.info(TAG + ": Registering Triton Steam Controller with ID: " + getControllerId());
                        //mManager.HIDDeviceConnected(getId(), getIdentifier(), getVendorId(), getProductId(), getSerialNumber(), getVersion(), getManufacturerName(), getProductName(), 0, 0, 0, 0, true);
                        setRegistered();
                    } else {
                        // For the original controller, we need to manually enter Valve mode.
                        Log.d(TAG, "Writing report characteristic to enter valve mode");
                        enterLizardMode();
                    }
                }
            }

            finishCurrentGattOperation();
        }

        @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
        private int getProductId() {
            if (mProductId > 0) {
                // We've already set a product ID.
                return mProductId;
            }

            if (mDevice.getName().startsWith("Steam Ctrl")) {
                // We're a newer Triton device
                mProductId = TRITON_BLE_PID;
            } else {
                // We're an OG Steam Controller
                mProductId = D0G_BLE2_PID;
            }

            return mProductId;
        }

        @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
        public void reconnect() {
            if (getConnectionState() != BluetoothProfile.STATE_CONNECTED && mGatt != null) {
                mGatt.disconnect();
                mGatt = connectGatt();
            } else {
                start();
            }
        }

        @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
        private int getConnectionState() {
            Context context = mManager;
            if (context == null) {
                // We are lacking any context to get our Bluetooth information.  We'll just assume disconnected.
                return BluetoothProfile.STATE_DISCONNECTED;
            }

            BluetoothManager btManager = (BluetoothManager)context.getSystemService(Context.BLUETOOTH_SERVICE);
            if (btManager == null) {
                // This device doesn't support Bluetooth.  We should never be here, because how did
                // we instantiate a device to start with?
                return BluetoothProfile.STATE_DISCONNECTED;
            }

            return btManager.getConnectionState(mDevice, BluetoothProfile.GATT);
        }

        @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
        public void enterLizardMode() {
            // Logic inspired by:
            // https://github.com/ricardoquesada/bluepad32/blob/main/src/components/bluepad32/parser/uni_hid_parser_steam.c#L76-L91

            // Disable esc, enter, cursor
            sendReportCommand(ReportCommand.CLEAR_DIGITAL_MAPPINGS, null);

            // Disable mouse
            ByteBuffer settingsData = ByteBuffer.allocate(6).order(ByteOrder.LITTLE_ENDIAN);
            settingsData.put(SETTING_LPAD_MODE);
            settingsData.putShort(TRACKPAD_MODE_NONE);
            settingsData.put(SETTING_RPAD_MODE);
            settingsData.putShort(TRACKPAD_MODE_NONE);
            sendReportCommand(ReportCommand.SET_SETTINGS_VALUES, settingsData);
        }

        @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
        public void exitLizardMode() {
            // Enable esc, enter, cursors
            sendReportCommand(ReportCommand.SET_DEFAULT_DIGITAL_MAPPINGS, null);
            // Reset settings
            sendReportCommand(ReportCommand.LOAD_DEFAULT_SETTINGS, null);
        }

        @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
        public void enableGyroscope() {
            ByteBuffer settingsData = ByteBuffer.allocate(3).order(ByteOrder.LITTLE_ENDIAN);
            settingsData.put(SETTING_GYRO_MODE);
            settingsData.putShort((short)(GyroMode.SEND_RAW_ACCEL.getValue() | GyroMode.SEND_RAW_GYRO.getValue()));
            sendReportCommand(ReportCommand.SET_SETTINGS_VALUES, settingsData);
        }

        @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
        public void disableGyroscope() {
            ByteBuffer settingsData = ByteBuffer.allocate(3).order(ByteOrder.LITTLE_ENDIAN);
            settingsData.put(SETTING_GYRO_MODE);
            settingsData.putShort(GyroMode.OFF.getValue());
            sendReportCommand(ReportCommand.SET_SETTINGS_VALUES, settingsData);
        }

        @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
        public void setLEDBrightness(byte brightnessPercent) {
            if (brightnessPercent < 0 || brightnessPercent > 100) {
                throw new IllegalArgumentException("Brightness percentage must be between 0 and 100");
            }

            ByteBuffer settingsData = ByteBuffer.allocate(3).order(ByteOrder.LITTLE_ENDIAN);
            settingsData.put(SETTING_LED_USER_BRIGHTNESS);
            settingsData.putShort(brightnessPercent);
            sendReportCommand(ReportCommand.SET_SETTINGS_VALUES, settingsData);
        }

        @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
        public void rumble(short leftSpeed, short rightSpeed) {
            // Modeled after the rumble command in Linux's hid-steam driver:
            // https://github.com/torvalds/linux/blob/master/drivers/hid/hid-steam.c#L514-L555
            // However, this doesn't seem to work
            /*ByteBuffer rumbleData = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)
                    .put((byte)0)   // Amplitude low byte
                    .put((byte)0)   // Amplitude high byte
                    .putShort(leftSpeed)
                    .putShort(rightSpeed)
                    .put((byte)2)   // Left gain
                    .put((byte)0);  // Right gain
            sendReportCommand(ReportCommand.TRIGGER_RUMBLE, rumbleData);*/

            hapticPulse(Trigger.LEFT, leftSpeed, (byte) 2);
            hapticPulse(Trigger.RIGHT, rightSpeed, (byte) 0);
        }

        @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
        private void hapticPulse(Trigger trigger, short onTime, byte gain) {
            short offTime = 0;
            short pulses = 0;
            if (onTime > 0) {
                offTime = (short)(0xFFFF - onTime);
                pulses = (short)0xFFFF;   // Practically infinite for our purposes until the next rumble command arrives
            }

            ByteBuffer rumbleData = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)
                    .put(trigger.getValue())    // Trigger index
                    .putShort(onTime)  // Time on in µs
                    .putShort(offTime) // Time off in µs
                    .putShort(pulses)   // Number of pulses
                    .put(gain);  // Gain in decibels, ranging from -24 to +6
            Log.v(TAG, "Rumbling trigger " + trigger + ": onTime=" + onTime + "µs, offTime=" + offTime + "µs");
            sendReportCommand(ReportCommand.TRIGGER_HAPTIC_PULSE, rumbleData);
        }

        @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
        private void sendReportCommand(ReportCommand reportCommand, @Nullable ByteBuffer payload) {
            int payloadLength = 0;
            if (payload != null) {
                payload.rewind();
                payloadLength = payload.remaining();
            }

            byte[] command = new byte[3 + payloadLength];
            command[0] = (byte)FEATURE_REPORT_ID;
            command[1] = reportCommand.getValue();
            command[2] = (byte) payloadLength;
            if (payload != null) {
                payload.get(command, 3, payloadLength);
            }

            if (Log.isLoggable(TAG, Log.VERBOSE)) {
                String[] bytes = new String[command.length];
                for (int i = 0; i < command.length; i++) {
                    bytes[i] = Hex.toHexString(command, i, 1);
                }
                Log.v(TAG, "Sending report command " + reportCommand.name() + ", command=" + Arrays.toString(bytes));
            }
            queueGattOperation(GattOperation.writeCharacteristic(mGatt, reportCharacteristic, command));
        }

        @Override
        @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
        public void close() {
            BluetoothGatt g = mGatt;
            if (g != null) {
                if (mIsConnected) {
                    // Reset settings potentially changed while using the controller
                    rumble((short)0, (short)0);
                    setLEDBrightness((byte)100);

                    if (getProductId() == D0G_BLE2_PID) {
                        exitLizardMode();
                    }

                    mHandler.post(this::finishClosing);
                } else {
                    finishClosing();
                }
            } else {
                resetState();
            }
        }

        @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
        private void finishClosing() {
            synchronized (mOperations) {
                if (mIsConnected && !mOperations.isEmpty()) {
                    // Wait for pending operations to finish before closing GATT, otherwise we might run into issues with pending operations trying to access a closed GATT.
                    mHandler.post(this::finishClosing);
                    return;
                }
            }

            BluetoothGatt g = mGatt;
            if (g != null) {
                g.disconnect();
                g.close();
                mGatt = null;
            }
            resetState();
        }

        private void resetState() {
            mIsRegistered = false;
            mIsConnected = false;
        }

        private boolean isRegistered() {
            return mIsRegistered;
        }

        private void setRegistered() {
            mIsRegistered = true;
        }

        @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
        private UUID getInputCharacteristic() {
            if (getProductId() == TRITON_BLE_PID) {
                return inputCharacteristicTriton;
            } else {
                return inputCharacteristicD0G;
            }
        }
    }

    static class GattOperation {
        private enum Operation {
            CHR_READ,
            CHR_WRITE,
            ENABLE_NOTIFICATION
        }

        Operation mOp;
        UUID mUuid;
        byte[] mValue;
        BluetoothGatt mGatt;
        boolean mResult = true;

        private GattOperation(BluetoothGatt gatt, GattOperation.Operation operation, UUID uuid) {
            mGatt = gatt;
            mOp = operation;
            mUuid = uuid;
        }

        private GattOperation(BluetoothGatt gatt, GattOperation.Operation operation, UUID uuid, byte[] value) {
            mGatt = gatt;
            mOp = operation;
            mUuid = uuid;
            mValue = value;
        }

        @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
        public void run() {
            // This is executed in main thread
            BluetoothGattCharacteristic chr;

            switch (mOp) {
                case CHR_READ:
                    chr = getCharacteristic(mUuid);
                    //Log.v(TAG, "Reading characteristic " + chr.getUuid());
                    if (!mGatt.readCharacteristic(chr)) {
                        LimeLog.severe(TAG + ": Unable to read characteristic " + mUuid.toString());
                        mResult = false;
                        break;
                    }
                    mResult = true;
                    break;
                case CHR_WRITE:
                    chr = getCharacteristic(mUuid);
                    //Log.v(TAG, "Writing characteristic " + chr.getUuid() + " value=" + HexDump.toHexString(value));
                    chr.setValue(mValue);
                    if (!mGatt.writeCharacteristic(chr)) {
                        LimeLog.severe(TAG + ": Unable to write characteristic " + mUuid.toString());
                        mResult = false;
                        break;
                    }
                    mResult = true;
                    break;
                case ENABLE_NOTIFICATION:
                    chr = getCharacteristic(mUuid);
                    //Log.v(TAG, "Writing descriptor of " + chr.getUuid());
                    if (chr != null) {
                        BluetoothGattDescriptor cccd = chr.getDescriptor(UUID.fromString("00002902-0000-1000-8000-00805f9b34fb"));
                        if (cccd != null) {
                            int properties = chr.getProperties();
                            byte[] value;
                            if ((properties & BluetoothGattCharacteristic.PROPERTY_NOTIFY) == BluetoothGattCharacteristic.PROPERTY_NOTIFY) {
                                value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE;
                            } else if ((properties & BluetoothGattCharacteristic.PROPERTY_INDICATE) == BluetoothGattCharacteristic.PROPERTY_INDICATE) {
                                value = BluetoothGattDescriptor.ENABLE_INDICATION_VALUE;
                            } else {
                                LimeLog.severe(TAG + ": Unable to start notifications on input characteristic");
                                mResult = false;
                                return;
                            }

                            mGatt.setCharacteristicNotification(chr, true);
                            cccd.setValue(value);
                            if (!mGatt.writeDescriptor(cccd)) {
                                LimeLog.severe(TAG + ": Unable to write descriptor " + mUuid.toString());
                                mResult = false;
                                return;
                            }
                            mResult = true;
                        }
                    }
            }
        }

        public boolean finish() {
            return mResult;
        }

        private BluetoothGattCharacteristic getCharacteristic(UUID uuid) {
            BluetoothGattService valveService = mGatt.getService(steamControllerService);
            if (valveService == null)
                return null;
            return valveService.getCharacteristic(uuid);
        }

        static public GattOperation readCharacteristic(BluetoothGatt gatt, UUID uuid) {
            return new GattOperation(gatt, Operation.CHR_READ, uuid);
        }

        static public GattOperation writeCharacteristic(BluetoothGatt gatt, UUID uuid, byte[] value) {
            return new GattOperation(gatt, Operation.CHR_WRITE, uuid, value);
        }

        static public GattOperation enableNotification(BluetoothGatt gatt, UUID uuid) {
            return new GattOperation(gatt, Operation.ENABLE_NOTIFICATION, uuid);
        }
    }

    private enum ReportCommand {
        CLEAR_DIGITAL_MAPPINGS((byte)0x81),
        SET_DEFAULT_DIGITAL_MAPPINGS((byte)0x85),
        SET_SETTINGS_VALUES((byte)0x87),
        LOAD_DEFAULT_SETTINGS((byte)0x8E),
        TRIGGER_HAPTIC_PULSE((byte)0x8F),
        TRIGGER_RUMBLE((byte)0xEB);

        private final byte value;
        ReportCommand(byte value) {
            this.value = value;
        }

        public byte getValue() {
            return value;
        }
    }

    private enum GyroMode {
        /**
         * No gyroscope input whatsoever.
         */
        OFF(0x00),
        /**
         * Simulates steering wheel motions on the left trackpad.
         */
        STEERING(0x01),
        /**
         * Simulates tilting motions (like a fishing rod) on the left trackpad where the X axis always stays 0.
         */
        TILT(0x02),
        /**
         * Sends quaternion data from the gyroscope.
         */
        SEND_ORIENTATION(0x04),
        /**
         * Sends raw acceleration data from the gyroscope.
         */
        SEND_RAW_ACCEL(0x08),
        /**
         * Sends raw gyro data from the gyroscope.
         */
        SEND_RAW_GYRO(0x10);

        private final short value;
        GyroMode(int value) {
            this.value = (short)value;
        }

        public short getValue() {
            return value;
        }
    }

    private enum Trigger {
        LEFT(0x01),
        RIGHT(0x00);

        private final byte value;
        Trigger(int value) {
            this.value = (byte)value;
        }

        public byte getValue() {
            return value;
        }
    }
}
