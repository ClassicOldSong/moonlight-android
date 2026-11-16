package com.limelight;

import android.content.Context;
import android.content.SharedPreferences;
import android.view.InputDevice;

import androidx.preference.PreferenceManager;

import java.util.Locale;

/** Shared policy for physical hardware keys. Device names survive reconnects and ID changes. */
public final class InputRouting {
    public static final String AUTO = "auto";
    public static final String STREAM = "stream";
    public static final String TV = "tv";
    public static final String ALWAYS_STREAM = "input_routing_always_stream";
    public static final String ALWAYS_TV = "input_routing_always_tv";

    private InputRouting() {}

    public static String deviceKey(InputDevice device) {
        // Name-based storage also works when a Bluetooth device's descriptor changes.
        return "input_routing_device_" + device.getName();
    }

    public static boolean shouldSendToStream(Context context, InputDevice device) {
        if (device == null || device.isVirtual()) {
            return false;
        }
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        String choice = prefs.getString(deviceKey(device), AUTO);
        if (STREAM.equals(choice)) return true;
        if (TV.equals(choice)) return false;

        String name = device.getName().toLowerCase(Locale.ROOT);
        // Resolve overlapping text rules in favor of leaving keys with the TV.
        if (matches(name, prefs.getString(ALWAYS_TV, ""))) return false;
        if (matches(name, prefs.getString(ALWAYS_STREAM, ""))) return true;

        return device.supportsSource(InputDevice.SOURCE_JOYSTICK) ||
                device.getKeyboardType() == InputDevice.KEYBOARD_TYPE_ALPHABETIC;
    }

    private static boolean matches(String name, String patterns) {
        for (String pattern : patterns.split(",")) {
            String normalized = pattern.trim().toLowerCase(Locale.ROOT);
            if (!normalized.isEmpty() && name.contains(normalized)) return true;
        }
        return false;
    }
}
