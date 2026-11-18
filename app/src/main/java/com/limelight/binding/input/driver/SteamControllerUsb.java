package com.limelight.binding.input.driver;

import android.hardware.usb.UsbConstants;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbEndpoint;
import android.hardware.usb.UsbInterface;
import android.os.SystemClock;

import com.limelight.LimeLog;

import java.util.ArrayList;
import java.util.List;

// Steam Controller (2026) over USB: the 2.4 GHz puck (one instance per controller slot) or a cable.
// Transport ported from SDL's HIDDeviceUSB.java; slot layout from SDL_hidapi_steam_triton.c.
public class SteamControllerUsb extends SteamTritonController {
    private static final int TRITON_WIRED_PID = 0x1302;
    private static final int PROTEUS_DONGLE_PID = 0x1304;
    private static final int NEREID_DONGLE_PID = 0x1305;

    private final UsbDeviceConnection connection;
    private final UsbInterface iface;
    private UsbEndpoint inEndpt, outEndpt;
    private Thread inputThread;
    private volatile boolean stopped;
    private boolean added;

    public static boolean canClaimDevice(UsbDevice device) {
        int pid = device.getProductId();
        return device.getVendorId() == VALVE_VID &&
                (pid == TRITON_WIRED_PID || pid == PROTEUS_DONGLE_PID || pid == NEREID_DONGLE_PID);
    }

    // Puck: each HID interface 2-5 is one controller slot. Cable: we don't know which HID
    // interface carries the controller, so take them all; only that one sends state reports.
    public static List<UsbInterface> controllerInterfaces(UsbDevice device) {
        boolean dongle = device.getProductId() != TRITON_WIRED_PID;
        List<UsbInterface> result = new ArrayList<>();
        for (int i = 0; i < device.getInterfaceCount(); i++) {
            UsbInterface iface = device.getInterface(i);
            if (iface.getInterfaceClass() == UsbConstants.USB_CLASS_HID &&
                    (!dongle || (iface.getId() >= 2 && iface.getId() <= 5))) {
                result.add(iface);
            }
        }
        return result;
    }

    public SteamControllerUsb(UsbDevice device, UsbDeviceConnection connection, UsbInterface iface,
                              int deviceId, UsbDriverListener listener) {
        super(deviceId, listener, device.getProductId());
        this.connection = connection;
        this.iface = iface;
    }

    @Override
    public boolean start() {
        if (!connection.claimInterface(iface, true)) {
            LimeLog.warning("Steam Controller USB: failed to claim interface " + iface.getId());
            return false;
        }
        for (int i = 0; i < iface.getEndpointCount(); i++) {
            UsbEndpoint e = iface.getEndpoint(i);
            if (e.getDirection() == UsbConstants.USB_DIR_IN && inEndpt == null) {
                inEndpt = e;
            }
            else if (e.getDirection() == UsbConstants.USB_DIR_OUT && outEndpt == null) {
                outEndpt = e;
            }
        }
        if (inEndpt == null) {
            connection.releaseInterface(iface);
            return false;
        }

        inputThread = new Thread(this::inputLoop, "SteamControllerUsb-" + iface.getId());
        inputThread.start();
        return true;
    }

    @Override
    public void stop() {
        if (stopped) {
            return;
        }
        stopped = true;
        if (inputThread != null) {
            inputThread.interrupt();
        }
        connection.releaseInterface(iface);
        connection.close();
        notifyDeviceRemoved();
    }

    @Override
    public void rumble(short lowFreqMotor, short highFreqMotor) {
        this.lowFreqMotor = lowFreqMotor;
        this.highFreqMotor = highFreqMotor;
        sendRumble();
    }

    private void sendRumble() {
        if (outEndpt == null) {
            return;
        }
        byte[] body = rumbleBody();
        byte[] report = new byte[body.length + 1];
        report[0] = (byte) ID_OUT_RUMBLE;
        System.arraycopy(body, 0, report, 1, body.length);
        connection.bulkTransfer(outEndpt, report, report.length, 100);
    }

    private void sendSettings() {
        byte[] report = new byte[SETTINGS.length + 2];
        report[0] = 1; // feature report ID
        System.arraycopy(SETTINGS, 0, report, 1, SETTINGS.length);
        connection.controlTransfer(UsbConstants.USB_TYPE_CLASS | 0x01 /* interface */ | UsbConstants.USB_DIR_OUT,
                0x09 /* SET_REPORT */, (3 /* feature */ << 8) | 1, iface.getId(),
                report, report.length, 1000);
    }

    private void inputLoop() {
        byte[] buffer = new byte[Math.max(64, inEndpt.getMaxPacketSize())];
        long lastSettings = 0, lastRumble = 0;

        while (!stopped && !Thread.currentThread().isInterrupted()) {
            long now = SystemClock.uptimeMillis();
            if (added && now - lastSettings >= SETTINGS_INTERVAL_MS) {
                sendSettings();
                lastSettings = now;
            }
            if ((lowFreqMotor != 0 || highFreqMotor != 0) && now - lastRumble >= RUMBLE_RESEND_MS) {
                sendRumble();
                lastRumble = now;
            }

            int res = connection.bulkTransfer(inEndpt, buffer, buffer.length, 100);
            if (res <= 0) {
                // Same heuristic as AbstractXboxController: an instant failure means the device is gone
                if (SystemClock.uptimeMillis() - now < 50 && !stopped) {
                    LimeLog.warning("Steam Controller USB: I/O error on interface " + iface.getId());
                    stop();
                }
                continue;
            }

            switch (buffer[0] & 0xFF) {
                case ID_STATE:
                case ID_STATE_BLE:
                case ID_STATE_TIMESTAMP:
                    if (!added) {
                        // First state report means a controller is really on this slot
                        added = true;
                        LimeLog.info("Steam Controller USB: controller on interface " + iface.getId());
                        notifyDeviceAdded();
                        sendSettings();
                        lastSettings = now;
                    }
                    handleState(buffer, 1, res - 1);
                    break;
                case ID_WIRELESS_STATUS:
                case ID_WIRELESS_STATUS_X:
                    // ponytail: the slot stays registered with the host while the puck is plugged in;
                    // we only release held inputs when the controller drops off
                    if (added && res > 1 && buffer[1] == WIRELESS_DISCONNECT) {
                        reportNeutral();
                    }
                    break;
                default:
                    break;
            }
        }
    }
}
