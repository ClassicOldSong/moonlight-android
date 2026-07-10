package com.limelight;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.content.Intent;
import android.view.Display;
import android.view.WindowManager;

import com.limelight.utils.ExternalDisplayControlActivity;

import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowActivity;
import org.robolectric.shadows.ShadowDisplayManager;

@Config(sdk = {33}, shadows = {com.limelight.shadows.ShadowMoonBridge.class, com.limelight.shadows.ShadowGameManager.class})
@RunWith(RobolectricTestRunner.class)
public class ExternalDisplayControlActivityLaunchTest {
    @BeforeClass
    public static void suppressLogs() {
        TestLogSuppressor.install();
    }

    @Before
    public void setUp() {
        ShadowDisplayManager.reset();
        Game.instance = null;
    }

    @Test
    public void startsWrappedGameIntentAsPresentationFallbackWithoutLaunchDisplayOptions() {
        int displayId = ShadowDisplayManager.addDisplay("w1920dp-h1080dp");
        Intent gameIntent = new Intent();
        gameIntent.setClassName("com.limelight", Game.class.getName());

        Intent wrapperIntent = new Intent();
        wrapperIntent.setClassName("com.limelight", ExternalDisplayControlActivity.class.getName());
        wrapperIntent.putExtra(ExternalDisplayControlActivity.EXTRA_LAUNCH_INTENT, gameIntent);

        ExternalDisplayControlActivity activity = Robolectric
                .buildActivity(ExternalDisplayControlActivity.class, wrapperIntent)
                .create()
                .get();

        ShadowActivity.IntentForResult started = Shadows.shadowOf(activity).getNextStartedActivityForResult();
        assertNotNull(started);
        assertEquals(Game.class.getName(), started.intent.getComponent().getClassName());
        assertEquals(Display.DEFAULT_DISPLAY, started.intent.getIntExtra(Game.EXTRA_DISPLAY_ID, -1));
        assertEquals(displayId, started.intent.getIntExtra(Game.EXTRA_PRESENTATION_DISPLAY_ID, -1));
        assertNull(started.options);
        assertNull(Shadows.shadowOf(activity).getNextStartedActivityForResult());
    }

    @Test
    public void controllerWindowKeepsScreenOnDuringLaunch() {
        ShadowDisplayManager.addDisplay("w1920dp-h1080dp");
        Intent gameIntent = new Intent();
        gameIntent.setClassName("com.limelight", Game.class.getName());

        Intent wrapperIntent = new Intent();
        wrapperIntent.setClassName("com.limelight", ExternalDisplayControlActivity.class.getName());
        wrapperIntent.putExtra(ExternalDisplayControlActivity.EXTRA_LAUNCH_INTENT, gameIntent);

        ExternalDisplayControlActivity activity = Robolectric
                .buildActivity(ExternalDisplayControlActivity.class, wrapperIntent)
                .create()
                .get();

        assertTrue((activity.getWindow().getAttributes().flags
                & WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) != 0);
    }

    @Test
    public void reusedWrapperStartsWrappedGameIntentFromNewIntent() {
        int displayId = ShadowDisplayManager.addDisplay("w1920dp-h1080dp");

        Intent initialGameIntent = new Intent();
        initialGameIntent.setClassName("com.limelight", Game.class.getName());

        Intent initialWrapperIntent = new Intent();
        initialWrapperIntent.setClassName("com.limelight", ExternalDisplayControlActivity.class.getName());
        initialWrapperIntent.putExtra(ExternalDisplayControlActivity.EXTRA_LAUNCH_INTENT, initialGameIntent);

        ActivityController<ExternalDisplayControlActivity> controller = Robolectric
                .buildActivity(ExternalDisplayControlActivity.class, initialWrapperIntent)
                .create();
        ExternalDisplayControlActivity activity = controller.get();
        Shadows.shadowOf(activity).getNextStartedActivityForResult();

        Game.instance = null;

        Intent secondGameIntent = new Intent();
        secondGameIntent.setClassName("com.limelight", Game.class.getName());

        Intent secondWrapperIntent = new Intent();
        secondWrapperIntent.setClassName("com.limelight", ExternalDisplayControlActivity.class.getName());
        secondWrapperIntent.putExtra(ExternalDisplayControlActivity.EXTRA_LAUNCH_INTENT, secondGameIntent);

        controller.newIntent(secondWrapperIntent);

        ShadowActivity.IntentForResult started = Shadows.shadowOf(activity).getNextStartedActivityForResult();
        assertNotNull(started);
        assertEquals(Game.class.getName(), started.intent.getComponent().getClassName());
        assertEquals(Display.DEFAULT_DISPLAY, started.intent.getIntExtra(Game.EXTRA_DISPLAY_ID, -1));
        assertEquals(displayId, started.intent.getIntExtra(Game.EXTRA_PRESENTATION_DISPLAY_ID, -1));
        assertNull(started.options);
        assertNull(Shadows.shadowOf(activity).getNextStartedActivityForResult());
    }

    @Test
    public void startsPresentationFallbackIntentWithoutLaunchDisplayOptions() {
        int displayId = ShadowDisplayManager.addDisplay("w1920dp-h1080dp");
        Intent gameIntent = new Intent();
        gameIntent.setClassName("com.limelight", Game.class.getName());
        gameIntent.putExtra(Game.EXTRA_DISPLAY_ID, Display.DEFAULT_DISPLAY);
        gameIntent.putExtra(Game.EXTRA_PRESENTATION_DISPLAY_ID, displayId);

        Intent wrapperIntent = new Intent();
        wrapperIntent.setClassName("com.limelight", ExternalDisplayControlActivity.class.getName());
        wrapperIntent.putExtra(ExternalDisplayControlActivity.EXTRA_LAUNCH_INTENT, gameIntent);

        ExternalDisplayControlActivity activity = Robolectric
                .buildActivity(ExternalDisplayControlActivity.class, wrapperIntent)
                .create()
                .get();

        ShadowActivity.IntentForResult started = Shadows.shadowOf(activity).getNextStartedActivityForResult();
        assertNotNull(started);
        assertEquals(Game.class.getName(), started.intent.getComponent().getClassName());
        assertEquals(Display.DEFAULT_DISPLAY, started.intent.getIntExtra(Game.EXTRA_DISPLAY_ID, -1));
        assertEquals(displayId, started.intent.getIntExtra(Game.EXTRA_PRESENTATION_DISPLAY_ID, -1));
        assertNull(started.options);
    }
}
