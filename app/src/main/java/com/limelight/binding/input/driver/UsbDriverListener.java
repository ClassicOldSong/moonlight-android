package com.limelight.binding.input.driver;

public interface UsbDriverListener {
    void reportControllerState(int controllerId, int buttonFlags,
                               float leftStickX, float leftStickY,
                               float rightStickX, float rightStickY,
                               float leftTrigger, float rightTrigger);
    void reportControllerMotion(int controllerId, byte motionType, float motionX, float motionY, float motionZ);

    // A controller surface used as a mouse (e.g. the Steam Controller's right trackpad)
    default void reportControllerMouse(int controllerId, short deltaX, short deltaY, boolean leftButton) {}

    void deviceRemoved(AbstractController controller);
    void deviceAdded(AbstractController controller);
}
