package com.limelight;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import android.app.Activity;
import android.content.Intent;
import android.view.Display;

import androidx.preference.PreferenceManager;

import com.limelight.computers.ComputerManagerService;
import com.limelight.nvstream.http.ComputerDetails;
import com.limelight.nvstream.http.NvApp;
import com.limelight.utils.ExternalDisplayControlActivity;
import com.limelight.utils.ServerHelper;

import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowDisplayManager;

import java.util.ArrayList;

@Config(sdk = {33}, shadows = {com.limelight.shadows.ShadowMoonBridge.class, com.limelight.shadows.ShadowGameManager.class})
@RunWith(RobolectricTestRunner.class)
public class ServerHelperStartIntentTest {
    private Activity parent;
    private NvApp app;
    private ComputerDetails computer;
    private ComputerManagerService.ComputerManagerBinder managerBinder;

    @BeforeClass
    public static void suppressLogs() {
        TestLogSuppressor.install();
    }

    @Before
    public void setUp() {
        ShadowDisplayManager.reset();
        parent = Robolectric.buildActivity(Activity.class).setup().get();

        PreferenceManager.getDefaultSharedPreferences(parent)
                .edit()
                .clear()
                .commit();

        app = new NvApp("Test Game", "game-uuid", 1234, false);

        computer = new ComputerDetails();
        computer.activeAddress = new ComputerDetails.AddressTuple("192.0.2.10", 47989);
        computer.httpsPort = 47984;
        computer.uuid = "pc-uuid";
        computer.name = "Test PC";
        computer.serverCommands = new ArrayList<>();

        managerBinder = mock(ComputerManagerService.ComputerManagerBinder.class);
        when(managerBinder.getUniqueId()).thenReturn("test-unique-id");
    }

    @Test
    public void createStartIntent_whenFullyExternalDisabled_returnsPlainGameIntent() {
        setFullyExternalEnabled(false);
        int displayId = ShadowDisplayManager.addDisplay("w1920dp-h1080dp");

        Intent intent = ServerHelper.createStartIntent(parent, app, computer, managerBinder, false);

        assertEquals(Game.class.getName(), intent.getComponent().getClassName());
        assertFalse(intent.hasExtra(Game.EXTRA_DISPLAY_ID));
        assertEquals(Display.DEFAULT_DISPLAY, parent.getWindowManager().getDefaultDisplay().getDisplayId());
        assertTrue(displayId != Display.DEFAULT_DISPLAY);
    }

    @Test
    public void createStartIntent_whenFullyExternalEnabledWithoutDisplay_returnsPlainGameIntent() {
        setFullyExternalEnabled(true);

        Intent intent = ServerHelper.createStartIntent(parent, app, computer, managerBinder, false);

        assertEquals(Game.class.getName(), intent.getComponent().getClassName());
        assertFalse(intent.hasExtra(Game.EXTRA_DISPLAY_ID));
    }

    @Test
    public void createStartIntent_whenFullyExternalEnabledWithDisplay_wrapsPresentationFallbackGameIntent() {
        setFullyExternalEnabled(true);
        int displayId = ShadowDisplayManager.addDisplay("w1920dp-h1080dp");

        Intent wrapperIntent = ServerHelper.createStartIntent(parent, app, computer, managerBinder, true);

        assertEquals(ExternalDisplayControlActivity.class.getName(), wrapperIntent.getComponent().getClassName());

        Intent gameIntent = wrapperIntent.getParcelableExtra(ExternalDisplayControlActivity.EXTRA_LAUNCH_INTENT);
        assertNotNull(gameIntent);
        assertEquals(Game.class.getName(), gameIntent.getComponent().getClassName());
        assertEquals(Display.DEFAULT_DISPLAY, gameIntent.getIntExtra(Game.EXTRA_DISPLAY_ID, -1));
        assertEquals(displayId, gameIntent.getIntExtra(Game.EXTRA_PRESENTATION_DISPLAY_ID, -1));
        assertTrue(gameIntent.getBooleanExtra(Game.EXTRA_VDISPLAY, false));
    }

    @Test
    public void createStartIntent_whenFullyExternalEnabledWithDisplay_doesNotUseNewTaskLaunchFlag() {
        setFullyExternalEnabled(true);
        ShadowDisplayManager.addDisplay("w1920dp-h1080dp");

        Intent wrapperIntent = ServerHelper.createStartIntent(parent, app, computer, managerBinder, false);
        Intent gameIntent = wrapperIntent.getParcelableExtra(ExternalDisplayControlActivity.EXTRA_LAUNCH_INTENT);

        assertNotNull(gameIntent);
        assertEquals(0, gameIntent.getFlags() & Intent.FLAG_ACTIVITY_NEW_TASK);
    }

    @Test
    public void createStartIntent_preservesCommonGameExtrasInsideWrapper() {
        setFullyExternalEnabled(true);
        ShadowDisplayManager.addDisplay("w1920dp-h1080dp");

        Intent wrapperIntent = ServerHelper.createStartIntent(parent, app, computer, managerBinder, true);
        Intent gameIntent = wrapperIntent.getParcelableExtra(ExternalDisplayControlActivity.EXTRA_LAUNCH_INTENT);

        assertNotNull(gameIntent);
        assertEquals("192.0.2.10", gameIntent.getStringExtra(Game.EXTRA_HOST));
        assertEquals(47989, gameIntent.getIntExtra(Game.EXTRA_PORT, 0));
        assertEquals(47984, gameIntent.getIntExtra(Game.EXTRA_HTTPS_PORT, 0));
        assertEquals("Test Game", gameIntent.getStringExtra(Game.EXTRA_APP_NAME));
        assertEquals("game-uuid", gameIntent.getStringExtra(Game.EXTRA_APP_UUID));
        assertEquals(1234, gameIntent.getIntExtra(Game.EXTRA_APP_ID, 0));
        assertEquals("test-unique-id", gameIntent.getStringExtra(Game.EXTRA_UNIQUEID));
        assertEquals("pc-uuid", gameIntent.getStringExtra(Game.EXTRA_PC_UUID));
        assertEquals("Test PC", gameIntent.getStringExtra(Game.EXTRA_PC_NAME));
        assertTrue(gameIntent.getBooleanExtra(Game.EXTRA_VDISPLAY, false));
        assertNull(gameIntent.getByteArrayExtra(Game.EXTRA_SERVER_CERT));
    }

    private void setFullyExternalEnabled(boolean enabled) {
        PreferenceManager.getDefaultSharedPreferences(parent)
                .edit()
                .putBoolean("checkbox_enable_fullexdisplay", enabled)
                .commit();
    }
}
