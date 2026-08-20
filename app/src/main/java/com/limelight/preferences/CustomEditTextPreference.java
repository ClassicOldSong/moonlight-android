package com.limelight.preferences;

import android.content.Context;
import android.util.AttributeSet;
import android.view.inputmethod.InputMethodManager;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.widget.AppCompatEditText;
import androidx.preference.EditTextPreference;

import com.limelight.R;
import com.limelight.ui.AppDialog;

/** EditTextPreference backed by an in-activity {@link AppDialog}. */
public class CustomEditTextPreference extends EditTextPreference {
    private static final String ANDROID_SCHEMA_URL = "http://schemas.android.com/apk/res/android";

    private OnBindEditTextListener bindEditTextListener;
    private final boolean singleLine;

    public CustomEditTextPreference(@NonNull Context context, @Nullable AttributeSet attrs,
                                    int defStyleAttr, int defStyleRes) {
        super(context, attrs, defStyleAttr, defStyleRes);
        singleLine = attrs != null &&
                attrs.getAttributeBooleanValue(ANDROID_SCHEMA_URL, "singleLine", false);
    }

    public CustomEditTextPreference(@NonNull Context context, @Nullable AttributeSet attrs,
                                    int defStyleAttr) {
        this(context, attrs, defStyleAttr, 0);
    }

    public CustomEditTextPreference(@NonNull Context context, @Nullable AttributeSet attrs) {
        this(context, attrs, androidx.preference.R.attr.editTextPreferenceStyle);
    }

    public CustomEditTextPreference(@NonNull Context context) {
        this(context, null);
    }

    @Override
    public void setOnBindEditTextListener(@Nullable OnBindEditTextListener listener) {
        super.setOnBindEditTextListener(listener);
        bindEditTextListener = listener;
    }

    @Override
    protected void onClick() {
        AppCompatEditText editText = (AppCompatEditText) AppDialog.inflateContent(
                getContext(), R.layout.app_dialog_edit_text_content);
        editText.setSingleLine(singleLine);
        editText.setText(getText());
        if (bindEditTextListener != null) {
            bindEditTextListener.onBindEditText(editText);
        }
        editText.setSelection(editText.length());

        AppDialog.builder(getContext())
                .setTitle(getDialogTitle() != null ? getDialogTitle() : getTitle())
                .setMessage(getDialogMessage())
                .setView(editText)
                .setNegativeButton(getNegativeButtonText() != null
                        ? getNegativeButtonText() : getContext().getText(R.string.cancel),
                        dialog -> true)
                .setPositiveButton(getPositiveButtonText() != null
                        ? getPositiveButtonText() : getContext().getText(android.R.string.ok),
                        dialog -> {
                            String value = editText.getText() == null
                                    ? "" : editText.getText().toString();
                            if (!callChangeListener(value)) {
                                return false;
                            }
                            setText(value);
                            return true;
                        })
                .show();

        editText.post(() -> {
            editText.requestFocus();
            InputMethodManager inputMethodManager = (InputMethodManager)
                    getContext().getSystemService(Context.INPUT_METHOD_SERVICE);
            if (inputMethodManager != null) {
                inputMethodManager.showSoftInput(editText, InputMethodManager.SHOW_IMPLICIT);
            }
        });
    }
}
