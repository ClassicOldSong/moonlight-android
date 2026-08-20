package com.limelight.ui;

import android.os.Bundle;
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

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

@Config(sdk = {33})
@RunWith(RobolectricTestRunner.class)
public class AppDialogTest {
    @BeforeClass
    public static void suppressLogs() {
        TestLogSuppressor.install();
    }

    @Test
    public void itemMenuLivesInActivityAndDismissesAfterSelection() {
        TestActivity activity = createActivity();
        AtomicInteger selected = new AtomicInteger(-1);
        AppDialog dialog = AppDialog.builder(activity)
                .setTitle("Actions")
                .setItems(new CharSequence[]{"One", "Two"},
                        (shown, which) -> selected.set(which))
                .show();

        assertSame(activity.findViewById(android.R.id.content),
                dialog.getOverlayView().getParent());
        LinearLayout items = activity.findViewById(R.id.app_dialog_items);
        items.getChildAt(1).performClick();

        assertEquals(1, selected.get());
        assertFalse(dialog.isShowing());
        assertNull(activity.findViewById(R.id.app_dialog_overlay));
    }

    @Test
    public void rejectedButtonKeepsOverlayOpenUntilAccepted() {
        TestActivity activity = createActivity();
        AtomicBoolean accept = new AtomicBoolean(false);
        AppDialog dialog = AppDialog.builder(activity)
                .setPositiveButton(android.R.string.ok, shown -> accept.get())
                .show();
        LinearLayout actions = activity.findViewById(R.id.app_dialog_actions);

        actions.getChildAt(0).performClick();
        assertTrue(dialog.isShowing());

        accept.set(true);
        actions.getChildAt(0).performClick();
        assertFalse(dialog.isShowing());
    }

    @Test
    public void multiChoiceUpdatesStateAndStaysOpen() {
        TestActivity activity = createActivity();
        boolean[] checked = {false, true};
        AtomicInteger changedIndex = new AtomicInteger(-1);
        AppDialog dialog = AppDialog.builder(activity)
                .setMultiChoiceItems(new CharSequence[]{"A", "B"}, checked,
                        (shown, which, isChecked) -> changedIndex.set(which))
                .setPositiveButton(android.R.string.ok, shown -> true)
                .show();

        LinearLayout items = activity.findViewById(R.id.app_dialog_items);
        items.getChildAt(0).performClick();

        assertTrue(checked[0]);
        assertEquals(0, changedIndex.get());
        assertTrue(dialog.isShowing());
    }

    @Test
    public void backCancelsOverlayWithoutFinishingHost() {
        TestActivity activity = createActivity();
        AtomicBoolean canceled = new AtomicBoolean(false);
        AppDialog dialog = AppDialog.builder(activity)
                .setOnCancel(() -> canceled.set(true))
                .show();

        activity.getOnBackPressedDispatcher().onBackPressed();

        assertTrue(canceled.get());
        assertFalse(dialog.isShowing());
        assertFalse(activity.isFinishing());
    }

    @Test
    public void showingAnotherOverlayReplacesTheOldOne() {
        TestActivity activity = createActivity();
        AppDialog first = AppDialog.builder(activity).setTitle("First").show();
        AppDialog second = AppDialog.builder(activity).setTitle("Second").show();

        assertFalse(first.isShowing());
        assertTrue(second.isShowing());
        assertSame(second.getOverlayView(), activity.findViewById(R.id.app_dialog_overlay));
    }

    @Test
    public void dismissFromPairingWorkerRunsViewCleanupOnMainThread() throws Exception {
        TestActivity activity = createActivity();
        AtomicBoolean dismissedOnMainThread = new AtomicBoolean(false);
        AppDialog dialog = AppDialog.builder(activity)
                .setOnDismiss(() -> dismissedOnMainThread.set(
                        Looper.myLooper() == Looper.getMainLooper()))
                .show();

        Thread pairingWorker = new Thread(dialog::dismiss);
        pairingWorker.start();
        pairingWorker.join();
        Shadows.shadowOf(Looper.getMainLooper()).idle();

        assertFalse(dialog.isShowing());
        assertTrue(dismissedOnMainThread.get());
    }

    @Test
    public void activityDestroyCleansUpOverlay() {
        ActivityController<TestActivity> controller =
                Robolectric.buildActivity(TestActivity.class).setup();
        AtomicBoolean dismissed = new AtomicBoolean(false);
        AppDialog dialog = AppDialog.builder(controller.get())
                .setOnDismiss(() -> dismissed.set(true))
                .show();

        controller.pause().stop().destroy();

        assertFalse(dialog.isShowing());
        assertTrue(dismissed.get());
    }

    private static TestActivity createActivity() {
        return Robolectric.buildActivity(TestActivity.class).setup().get();
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
