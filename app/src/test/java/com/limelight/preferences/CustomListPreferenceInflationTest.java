package com.limelight.preferences;

import android.os.Bundle;
import android.widget.FrameLayout;

import androidx.appcompat.app.AppCompatActivity;
import androidx.preference.EditTextPreference;
import androidx.preference.ListPreference;
import androidx.preference.Preference;
import androidx.preference.PreferenceFragmentCompat;
import androidx.preference.PreferenceGroup;

import com.limelight.R;
import com.limelight.TestLogSuppressor;

import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;

import static org.junit.Assert.assertEquals;

@Config(sdk = {33})
@RunWith(RobolectricTestRunner.class)
public class CustomListPreferenceInflationTest {
    @BeforeClass
    public static void suppressLogs() {
        TestLogSuppressor.install();
    }

    @Test
    public void everyDialogBackedPreferenceInflatesAsApplicationOwnedComponent() {
        ActivityController<TestActivity> controller =
                Robolectric.buildActivity(TestActivity.class).setup();
        TestActivity activity = controller.get();
        TestPreferenceFragment fragment = new TestPreferenceFragment();

        activity.getSupportFragmentManager().beginTransaction()
                .replace(android.R.id.content, fragment)
                .commitNow();

        Counts counts = countPreferences(fragment.getPreferenceScreen());
        assertEquals(11, counts.listPreferences);
        assertEquals(11, counts.customListPreferences);
        assertEquals(3, counts.editTextPreferences);
        assertEquals(3, counts.customEditTextPreferences);
        assertEquals(17, counts.customSeekBarPreferences);
    }

    private static Counts countPreferences(Preference preference) {
        Counts counts = new Counts();
        if (preference instanceof ListPreference) {
            counts.listPreferences++;
        }
        if (preference instanceof CustomListPreference) {
            counts.customListPreferences++;
        }
        if (preference instanceof EditTextPreference) {
            counts.editTextPreferences++;
        }
        if (preference instanceof CustomEditTextPreference) {
            counts.customEditTextPreferences++;
        }
        if (preference instanceof SeekBarPreference) {
            counts.customSeekBarPreferences++;
        }
        if (preference instanceof PreferenceGroup) {
            PreferenceGroup group = (PreferenceGroup) preference;
            for (int i = 0; i < group.getPreferenceCount(); i++) {
                counts.add(countPreferences(group.getPreference(i)));
            }
        }
        return counts;
    }

    private static final class Counts {
        int listPreferences;
        int customListPreferences;
        int editTextPreferences;
        int customEditTextPreferences;
        int customSeekBarPreferences;

        void add(Counts other) {
            listPreferences += other.listPreferences;
            customListPreferences += other.customListPreferences;
            editTextPreferences += other.editTextPreferences;
            customEditTextPreferences += other.customEditTextPreferences;
            customSeekBarPreferences += other.customSeekBarPreferences;
        }
    }

    public static class TestPreferenceFragment extends PreferenceFragmentCompat {
        @Override
        public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
            setPreferencesFromResource(R.xml.preferences, rootKey);
        }
    }

    public static class TestActivity extends AppCompatActivity {
        @Override
        protected void onCreate(Bundle savedInstanceState) {
            setTheme(R.style.SettingsTheme);
            super.onCreate(savedInstanceState);
            setContentView(new FrameLayout(this));
        }
    }
}
