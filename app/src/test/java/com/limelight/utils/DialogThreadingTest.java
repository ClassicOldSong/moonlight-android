package com.limelight.utils;

import android.os.Bundle;
import android.os.Looper;
import android.widget.FrameLayout;

import androidx.appcompat.app.AppCompatActivity;

import com.limelight.R;
import com.limelight.TestLogSuppressor;

import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

@Config(sdk = {33})
@RunWith(RobolectricTestRunner.class)
public class DialogThreadingTest {
    @BeforeClass
    public static void suppressLogs() {
        TestLogSuppressor.install();
    }

    @Test
    public void pairingWorkerCanCloseDialogWithoutTouchingViewsOffMainThread()
            throws Exception {
        TestActivity activity = Robolectric.buildActivity(TestActivity.class).setup().get();
        Dialog.displayDialog(activity, "Pairing", "Waiting for host", false);
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        assertNotNull(activity.findViewById(R.id.app_dialog_overlay));

        Thread pairingWorker = new Thread(Dialog::closeDialogs);
        pairingWorker.start();
        pairingWorker.join();
        Shadows.shadowOf(Looper.getMainLooper()).idle();

        assertNull(activity.findViewById(R.id.app_dialog_overlay));
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
