package com.limelight.binding.input;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import androidx.test.core.app.ApplicationProvider;

import com.limelight.TestLogSuppressor;
import com.limelight.nvstream.NvConnection;
import com.limelight.nvstream.input.ControllerPacket;
import com.limelight.nvstream.input.MouseButtonPacket;
import com.limelight.preferences.PreferenceConfiguration;
import com.limelight.shadows.ShadowGameManager;
import com.limelight.shadows.ShadowMoonBridge;

import org.junit.After;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mockito;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import static org.mockito.ArgumentMatchers.anyByte;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyShort;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@Config(sdk = {33}, shadows = {ShadowMoonBridge.class, ShadowGameManager.class})
@RunWith(RobolectricTestRunner.class)
public class MouseEmulationHandlerTest {

    private NvConnection conn;
    private PreferenceConfiguration prefConfig;
    private MouseEmulationHandler handler;

    @BeforeClass
    public static void init() {
        TestLogSuppressor.install();
    }

    @Before
    public void setUp() {
        Context ctx = ApplicationProvider.getApplicationContext();
        conn = Mockito.mock(NvConnection.class);
        MouseEmulationHandler.StickValueProvider stickProvider = Mockito.mock(MouseEmulationHandler.StickValueProvider.class);
        when(stickProvider.getLeftStickX()).thenReturn((short) 0);
        when(stickProvider.getLeftStickY()).thenReturn((short) 0);
        when(stickProvider.getRightStickX()).thenReturn((short) 0);
        when(stickProvider.getRightStickY()).thenReturn((short) 0);

        prefConfig = PreferenceConfiguration.readPreferences(ctx);
        prefConfig.mouseEmulationSensitivity = 100;
        prefConfig.analogStickForScrolling = PreferenceConfiguration.AnalogStickForScrolling.NONE;

        handler = new MouseEmulationHandler(conn, prefConfig,
                new Handler(Looper.getMainLooper()), ctx, stickProvider);
    }

    @After
    public void tearDown() {
        handler.destroy();
    }

    // -------------------------------------------------------------------------
    // Button edge detection
    // -------------------------------------------------------------------------

    @Test
    public void buttonEdgeDetection_aFlag_sendsLmbDownAndUp() {
        handler.toggle();

        handler.handleButtonInput(ControllerPacket.A_FLAG, (short) 0, (short) 1);
        verify(conn).sendMouseButtonDown(MouseButtonPacket.BUTTON_LEFT);

        handler.handleButtonInput(0, (short) 0, (short) 1);
        verify(conn).sendMouseButtonUp(MouseButtonPacket.BUTTON_LEFT);
    }

    @Test
    public void buttonEdgeDetection_bFlag_sendsRmbDownAndUp() {
        handler.toggle();

        handler.handleButtonInput(ControllerPacket.B_FLAG, (short) 0, (short) 1);
        verify(conn).sendMouseButtonDown(MouseButtonPacket.BUTTON_RIGHT);

        handler.handleButtonInput(0, (short) 0, (short) 1);
        verify(conn).sendMouseButtonUp(MouseButtonPacket.BUTTON_RIGHT);
    }

    @Test
    public void handleButtonInput_noOp_whenInactive() {
        // handler is inactive by default — toggle() not called
        handler.handleButtonInput(ControllerPacket.A_FLAG, (short) 0, (short) 1);

        verify(conn, never()).sendMouseButtonDown(anyByte());
        verify(conn, never()).sendMouseButtonUp(anyByte());
        verify(conn, never()).sendControllerInput(anyShort(), anyShort(), anyInt(), anyByte(), anyByte(), anyShort(), anyShort(), anyShort(), anyShort());
    }

    @Test
    public void handleButtonInput_sendsZeroedControllerPacket() {
        handler.toggle();

        handler.handleButtonInput(ControllerPacket.A_FLAG, (short) 0, (short) 1);

        // Unmapped buttons must not leak as controller input to the host
        verify(conn).sendControllerInput((short) 0, (short) 1, 0, (byte) 0, (byte) 0, (short) 0, (short) 0, (short) 0, (short) 0);
    }

    // -------------------------------------------------------------------------
    // Toggle / destroy cleanup
    // -------------------------------------------------------------------------

    @Test
    public void toggleOff_releasesHeldAButton() {
        handler.toggle(); // activate
        handler.handleButtonInput(ControllerPacket.A_FLAG, (short) 0, (short) 1);
        verify(conn).sendMouseButtonDown(MouseButtonPacket.BUTTON_LEFT);

        handler.toggle(); // deactivate
        verify(conn).sendMouseButtonUp(MouseButtonPacket.BUTTON_LEFT);
    }

    @Test
    public void toggleOff_releasesHeldBButton() {
        handler.toggle(); // activate
        handler.handleButtonInput(ControllerPacket.B_FLAG, (short) 0, (short) 1);
        verify(conn).sendMouseButtonDown(MouseButtonPacket.BUTTON_RIGHT);

        handler.toggle(); // deactivate
        verify(conn).sendMouseButtonUp(MouseButtonPacket.BUTTON_RIGHT);
    }

    @Test
    public void destroy_releasesHeldMouseButtons() {
        handler.toggle(); // activate
        handler.handleButtonInput(ControllerPacket.A_FLAG | ControllerPacket.B_FLAG, (short) 0, (short) 1);

        handler.destroy();

        verify(conn).sendMouseButtonUp(MouseButtonPacket.BUTTON_LEFT);
        verify(conn).sendMouseButtonUp(MouseButtonPacket.BUTTON_RIGHT);
    }

    // -------------------------------------------------------------------------
    // Middle click button mapping
    // -------------------------------------------------------------------------

    @Test
    public void buttonEdgeDetection_yFlag_sendsMmbDownAndUp() {
        handler.toggle();

        handler.handleButtonInput(ControllerPacket.Y_FLAG, (short) 0, (short) 1);
        verify(conn).sendMouseButtonDown(MouseButtonPacket.BUTTON_MIDDLE);

        handler.handleButtonInput(0, (short) 0, (short) 1);
        verify(conn).sendMouseButtonUp(MouseButtonPacket.BUTTON_MIDDLE);
    }

    @Test
    public void buttonEdgeDetection_rsClkFlag_sendsMmbDownAndUp() {
        prefConfig.analogStickForScrolling = PreferenceConfiguration.AnalogStickForScrolling.RIGHT;
        handler.toggle();

        handler.handleButtonInput(ControllerPacket.RS_CLK_FLAG, (short) 0, (short) 1);
        verify(conn).sendMouseButtonDown(MouseButtonPacket.BUTTON_MIDDLE);

        handler.handleButtonInput(0, (short) 0, (short) 1);
        verify(conn).sendMouseButtonUp(MouseButtonPacket.BUTTON_MIDDLE);
    }

    @Test
    public void buttonEdgeDetection_lsClkFlag_sendsMmbDownAndUp() {
        prefConfig.analogStickForScrolling = PreferenceConfiguration.AnalogStickForScrolling.LEFT;
        handler.toggle();

        handler.handleButtonInput(ControllerPacket.LS_CLK_FLAG, (short) 0, (short) 1);
        verify(conn).sendMouseButtonDown(MouseButtonPacket.BUTTON_MIDDLE);

        handler.handleButtonInput(0, (short) 0, (short) 1);
        verify(conn).sendMouseButtonUp(MouseButtonPacket.BUTTON_MIDDLE);
    }

    @Test
    public void buttonEdgeDetection_stickClkFlags_sendNoMmb_whenScrollingNone() {
        handler.toggle();

        handler.handleButtonInput(ControllerPacket.RS_CLK_FLAG | ControllerPacket.LS_CLK_FLAG, (short) 0, (short) 1);

        verify(conn, never()).sendMouseButtonDown(MouseButtonPacket.BUTTON_MIDDLE);
        verify(conn, never()).sendMouseButtonUp(MouseButtonPacket.BUTTON_MIDDLE);
    }

    @Test
    public void buttonEdgeDetection_rsClkFlag_sendNoMmb_whenScrollingLeft() {
        prefConfig.analogStickForScrolling = PreferenceConfiguration.AnalogStickForScrolling.LEFT;
        handler.toggle();

        handler.handleButtonInput(ControllerPacket.RS_CLK_FLAG, (short) 0, (short) 1);

        verify(conn, never()).sendMouseButtonDown(MouseButtonPacket.BUTTON_MIDDLE);
        verify(conn, never()).sendMouseButtonUp(MouseButtonPacket.BUTTON_MIDDLE);
    }

    @Test
    public void buttonEdgeDetection_lsClkFlag_sendNoMmb_whenScrollingRight() {
        prefConfig.analogStickForScrolling = PreferenceConfiguration.AnalogStickForScrolling.RIGHT;
        handler.toggle();

        handler.handleButtonInput(ControllerPacket.LS_CLK_FLAG, (short) 0, (short) 1);

        verify(conn, never()).sendMouseButtonDown(MouseButtonPacket.BUTTON_MIDDLE);
        verify(conn, never()).sendMouseButtonUp(MouseButtonPacket.BUTTON_MIDDLE);
    }

    @Test
    public void toggleOff_releasesHeldYButton() {
        handler.toggle(); // activate
        handler.handleButtonInput(ControllerPacket.Y_FLAG, (short) 0, (short) 1);
        verify(conn).sendMouseButtonDown(MouseButtonPacket.BUTTON_MIDDLE);

        handler.toggle(); // deactivate
        verify(conn).sendMouseButtonUp(MouseButtonPacket.BUTTON_MIDDLE);
    }

    @Test
    public void destroy_releasesHeldMiddleClickButtons() {
        prefConfig.analogStickForScrolling = PreferenceConfiguration.AnalogStickForScrolling.RIGHT;
        handler.toggle();

        handler.handleButtonInput(ControllerPacket.Y_FLAG | ControllerPacket.RS_CLK_FLAG, (short) 0, (short) 1);

        handler.destroy();

        // Y_FLAG and RS_CLK_FLAG each independently release BUTTON_MIDDLE
        verify(conn, times(2)).sendMouseButtonUp(MouseButtonPacket.BUTTON_MIDDLE);
    }
}
