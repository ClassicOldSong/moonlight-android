package com.limelight.binding.audio;

public enum AudioOutputMode {
    PCM("pcm"),
    AC3_AUTO("ac3_auto"),
    AC3_FORCE("ac3_force"),
    EAC3_AUTO("eac3_auto"),
    EAC3_FORCE("eac3_force");

    private final String preferenceValue;

    AudioOutputMode(String preferenceValue) {
        this.preferenceValue = preferenceValue;
    }

    public String getPreferenceValue() {
        return preferenceValue;
    }

    public boolean isEncoded() {
        return this != PCM;
    }

    public boolean isEac3() {
        return this == EAC3_AUTO || this == EAC3_FORCE;
    }

    public boolean isAutomatic() {
        return this == AC3_AUTO || this == EAC3_AUTO;
    }

    public static AudioOutputMode fromPreference(String value) {
        if (value != null) {
            for (AudioOutputMode mode : values()) {
                if (mode.preferenceValue.equals(value)) {
                    return mode;
                }
            }
        }
        return PCM;
    }
}
