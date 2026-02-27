package com.limelight.binding.input.driver;

import android.Manifest;
import android.bluetooth.*;
import android.content.Context;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;

import android.util.Log;
import androidx.annotation.Nullable;
import androidx.annotation.RequiresPermission;
import com.limelight.LimeLog;
import com.limelight.nvstream.input.ControllerPacket;
import com.limelight.nvstream.jni.MoonBridge;
import org.bouncycastle.util.encoders.Hex;
import org.jspecify.annotations.NonNull;

import java.io.Closeable;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static android.bluetooth.BluetoothDevice.TRANSPORT_LE;

/**
 * The methods in this class are inspired by SDL's Steam Controller HID device:
 * <a href="https://github.com/libsdl-org/SDL/blob/bbcc205de97a1b3e53257af2a77f5f5d3d59c4f8/android-project/app/src/main/java/org/libsdl/app/HIDDeviceBLESteamController.java">HIDDeviceBLESteamController.java</a>
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
    private static final byte FEATURE_REPORT_ID = (byte)0xC0;
    private static final byte CMD_CLEAR_DIGITAL_MAPPINGS = (byte)0x81;
    private static final byte CMD_SET_SETTINGS_VALUES = (byte)0x87;
    private static final byte SETTING_LPAD_MODE = (byte)0x07;
    private static final byte SETTING_RPAD_MODE = (byte)0x08;
    private static final byte SETTING_RPAD_MARGIN = (byte)0x18;
    private static final byte SETTING_GYRO_MODE = (byte)0x30;

    private static final short TRACKPAD_MODE_DISABLED = 0x07;

    // Valve Corporation
    private static final int VALVE_USB_VID = 0x28DE;

    static final UUID steamControllerService = UUID.fromString("100F6C32-1735-4313-B402-38567131E5F3");
    static final UUID inputCharacteristicD0G = UUID.fromString("100F6C33-1735-4313-B402-38567131E5F3");
    static final UUID inputCharacteristicTriton = UUID.fromString("100F6C7A-1735-4313-B402-38567131E5F3");
    static final UUID reportCharacteristic = UUID.fromString("100F6C34-1735-4313-B402-38567131E5F3");
    private static final byte[] setGamepadModeCommand = new byte[] { (byte)0xC0, CMD_SET_SETTINGS_VALUES, 0x0C, // Length
            SETTING_LPAD_MODE,   0x07, 0x00, // Disable cursor
            SETTING_RPAD_MODE,   0x07, 0x00, // Disable mouse
            SETTING_RPAD_MARGIN, 0x00, 0x00, // No margin
            SETTING_GYRO_MODE,   /*0x1F*/0x00, 0x00, // Disable gyro/accel
        };

    private final Callback mCallback;

    public SteamController(ControllerDriverListener listener, BluetoothDriverService manager, int deviceId, BluetoothDevice device) {
        super(deviceId, listener, VALVE_USB_VID, 0);
        type = MoonBridge.LI_CTYPE_PS;
        capabilities =
                MoonBridge.LI_CCAP_ANALOG_TRIGGERS | MoonBridge.LI_CCAP_RUMBLE | MoonBridge.LI_CCAP_TRIGGER_RUMBLE |
                        MoonBridge.LI_CCAP_ACCEL | MoonBridge.LI_CCAP_GYRO;
        buttonFlags =
                ControllerPacket.A_FLAG | ControllerPacket.B_FLAG | ControllerPacket.X_FLAG | ControllerPacket.Y_FLAG |
                        ControllerPacket.UP_FLAG | ControllerPacket.DOWN_FLAG | ControllerPacket.LEFT_FLAG | ControllerPacket.RIGHT_FLAG |
                        ControllerPacket.LB_FLAG | ControllerPacket.RB_FLAG |
                        ControllerPacket.LS_CLK_FLAG | ControllerPacket.RS_CLK_FLAG |
                        ControllerPacket.BACK_FLAG | ControllerPacket.PLAY_FLAG | ControllerPacket.SPECIAL_BUTTON_FLAG;

        mCallback = new Callback(manager, device);
    }

    protected boolean handleRead(ByteBuffer buffer) {
        buffer.order(ByteOrder.LITTLE_ENDIAN);
        int version = Byte.toUnsignedInt(buffer.get()); // skip first byte
        if (version != 0xC0) {
            Log.d(TAG, "Unknown report version: " + version);
            return false;
        }

        int type = Byte.toUnsignedInt(buffer.get()) | Byte.toUnsignedInt(buffer.get()) << 8;
        if (type == 4) {
            // This is a special report that only contains IMU data.  We can ignore it, as we get the same data in our regular reports.
            byte[] imuData = new byte[buffer.remaining()];
            long sum = 0;
            for (byte imuDatum : imuData) {
                sum += Byte.toUnsignedInt(imuDatum);
            }
            if (sum != 0) {
                Log.d(TAG, "Received non-empty IMU-only report, ignoring. Data: " + Hex.toHexString(imuData));
            }

            return true;
        }

        if((type & BLEButtonChunk1) != 0) {
            byte[] buttons = new byte[3];
            buffer.get(buttons);

            long b = Byte.toUnsignedLong(buttons[0]) | Byte.toUnsignedLong(buttons[1]) << 8 | Byte.toUnsignedLong(buttons[2]) << 16;
            setButtonFlag(ControllerPacket.RS_CLK_FLAG, (int) (b & 0x00000001));
            setButtonFlag(ControllerPacket.LS_CLK_FLAG, (int) (b & 0x00000002));

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

            Log.d(TAG, "Buttons: " + Long.toBinaryString(b));
        }
        if((type & BLEButtonChunk2) != 0) {
            int left = Byte.toUnsignedInt(buffer.get());
            int right = Byte.toUnsignedInt(buffer.get());
            Log.d(TAG, "Triggers: "+left+" | "+right);
            leftTrigger = left/255.0f;
            rightTrigger = right/255.0f;
        }
        if((type & BLEButtonChunk3) != 0) {
            byte[] buttons = new byte[3];
            buffer.get(buttons);
        }
        if((type & BLELeftJoystickChunk) != 0) {
            int x = buffer.getShort();
            int y = ~buffer.getShort();
            Log.d(TAG, "Joystick: "+x+" | "+y);
            leftStickX = x / (float)Short.MAX_VALUE;
            leftStickY = y / (float)Short.MAX_VALUE;
        }
        if((type & BLELeftTrackpadChunk) != 0) {
            int x = buffer.getShort();
            int y = ~buffer.getShort();
            //Log.d(TAG, "IGNORED Left Trackpad: "+x+" | "+y);
        }
        if((type & BLERightTrackpadChunk) != 0) {
            int x = buffer.getShort();
            int y = ~buffer.getShort();
            Log.d(TAG, "Right Pad: "+x+" | "+y);
            rightStickX = x / (float)Short.MAX_VALUE;
            rightStickY = y / (float)Short.MAX_VALUE;
        }
        if((type & BLEIMUAccelChunk) != 0) {
            accelX = buffer.getShort() / (float)Short.MAX_VALUE;
            accelY = buffer.getShort() / (float)Short.MAX_VALUE;
            accelZ = buffer.getShort() / (float)Short.MAX_VALUE;
            Log.d(TAG, "Accel: "+accelX+" | "+accelY+" | "+accelZ);
        }
        if((type & BLEIMUGyroChunk) != 0) {
            gyroX = buffer.getShort() / (float)Short.MAX_VALUE;
            gyroY = buffer.getShort() / (float)Short.MAX_VALUE;
            gyroZ = buffer.getShort() / (float)Short.MAX_VALUE;
            Log.d(TAG, "Gyro: "+gyroX+" | "+gyroY+" | "+gyroZ);
        }
        if((type & BLEIMUQuatChunk) != 0) {
            int w = buffer.getShort();
            int x = buffer.getShort();
            int y = buffer.getShort();
            int z = 0;//buffer.getShort();
            Log.d(TAG, "IGNORED Gyro Quat: "+w+" | "+x+" | "+y+" | "+z);
        }

        reportInput();

        return true;
    }

    @Override
    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    public boolean start() {
        return mCallback.start();
    }

    @Override
    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    public void stop() {
        // Stop rumbling before closing connection
        mCallback.rumble((short) 0, (short) 0);

        mCallback.close();
    }

    @Override
    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    public int getProductId() {
        return mCallback.getProductId();
    }

    @Override
    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    public void rumble(short lowFreqMotor, short highFreqMotor) {
        // TODO: Implement rumble
        Log.d(TAG, "Rumbling imaginatively: lowFreq=" + lowFreqMotor + " highFreq=" + highFreqMotor);
        //mCallback.rumble(lowFreqMotor, highFreqMotor);
    }

    @Override
    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    public void rumbleTriggers(short leftTrigger, short rightTrigger) {
        Log.d(TAG, "Rumbling triggers imaginatively: leftTrigger=" + leftTrigger + " rightTrigger=" + rightTrigger);
        //mCallback.rumble(leftTrigger, rightTrigger);
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    public void reconnect() {
        mCallback.reconnect();
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
        private boolean probeService(SteamController controller) {
            if (isRegistered()) {
                return true;
            }

            if (!mIsConnected) {
                return false;
            }

            LimeLog.info("probeService controller=" + controller);

            for (BluetoothGattService service : mGatt.getServices()) {
                if (service.getUuid().equals(steamControllerService)) {
                    LimeLog.info("Found Valve steam controller service " + service.getUuid());

                    for (BluetoothGattCharacteristic chr : service.getCharacteristics()) {
                        boolean bShouldStartNotifications = false;

                        if (chr.getUuid().equals(inputCharacteristicTriton)) {
                            LimeLog.info("Found Triton input characteristic");
                            mProductId = TRITON_BLE_PID;
                            bShouldStartNotifications = true;
                        } else if (chr.getUuid().equals(inputCharacteristicD0G)) {
                            LimeLog.info("Found D0G input characteristic");
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
                                        LimeLog.info("Found Triton output report 0x" + Integer.toString(reportId, 16));
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
                    return true;
                }
            }

            if ((mGatt.getServices().isEmpty()) && mIsChromebook && !mIsReconnecting) {
                LimeLog.severe("Chromebook: Discovered services were empty; this almost certainly means the BtGatt.ContextMap bug has bitten us.");
                mIsConnected = false;
                mIsReconnecting = true;
                mGatt.disconnect();
                mGatt = connectGatt();
            }

            return false;
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
                // TODO: Report features
                Log.v(TAG, "Report characteristic read: " + Hex.toHexString(characteristic.getValue()));
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
                        LimeLog.warning("Current operation null in executor?");
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
                    LimeLog.info("Registering Steam Controller with ID: " + getIdentifier());
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
            Log.v(TAG, "onDescriptorRead status=" + status);
        }

        @Override
        @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
        public void onDescriptorWrite(BluetoothGatt gatt, BluetoothGattDescriptor descriptor, int status) {
            BluetoothGattCharacteristic chr = descriptor.getCharacteristic();
            Log.v(TAG, "onDescriptorWrite status=" + status + " uuid=" + chr.getUuid() + " descriptor=" + descriptor.getUuid());

            if (chr.getUuid().equals(getInputCharacteristic())) {
                BluetoothGattCharacteristic reportChr = chr.getService().getCharacteristic(reportCharacteristic);
                if (reportChr != null) {
                    if (getProductId() == TRITON_BLE_PID) {
                        // For Triton we just mark things registered.
                        LimeLog.info("Registering Triton Steam Controller with ID: " + getControllerId());
                        //mManager.HIDDeviceConnected(getId(), getIdentifier(), getVendorId(), getProductId(), getSerialNumber(), getVersion(), getManufacturerName(), getProductName(), 0, 0, 0, 0, true);
                        setRegistered();
                    } else {
                        // For the original controller, we need to manually enter Valve mode.
                        LimeLog.info("Writing report characteristic to enter valve mode");
                        /*reportChr.setValue(clearMappingsCommand);
                        gatt.writeCharacteristic(reportChr);
                        reportChr.setValue(enterValveMode);
                        gatt.writeCharacteristic(reportChr);*/
                        enableLizardMode();
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
            if (getConnectionState() != BluetoothProfile.STATE_CONNECTED) {
                mGatt.disconnect();
                mGatt = connectGatt();
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

        public void enableLizardMode() {
            // Disable esc, enter, cursor
            sendReportCommand(ReportCommand.CLEAR_DIGITAL_MAPPINGS, null);

            // Disable mouse
            ByteBuffer settingsData = ByteBuffer.allocate(3).order(ByteOrder.LITTLE_ENDIAN);
            settingsData.put(SETTING_LPAD_MODE);
            settingsData.putShort(TRACKPAD_MODE_DISABLED);
            settingsData.put(SETTING_RPAD_MODE);
            settingsData.putShort(TRACKPAD_MODE_DISABLED);
            sendReportCommand(ReportCommand.SET_SETTINGS_VALUES, settingsData);
        }

        public void disableLizardMode() {
            // Enable esc, enter, cursors
            sendReportCommand(ReportCommand.SET_DEFAULT_DIGITAL_MAPPINGS, null);
            // Reset settings
            sendReportCommand(ReportCommand.LOAD_DEFAULT_SETTINGS, null);
        }

        @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
        public void rumble(short leftSpeed, short rightSpeed) {
            // Modeled after the rumble command in Linux's hid-steam driver:
            // https://github.com/torvalds/linux/blob/master/drivers/hid/hid-steam.c#L514-L555
            ByteBuffer rumbleData = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN);
            rumbleData.put((byte)0)   // Amplitude low byte
                    .put((byte)0)   // Amplitude high byte
                    .putShort(leftSpeed)
                    .putShort(rightSpeed)
                    .put((byte)2)   // Left gain
                    .put((byte)0);  // Right gain

            sendReportCommand(ReportCommand.TRIGGER_RUMBLE, rumbleData);
        }

        private void sendReportCommand(ReportCommand reportCommand, @Nullable ByteBuffer payload) {
            int payloadLength = 0;
            if (payload != null) {
                payload.rewind();
                payloadLength = payload.remaining();
            }

            byte[] command = new byte[3 + payloadLength];
            command[0] = FEATURE_REPORT_ID;
            command[1] = reportCommand.getValue();
            command[2] = (byte)(payloadLength + 1); // Length includes the length itself
            if (payload != null) {
                payload.get(command, 3, payloadLength);
            }

            queueGattOperation(GattOperation.writeCharacteristic(mGatt, reportCharacteristic, command));
        }

        @Override
        @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
        public void close() {
            BluetoothGatt g = mGatt;
            if (g != null) {
                if (getProductId() == D0G_BLE2_PID) {
                    disableLizardMode();
                }

                g.disconnect();
                g.close();
                mGatt = null;
            }
            mIsRegistered = false;
            mIsConnected = false;
            mOperations.clear();
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
                        LimeLog.severe("Unable to read characteristic " + mUuid.toString());
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
                        LimeLog.severe("Unable to write characteristic " + mUuid.toString());
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
                                LimeLog.severe("Unable to start notifications on input characteristic");
                                mResult = false;
                                return;
                            }

                            mGatt.setCharacteristicNotification(chr, true);
                            cccd.setValue(value);
                            if (!mGatt.writeDescriptor(cccd)) {
                                LimeLog.severe("Unable to write descriptor " + mUuid.toString());
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
        TRIGGER_RUMBLE((byte)0xEB);

        private final byte value;
        ReportCommand(byte value) {
            this.value = value;
        }

        public byte getValue() {
            return value;
        }
    }
}
