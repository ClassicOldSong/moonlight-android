package com.limelight.preferences;

import android.os.Bundle;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.SeekBar;

import androidx.appcompat.app.AppCompatActivity;
import androidx.preference.PreferenceFragmentCompat;

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
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

@Config(sdk = {33})
@RunWith(RobolectricTestRunner.class)
public class CustomPreferenceOverlayTest {
    @BeforeClass
    public static void suppressLogs() {
        TestLogSuppressor.install();
    }

    @Test
    public void seekBarRejectsThenAcceptsValueWithoutSystemDialog() {
        Fixture fixture = createFixture();
        SeekBarPreference preference =
                fixture.fragment.findPreference("seekbar_resolution_scale_factor");
        assertNotNull(preference);
        preference.setProgress(100);
        preference.setOnPreferenceChangeListener((changed, value) -> false);

        preference.showDialog();
        SeekBar seekBar = fixture.activity.findViewById(R.id.app_dialog_seekbar);
        seekBar.setProgress(130); // 20 minimum + 130 progress = 150
        positiveButton(fixture.activity).performClick();

        assertEquals(100, preference.getProgress());
        assertNotNull(fixture.activity.findViewById(R.id.app_dialog_overlay));

        preference.setOnPreferenceChangeListener((changed, value) -> true);
        positiveButton(fixture.activity).performClick();

        assertEquals(150, preference.getProgress());
        assertNull(fixture.activity.findViewById(R.id.app_dialog_overlay));
    }

    @Test
    public void editTextRejectsThenAcceptsValueWithoutSystemDialog() {
        Fixture fixture = createFixture();
        CustomEditTextPreference preference = fixture.fragment.findPreference("edit_diy_w_h");
        assertNotNull(preference);
        preference.setText("1920x1080");
        preference.setOnPreferenceChangeListener((changed, value) -> false);

        preference.onClick();
        EditText editText = fixture.activity.findViewById(R.id.app_dialog_edit_text);
        editText.setText("2560x1440");
        positiveButton(fixture.activity).performClick();

        assertEquals("1920x1080", preference.getText());
        assertNotNull(fixture.activity.findViewById(R.id.app_dialog_overlay));

        preference.setOnPreferenceChangeListener((changed, value) -> true);
        positiveButton(fixture.activity).performClick();

        assertEquals("2560x1440", preference.getText());
        assertNull(fixture.activity.findViewById(R.id.app_dialog_overlay));
    }

    private static android.view.View positiveButton(TestActivity activity) {
        LinearLayout actions = activity.findViewById(R.id.app_dialog_actions);
        return actions.getChildAt(actions.getChildCount() - 1);
    }

    private static Fixture createFixture() {
        ActivityController<TestActivity> controller =
                Robolectric.buildActivity(TestActivity.class).setup();
        TestActivity activity = controller.get();
        TestPreferenceFragment fragment = new TestPreferenceFragment();
        activity.getSupportFragmentManager().beginTransaction()
                .replace(android.R.id.content, fragment)
                .commitNow();
        return new Fixture(activity, fragment);
    }

    private static final class Fixture {
        final TestActivity activity;
        final TestPreferenceFragment fragment;

        Fixture(TestActivity activity, TestPreferenceFragment fragment) {
            this.activity = activity;
            this.fragment = fragment;
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
