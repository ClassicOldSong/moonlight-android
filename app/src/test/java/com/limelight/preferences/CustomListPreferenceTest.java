package com.limelight.preferences;

import android.os.Looper;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;

import androidx.appcompat.app.AppCompatActivity;

import com.limelight.R;
import com.limelight.TestLogSuppressor;

import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;

@Config(sdk = {33})
@RunWith(RobolectricTestRunner.class)
public class CustomListPreferenceTest {
    @BeforeClass
    public static void suppressLogs() {
        TestLogSuppressor.install();
    }

    @Test
    public void clickAttachesOverlayToActivityContent() {
        ActivityController<TestActivity> controller =
                Robolectric.buildActivity(TestActivity.class).setup();
        TestActivity activity = controller.get();
        CustomListPreference preference = createPreference(activity);

        preference.onClick();
        Shadows.shadowOf(Looper.getMainLooper()).idle();

        View overlay = activity.findViewById(R.id.app_dialog_overlay);
        assertEquals(View.VISIBLE, overlay.getVisibility());
        assertSame(activity.findViewById(android.R.id.content), overlay.getParent());
    }

    @Test
    public void choosingItemRunsListenerAndUpdatesValue() {
        ActivityController<TestActivity> controller =
                Robolectric.buildActivity(TestActivity.class).setup();
        TestActivity activity = controller.get();
        CustomListPreference preference = createPreference(activity);
        preference.setOnPreferenceChangeListener(
                (changedPreference, value) -> "1080p".equals(value));

        preference.onClick();
        LinearLayout items = activity.findViewById(R.id.app_dialog_items);
        items.getChildAt(1).performClick();

        assertEquals("1080p", preference.getValue());
        assertNull(activity.findViewById(R.id.app_dialog_overlay));
    }

    @Test
    public void rejectedValueIsNotApplied() {
        ActivityController<TestActivity> controller =
                Robolectric.buildActivity(TestActivity.class).setup();
        TestActivity activity = controller.get();
        CustomListPreference preference = createPreference(activity);
        preference.setOnPreferenceChangeListener((changedPreference, value) -> false);

        preference.onClick();
        LinearLayout items = activity.findViewById(R.id.app_dialog_items);
        items.getChildAt(1).performClick();

        assertEquals("720p", preference.getValue());
    }

    @Test
    public void backDismissesOverlayWithoutFinishingActivity() {
        ActivityController<TestActivity> controller =
                Robolectric.buildActivity(TestActivity.class).setup();
        TestActivity activity = controller.get();
        CustomListPreference preference = createPreference(activity);

        preference.onClick();
        activity.getOnBackPressedDispatcher().onBackPressed();

        assertNull(activity.findViewById(R.id.app_dialog_overlay));
        assertFalse(activity.isFinishing());
    }

    private static CustomListPreference createPreference(TestActivity activity) {
        CustomListPreference preference = new CustomListPreference(activity);
        preference.setTitle("Resolution");
        preference.setEntries(new CharSequence[]{"1280 × 720", "1920 × 1080"});
        preference.setEntryValues(new CharSequence[]{"720p", "1080p"});
        preference.setValue("720p");
        return preference;
    }

    public static class TestActivity extends AppCompatActivity {
        @Override
        protected void onCreate(android.os.Bundle savedInstanceState) {
            setTheme(R.style.SettingsTheme);
            super.onCreate(savedInstanceState);
            setContentView(new FrameLayout(this));
        }
    }
}
