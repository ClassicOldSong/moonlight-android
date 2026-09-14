package com.limelight.utils;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.provider.Settings;
import android.view.InputDevice;
import android.view.accessibility.AccessibilityManager;

import com.limelight.BuildConfig;
import com.limelight.KeyboardAccessibilityService;

import java.util.List;

public final class KeyboardAccessibilityHelper {

    private KeyboardAccessibilityHelper() {}

    private static String getOurServiceId() {
        return BuildConfig.APPLICATION_ID + "/" + KeyboardAccessibilityService.class.getName();
    }

    public static boolean isKeyboardServiceEnabled(Context ctx) {
        AccessibilityManager am = (AccessibilityManager) ctx.getSystemService(Context.ACCESSIBILITY_SERVICE);
        if (am == null || !am.isEnabled()) {
            return false;
        }

        String ourId = getOurServiceId();
        List<android.accessibilityservice.AccessibilityServiceInfo> enabled =
                am.getEnabledAccessibilityServiceList(android.accessibilityservice.AccessibilityServiceInfo.FEEDBACK_ALL_MASK);
        if (enabled == null) {
            return false;
        }
        for (android.accessibilityservice.AccessibilityServiceInfo info : enabled) {
            if (ourId.equalsIgnoreCase(info.getId())) {
                return true;
            }
        }
        return false;
    }

    public static boolean hasPhysicalKeyboard(Context ctx) {
        for (int id : InputDevice.getDeviceIds()) {
            InputDevice device = InputDevice.getDevice(id);
            if (device == null || device.isVirtual()) {
                continue;
            }
            if ((device.getSources() & InputDevice.SOURCE_KEYBOARD) == InputDevice.SOURCE_KEYBOARD
                    && device.getKeyboardType() == InputDevice.KEYBOARD_TYPE_ALPHABETIC) {
                return true;
            }
        }
        return false;
    }

    public static void openAccessibilitySettings(Activity activity) {
        try {
            Intent intent = new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            activity.startActivity(intent);
        } catch (Exception ignored) {
        }
    }

    public static void openAppDetails(Activity activity) {
        try {
            Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
            intent.setData(Uri.fromParts("package", activity.getPackageName(), null));
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            activity.startActivity(intent);
        } catch (Exception ignored) {
        }
    }
}
