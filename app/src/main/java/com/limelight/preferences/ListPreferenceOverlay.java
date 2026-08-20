package com.limelight.preferences;

import android.text.TextUtils;

import androidx.annotation.NonNull;

import com.limelight.ui.AppDialog;

/** Application-owned choice overlay used by {@link CustomListPreference}. */
final class ListPreferenceOverlay {
    private ListPreferenceOverlay() {
    }

    static void show(@NonNull CustomListPreference preference) {
        CharSequence[] entries = preference.getEntries();
        CharSequence[] values = preference.getEntryValues();
        if (entries == null || values == null || entries.length == 0 ||
                entries.length != values.length) {
            return;
        }

        int selected = -1;
        for (int i = 0; i < values.length; i++) {
            if (TextUtils.equals(preference.getValue(), values[i].toString())) {
                selected = i;
                break;
            }
        }

        AppDialog.builder(preference.getContext())
                .setTitle(preference.getDialogTitle() != null
                        ? preference.getDialogTitle() : preference.getTitle())
                .setSingleChoiceItems(entries, selected,
                        (dialog, which) -> preference.selectValue(values[which].toString()))
                .show();
    }
}
