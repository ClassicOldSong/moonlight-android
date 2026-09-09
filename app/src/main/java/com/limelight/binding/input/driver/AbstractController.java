package com.limelight.binding.input.driver;

import com.limelight.nvstream.jni.MoonBridge;

public abstract class AbstractController {

    private final int deviceId;
    private final int vendorId;
    private final int productId;

    private ControllerDriverListener listener;

    protected int buttonFlags, supportedButtonFlags;
    protected float leftTrigger, rightTrigger;
    protected float rightStickX, rightStickY;
    protected float leftStickX, leftStickY;
    protected float gyroX, gyroY, gyroZ;
    protected float accelX, accelY, accelZ;
    protected short capabilities;
    protected byte type;

    public int getControllerId() {
        return deviceId;
    }

    public int getVendorId() {
        return vendorId;
    }

    public int getProductId() {
        return productId;
    }

    public int getSupportedButtonFlags() {
        return supportedButtonFlags;
    }

    public short getCapabilities() {
        return capabilities;
    }

    public byte getType() {
        return type;
    }

    protected void setButtonFlag(int buttonFlag, int data) {
        if (data != 0) {
            buttonFlags |= buttonFlag;
        } else {
            buttonFlags &= ~buttonFlag;
        }
    }

    protected void reportInput() {
        listener.reportControllerState(deviceId, buttonFlags, leftStickX, leftStickY,
                rightStickX, rightStickY, leftTrigger, rightTrigger);
    }

    // New method to report motion events
    protected void reportMotion() {
        listener.reportControllerMotion(deviceId, MoonBridge.LI_MOTION_TYPE_GYRO, gyroX, gyroY, gyroZ);
        listener.reportControllerMotion(deviceId, MoonBridge.LI_MOTION_TYPE_ACCEL, accelX, accelY, accelZ);
    }

    protected void reportBatteryState(byte batteryState, byte batteryPercentage) {
        listener.reportBatteryState(deviceId, batteryState, batteryPercentage);
    }

    public abstract boolean start();

    public abstract void stop();

    public AbstractController(int deviceId, ControllerDriverListener listener, int vendorId, int productId) {
        this.deviceId = deviceId;
        this.listener = listener;
        this.vendorId = vendorId;
        this.productId = productId;
    }

    public void setMotionEventState(byte motionType, short reportRateHz) {
        if (((getCapabilities() & MoonBridge.LI_CCAP_GYRO) | (getCapabilities() & MoonBridge.LI_CCAP_ACCEL)) != 0) {
            throw new IllegalStateException("Controllers with motion capabilities must override this method");
        }
    }

    public void setControllerLED(byte r, byte g, byte b) {
        if ((getCapabilities() & MoonBridge.LI_CCAP_RGB_LED) != 0) {
            throw new IllegalStateException("Controllers with RGB LED capability must override this method");
        }
    }

    public abstract void rumble(short lowFreqMotor, short highFreqMotor);

    public abstract void rumbleTriggers(short leftTrigger, short rightTrigger);

    protected void notifyDeviceRemoved() {
        listener.deviceRemoved(this);
    }

    protected void notifyDeviceAdded() {
        listener.deviceAdded(this);
    }
}
