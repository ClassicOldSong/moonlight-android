package com.limelight.binding.input.driver;

import android.Manifest;
import android.app.Service;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.os.*;

import android.util.Log;
import androidx.annotation.RequiresPermission;
import com.limelight.LimeLog;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

public class BluetoothDriverService extends Service {

    private static final String TAG = "bluetoothdriver";
    private final HashMap<BluetoothDevice, SteamController> mBluetoothDevices = new HashMap<>();
    private boolean started;
    private boolean mIsChromebook;
    private Handler mHandler;
    private BluetoothManager mBluetoothManager;
    private List<BluetoothDevice> mLastBluetoothDevices;
    private final BroadcastReceiver mBluetoothBroadcast = new BluetoothEventReceiver();
    private final BluetoothDriverBinder binder = new BluetoothDriverBinder();

    private ControllerDriverListener listener;

    @Override
    public void onCreate() {
        super.onCreate();
        mIsChromebook = getPackageManager().hasSystemFeature("org.chromium.arc.device_management");
    }

    @Override
    public IBinder onBind(Intent intent) {
        return binder;
    }

    private void initializeBluetooth() {
        //Log.d(TAG, "Initializing Bluetooth");

        if (started) {
            return;
        }
        started = true;

        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.R &&
                getPackageManager().checkPermission(android.Manifest.permission.BLUETOOTH, getPackageName()) != PackageManager.PERMISSION_GRANTED) {
            LimeLog.warning("Couldn't initialize Bluetooth, missing android.permission.BLUETOOTH");
            return;
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                getPackageManager().checkPermission(android.Manifest.permission.BLUETOOTH_CONNECT, getPackageName()) != PackageManager.PERMISSION_GRANTED) {
            LimeLog.warning("Couldn't initialize Bluetooth, missing android.permission.BLUETOOTH_CONNECT");
            return;
        }

        if (!getPackageManager().hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE)) {
            LimeLog.warning("Couldn't initialize Bluetooth, this version of Android does not support Bluetooth LE");
            return;
        }

        // Find bonded bluetooth controllers and create SteamControllers for them
        mBluetoothManager = (BluetoothManager)getSystemService(Context.BLUETOOTH_SERVICE);
        if (mBluetoothManager == null) {
            // This device doesn't support Bluetooth.
            return;
        }

        BluetoothAdapter btAdapter = mBluetoothManager.getAdapter();
        if (btAdapter == null) {
            // This device has Bluetooth support in the codebase, but has no available adapters.
            return;
        }

        // Get our bonded devices.
        for (BluetoothDevice device : btAdapter.getBondedDevices()) {
            LimeLog.info("Bluetooth device available: " + device);
            if (isSteamController(device)) {
                connectBluetoothDevice(device);
            }

        }

        // NOTE: These don't work on Chromebooks, to my undying dismay.
        IntentFilter filter = new IntentFilter();
        filter.addAction(BluetoothDevice.ACTION_ACL_CONNECTED);
        filter.addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED);
        if (Build.VERSION.SDK_INT >= 33) { /* Android 13.0 (TIRAMISU) */
            registerReceiver(mBluetoothBroadcast, filter, Context.RECEIVER_EXPORTED);
        } else {
            registerReceiver(mBluetoothBroadcast, filter);
        }

        if (mIsChromebook) {
            mHandler = new Handler(Looper.getMainLooper());
            mLastBluetoothDevices = new ArrayList<>();

            // final HIDDeviceManager finalThis = this;
            // mHandler.postDelayed(new Runnable() {
            //     @Override
            //     public void run() {
            //         finalThis.chromebookConnectionHandler();
            //     }
            // }, 5000);
        }
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    private void shutdownBluetooth() {
        if (!started) {
            return;
        }
        started = false;

        // Stop the attachment receiver
        unregisterReceiver(mBluetoothBroadcast);

        // Stop all controllers
        while (!mBluetoothDevices.isEmpty()) {
            // Stop and remove the controller
            disconnectBluetoothDevice(mBluetoothDevices.keySet().iterator().next());
        }
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    public void connectBluetoothDevice(BluetoothDevice bluetoothDevice) {
        synchronized (this) {
            if (mBluetoothDevices.containsKey(bluetoothDevice)) {
                LimeLog.info("Steam controller with address " + bluetoothDevice + " already exists, attempting reconnect");

                SteamController device = mBluetoothDevices.get(bluetoothDevice);
                device.reconnect();
            }
            SteamController device = new SteamController(listener, this, UsbDriverService.getNextDeviceId(), bluetoothDevice);
            mBluetoothDevices.put(bluetoothDevice, device);
            device.start();
        }
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    public void disconnectBluetoothDevice(BluetoothDevice bluetoothDevice) {
        synchronized (this) {
            SteamController device = mBluetoothDevices.get(bluetoothDevice);
            if (device == null)
                return;

            device.stop();
            mBluetoothDevices.remove(bluetoothDevice);
        }
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    public boolean isSteamController(BluetoothDevice bluetoothDevice) {
        // Sanity check.  If you pass in a null device, by definition it is never a Steam Controller.
        if (bluetoothDevice == null) {
            return false;
        }

        // If the device has no local name, we really don't want to try an equality check against it.
        if (bluetoothDevice.getName() == null) {
            return false;
        }

        // Steam Controllers will always support Bluetooth Low Energy
        if ((bluetoothDevice.getType() & BluetoothDevice.DEVICE_TYPE_LE) == 0) {
            return false;
        }

        // Match on the name either the original Steam Controller or the new second-generation one advertise with.
        return bluetoothDevice.getName().equals("SteamController") || bluetoothDevice.getName().startsWith("Steam Ctrl");
    }
    
    @Override
    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    public void onDestroy() {
        shutdownBluetooth();
    }

    public class BluetoothDriverBinder extends Binder {
        public void start() {
            BluetoothDriverService.this.initializeBluetooth();
        }

        @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
        public void stop() {
            BluetoothDriverService.this.shutdownBluetooth();
        }

        public void setListener(ControllerDriverListener listener) {
            BluetoothDriverService.this.listener = listener;

            // Report all controllerMap that already exist
            if (listener != null) {
                for (AbstractController controller : mBluetoothDevices.values()) {
                    listener.deviceAdded(controller);
                }
            }
        }
    }

    public class BluetoothEventReceiver extends BroadcastReceiver {
        @Override
        @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            // Bluetooth device was connected. If it was a Steam Controller, handle it
            if (BluetoothDevice.ACTION_ACL_CONNECTED.equals(action)) {
                BluetoothDevice device = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
                Log.d(TAG, "Bluetooth device connected: " + device);

                if (isSteamController(device)) {
                    connectBluetoothDevice(device);
                }
            }

            // Bluetooth device was disconnected, remove from controller manager (if any)
            if (BluetoothDevice.ACTION_ACL_DISCONNECTED.equals(action)) {
                BluetoothDevice device = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
                Log.d(TAG, "Bluetooth device disconnected: " + device);

                disconnectBluetoothDevice(device);
            }
        }
    }
}
