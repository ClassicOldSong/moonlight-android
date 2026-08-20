package com.limelight.preferences;

import android.content.Context;
import android.util.AttributeSet;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.preference.ListPreference;

/**
 * A {@link ListPreference} whose choices are rendered inside the host activity.
 *
 * <p>The stock AndroidX implementation delegates its UI to an AlertDialog. Some vendor Android
 * builds (particularly projector firmware) fail to render the dialog's single-choice list. This
 * class deliberately keeps ListPreference's data and persistence behavior, while replacing that
 * dialog with an application-owned view overlay.</p>
 */
public class CustomListPreference extends ListPreference {
    public CustomListPreference(@NonNull Context context, @Nullable AttributeSet attrs,
                                int defStyleAttr, int defStyleRes) {
        super(context, attrs, defStyleAttr, defStyleRes);
    }

    public CustomListPreference(@NonNull Context context, @Nullable AttributeSet attrs,
                                int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }

    public CustomListPreference(@NonNull Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
    }

    public CustomListPreference(@NonNull Context context) {
        super(context);
    }

    @Override
    protected void onClick() {
        ListPreferenceOverlay.show(this);
    }

    void selectValue(String value) {
        if (callChangeListener(value)) {
            setValue(value);
        }
    }
}
