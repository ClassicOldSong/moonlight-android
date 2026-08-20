package com.limelight.preferences;

import android.content.Context;
import android.util.AttributeSet;
import android.view.View;
import android.widget.SeekBar;
import android.widget.TextView;

import androidx.preference.Preference;

import com.limelight.R;
import com.limelight.ui.AppDialog;

import java.util.Locale;

/**
 * Integer preference edited with an application-owned slider overlay.
 */
public class SeekBarPreference extends Preference {
    private static final String ANDROID_SCHEMA_URL = "http://schemas.android.com/apk/res/android";
    private static final String SEEKBAR_SCHEMA_URL =
            "http://schemas.moonlight-stream.com/apk/res/seekbar";

    private final Context context;
    private final String dialogMessage;
    private final String suffix;
    private final int defaultValue;
    private final int maxValue;
    private final int minValue;
    private final int stepSize;
    private final int keyStepSize;
    private final int divisor;
    private int currentValue;

    public SeekBarPreference(Context context, AttributeSet attrs) {
        super(context, attrs);
        this.context = context;

        int dialogMessageId =
                attrs.getAttributeResourceValue(ANDROID_SCHEMA_URL, "dialogMessage", 0);
        dialogMessage = dialogMessageId == 0
                ? attrs.getAttributeValue(ANDROID_SCHEMA_URL, "dialogMessage")
                : context.getString(dialogMessageId);

        int suffixId = attrs.getAttributeResourceValue(ANDROID_SCHEMA_URL, "text", 0);
        suffix = suffixId == 0
                ? attrs.getAttributeValue(ANDROID_SCHEMA_URL, "text")
                : context.getString(suffixId);

        defaultValue = attrs.getAttributeIntValue(ANDROID_SCHEMA_URL, "defaultValue",
                PreferenceConfiguration.getDefaultBitrate(context));
        maxValue = attrs.getAttributeIntValue(ANDROID_SCHEMA_URL, "max", 100);
        minValue = attrs.getAttributeIntValue(SEEKBAR_SCHEMA_URL, "min", 1);
        stepSize = Math.max(1,
                attrs.getAttributeIntValue(SEEKBAR_SCHEMA_URL, "step", 1));
        divisor = Math.max(1,
                attrs.getAttributeIntValue(SEEKBAR_SCHEMA_URL, "divisor", 1));
        keyStepSize = attrs.getAttributeIntValue(SEEKBAR_SCHEMA_URL, "keyStep", 0);
        currentValue = defaultValue;
    }

    @Override
    protected void onSetInitialValue(boolean restorePersistedValue, Object defaultValue) {
        super.onSetInitialValue(restorePersistedValue, defaultValue);
        if (restorePersistedValue) {
            currentValue = shouldPersist() ? getPersistedInt(this.defaultValue) : this.defaultValue;
        } else if (defaultValue instanceof Integer) {
            currentValue = (Integer) defaultValue;
        }
    }

    @Override
    protected void onClick() {
        showDialog();
    }

    public void showDialog() {
        if (shouldPersist()) {
            currentValue = getPersistedInt(defaultValue);
        }

        View content = AppDialog.inflateContent(context, R.layout.app_dialog_seekbar_content);
        TextView valueText = content.findViewById(R.id.app_dialog_seekbar_value);
        SeekBar seekBar = content.findViewById(R.id.app_dialog_seekbar);
        seekBar.setMax(maxValue - minValue);
        if (keyStepSize != 0) {
            seekBar.setKeyProgressIncrement(keyStepSize);
        }
        seekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar changedSeekBar, int progress, boolean fromUser) {
                int absoluteValue = progress + minValue;
                int roundedValue = Math.round((float) absoluteValue / stepSize) * stepSize;
                roundedValue = Math.max(minValue, Math.min(maxValue, roundedValue));
                if (roundedValue != absoluteValue) {
                    changedSeekBar.setProgress(roundedValue - minValue);
                    return;
                }
                valueText.setText(formatValue(roundedValue));
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
            }
        });
        seekBar.setProgress(Math.max(0, Math.min(maxValue - minValue,
                currentValue - minValue)));
        valueText.setText(formatValue(seekBar.getProgress() + minValue));

        AppDialog.builder(context)
                .setTitle(getTitle())
                .setMessage(dialogMessage)
                .setView(content)
                .setNegativeButton(R.string.cancel, dialog -> true)
                .setPositiveButton(android.R.string.ok, dialog -> {
                    int newValue = seekBar.getProgress() + minValue;
                    if (!callChangeListener(newValue)) {
                        return false;
                    }
                    currentValue = newValue;
                    if (shouldPersist()) {
                        persistInt(currentValue);
                    }
                    notifyChanged();
                    return true;
                })
                .show();
    }

    public void setProgress(int progress) {
        currentValue = Math.max(minValue, Math.min(maxValue, progress));
    }

    public int getProgress() {
        return currentValue;
    }

    private String formatValue(int value) {
        String text = divisor == 1
                ? String.valueOf(value)
                : String.format((Locale) null, "%.1f", value / (float) divisor);
        if (suffix == null) {
            return text;
        }
        return text.concat(suffix.length() > 1 ? " " + suffix : suffix);
    }
}
