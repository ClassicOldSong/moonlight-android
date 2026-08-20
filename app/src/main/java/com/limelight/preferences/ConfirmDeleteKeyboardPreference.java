package com.limelight.preferences;

import static com.limelight.binding.input.virtual_controller.keyboard.KeyBoardControllerConfigurationLoader.OSC_PREFERENCE;
import static com.limelight.binding.input.virtual_controller.keyboard.KeyBoardControllerConfigurationLoader.OSC_PREFERENCE_VALUE;

import android.content.Context;
import android.util.AttributeSet;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.preference.DialogPreference;
import androidx.preference.PreferenceManager;

import com.limelight.R;
import com.limelight.ui.AppDialog;

/** Keyboard layout reset confirmation rendered inside the host activity. */
public class ConfirmDeleteKeyboardPreference extends DialogPreference {
    public ConfirmDeleteKeyboardPreference(@NonNull Context context, @Nullable AttributeSet attrs,
                                           int defStyleAttr, int defStyleRes) {
        super(context, attrs, defStyleAttr, defStyleRes);
    }

    public ConfirmDeleteKeyboardPreference(@NonNull Context context, @Nullable AttributeSet attrs,
                                           int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }

    public ConfirmDeleteKeyboardPreference(@NonNull Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
    }

    public ConfirmDeleteKeyboardPreference(@NonNull Context context) {
        super(context);
    }

    @Override
    protected void onClick() {
        AppDialog.builder(getContext())
                .setTitle(getDialogTitle() != null ? getDialogTitle() : getTitle())
                .setMessage(getDialogMessage())
                .setNegativeButton(getNegativeButtonText() != null
                                ? getNegativeButtonText() : getContext().getText(R.string.no),
                        dialog -> true)
                .setPositiveButton(getPositiveButtonText() != null
                                ? getPositiveButtonText() : getContext().getText(R.string.yes),
                        dialog -> {
                            String name = PreferenceManager.getDefaultSharedPreferences(getContext())
                                    .getString(OSC_PREFERENCE, OSC_PREFERENCE_VALUE);
                            getContext().getSharedPreferences(name, Context.MODE_PRIVATE)
                                    .edit().clear().apply();
                            Toast.makeText(getContext(), R.string.toast_reset_osc_success,
                                    Toast.LENGTH_SHORT).show();
                            return true;
                        })
                .show();
    }
}
