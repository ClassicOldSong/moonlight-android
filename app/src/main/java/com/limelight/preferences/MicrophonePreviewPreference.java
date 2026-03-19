package com.limelight.preferences;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.util.AttributeSet;
import android.widget.ProgressBar;

import androidx.annotation.NonNull;
import androidx.preference.Preference;
import androidx.preference.PreferenceViewHolder;

import com.limelight.R;

public class MicrophonePreviewPreference extends Preference {
    private int levelPercent;
    private boolean signalDetected;

    public MicrophonePreviewPreference(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public MicrophonePreviewPreference(Context context) {
        super(context);
        init();
    }

    private void init() {
        setSelectable(false);
        setWidgetLayoutResource(R.layout.preference_microphone_preview_widget);
        setSummary(R.string.microphone_preview_inactive);
    }

    public void updatePreviewState(String status, double level, boolean detected) {
        levelPercent = Math.max(0, Math.min(100, (int) Math.round(level * 100.0)));
        signalDetected = detected;
        setSummary(status + "\n" + getContext().getString(
                detected ? R.string.microphone_preview_signal_detected :
                        R.string.microphone_preview_signal_missing));
        notifyChanged();
    }

    @Override
    public void onBindViewHolder(@NonNull PreferenceViewHolder holder) {
        super.onBindViewHolder(holder);

        ProgressBar previewBar = (ProgressBar) holder.findViewById(R.id.microphone_preview_bar);
        if (previewBar != null) {
            previewBar.setProgress(levelPercent);
            previewBar.setProgressTintList(ColorStateList.valueOf(Color.parseColor(
                    signalDetected ? "#45c486" : "#5f6f86")));
        }
    }
}
