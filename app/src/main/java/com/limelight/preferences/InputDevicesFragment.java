package com.limelight.preferences;

import android.content.Context;
import android.hardware.input.InputManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.InputDevice;

import androidx.preference.EditTextPreference;
import androidx.preference.ListPreference;
import androidx.preference.Preference;
import androidx.preference.PreferenceCategory;
import androidx.preference.PreferenceFragmentCompat;
import androidx.preference.PreferenceScreen;

import com.limelight.InputRouting;
import com.limelight.R;

/** Shows connected devices while keeping disconnected devices' choices in preferences. */
public class InputDevicesFragment extends PreferenceFragmentCompat
        implements InputManager.InputDeviceListener {
    private InputManager inputManager;
    private PreferenceCategory devices;

    @Override
    public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
        inputManager = (InputManager) requireContext().getSystemService(Context.INPUT_SERVICE);
        PreferenceScreen screen = getPreferenceManager().createPreferenceScreen(requireContext());
        screen.setTitle(R.string.input_devices_title);
        setPreferenceScreen(screen);
        addPatterns(screen, InputRouting.ALWAYS_STREAM, R.string.input_routing_always_stream);
        addPatterns(screen, InputRouting.ALWAYS_TV, R.string.input_routing_always_tv);
        devices = new PreferenceCategory(requireContext());
        devices.setTitle(R.string.input_routing_connected);
        screen.addPreference(devices);
        refreshDevices();
    }

    private void addPatterns(PreferenceScreen screen, String key, int title) {
        EditTextPreference preference = new EditTextPreference(requireContext());
        preference.setKey(key);
        preference.setTitle(title);
        preference.setDialogTitle(title);
        preference.setSummary(R.string.input_routing_patterns_summary);
        preference.setOnBindEditTextListener(editText ->
                editText.setInputType(InputType.TYPE_CLASS_TEXT));
        screen.addPreference(preference);
    }

    private void refreshDevices() {
        devices.removeAll();
        for (int id : inputManager.getInputDeviceIds()) {
            InputDevice device = inputManager.getInputDevice(id);
            if (device == null || device.isVirtual()) continue;
            ListPreference row = new ListPreference(requireContext());
            row.setKey(InputRouting.deviceKey(device));
            row.setTitle(device.getName());
            row.setDialogTitle(device.getName());
            row.setEntries(new CharSequence[] {
                    getString(R.string.input_routing_auto),
                    getString(R.string.input_routing_stream),
                    getString(R.string.input_routing_tv)});
            row.setEntryValues(new CharSequence[] {
                    InputRouting.AUTO, InputRouting.STREAM, InputRouting.TV});
            row.setDefaultValue(InputRouting.AUTO);
            row.setSummaryProvider(ListPreference.SimpleSummaryProvider.getInstance());
            devices.addPreference(row);
        }
        if (devices.getPreferenceCount() == 0) {
            Preference empty = new Preference(requireContext());
            empty.setTitle(R.string.input_routing_empty);
            empty.setSelectable(false);
            devices.addPreference(empty);
        }
    }

    @Override
    public void onResume() {
        super.onResume();
        inputManager.registerInputDeviceListener(this, new Handler(Looper.getMainLooper()));
        refreshDevices();
    }

    @Override
    public void onPause() {
        inputManager.unregisterInputDeviceListener(this);
        super.onPause();
    }

    @Override
    public void onInputDeviceAdded(int deviceId) { refreshDevices(); }

    @Override
    public void onInputDeviceRemoved(int deviceId) { refreshDevices(); }

    @Override
    public void onInputDeviceChanged(int deviceId) { refreshDevices(); }
}
