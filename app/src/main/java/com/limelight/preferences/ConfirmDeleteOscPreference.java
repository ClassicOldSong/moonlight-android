package com.limelight.preferences;

import static com.limelight.binding.input.virtual_controller.VirtualControllerConfigurationLoader.OSC_PREFERENCE;

import android.content.Context;
import android.util.AttributeSet;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.preference.DialogPreference;

import com.limelight.R;
import com.limelight.ui.AppDialog;

/** OSC reset confirmation rendered by the application rather than a framework dialog. */
public class ConfirmDeleteOscPreference extends DialogPreference {
    public ConfirmDeleteOscPreference(@NonNull Context context, @Nullable AttributeSet attrs,
                                      int defStyleAttr, int defStyleRes) {
        super(context, attrs, defStyleAttr, defStyleRes);
    }

    public ConfirmDeleteOscPreference(@NonNull Context context, @Nullable AttributeSet attrs,
                                      int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }

    public ConfirmDeleteOscPreference(@NonNull Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
    }

    public ConfirmDeleteOscPreference(@NonNull Context context) {
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
                            getContext().getSharedPreferences(OSC_PREFERENCE, Context.MODE_PRIVATE)
                                    .edit().clear().apply();
                            Toast.makeText(getContext(), R.string.toast_reset_osc_success,
                                    Toast.LENGTH_SHORT).show();
                            return true;
                        })
                .show();
    }
}
