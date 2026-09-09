package com.limelight;

import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.content.Context;
import android.hardware.display.DisplayManager;
import android.view.Display;

import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowDisplayManager;

@Config(sdk = {33}, shadows = {com.limelight.shadows.ShadowMoonBridge.class, com.limelight.shadows.ShadowGameManager.class})
@RunWith(RobolectricTestRunner.class)
public class PresentationDisplaySelectionTest {
    private Activity activity;

    @BeforeClass
    public static void suppressLogs() {
        TestLogSuppressor.install();
    }

    @Before
    public void setUp() {
        ShadowDisplayManager.reset();
        activity = Robolectric.buildActivity(Activity.class).setup().get();
    }

    @Test
    public void chooseRenderDisplayForPresentation_usesPresentationDisplayWhenAvailable() {
        Display currentDisplay = activity.getWindowManager().getDefaultDisplay();
        int presentationDisplayId = ShadowDisplayManager.addDisplay("w1920dp-h1080dp");
        DisplayManager displayManager = (DisplayManager) activity.getSystemService(Context.DISPLAY_SERVICE);
        Display presentationDisplay = displayManager.getDisplay(presentationDisplayId);

        Display renderDisplay = Game.chooseRenderDisplayForPresentation(
                currentDisplay,
                presentationDisplay,
                true);

        assertSame(presentationDisplay, renderDisplay);
    }

    @Test
    public void chooseRenderDisplayForPresentation_keepsCurrentDisplayOutsidePresentationMode() {
        Display currentDisplay = activity.getWindowManager().getDefaultDisplay();
        int presentationDisplayId = ShadowDisplayManager.addDisplay("w1920dp-h1080dp");
        DisplayManager displayManager = (DisplayManager) activity.getSystemService(Context.DISPLAY_SERVICE);
        Display presentationDisplay = displayManager.getDisplay(presentationDisplayId);

        Display renderDisplay = Game.chooseRenderDisplayForPresentation(
                currentDisplay,
                presentationDisplay,
                false);

        assertSame(currentDisplay, renderDisplay);
    }

    @Test
    public void shouldApplyDisplayModeToActivityWindow_onlyOutsidePresentationMode() {
        assertTrue(Game.shouldApplyDisplayModeToActivityWindow(false));
        assertFalse(Game.shouldApplyDisplayModeToActivityWindow(true));
    }
}
