package com.limelight.nvstream;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.limelight.nvstream.http.ComputerDetails;
import com.limelight.nvstream.jni.MoonBridge;
import com.limelight.shadows.ShadowMoonBridge;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import javax.crypto.spec.SecretKeySpec;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 33, shadows = {ShadowMoonBridge.class})
public class NvConnectionMicrophoneTest {

    @Before
    public void setUp() {
        ShadowMoonBridge.reset();
    }

    @Test
    public void startBridgeConnectionForwardsEnabledMicrophoneFlag() {
        ConnectionContext context = createConnectionContext(true);

        int result = NvConnection.startBridgeConnection(context, new byte[16], null, null, null);

        assertEquals(0, result);
        assertTrue(ShadowMoonBridge.lastStartConnectionEnableMicrophone);
    }

    @Test
    public void startBridgeConnectionForwardsDisabledMicrophoneFlag() {
        ConnectionContext context = createConnectionContext(false);

        int result = NvConnection.startBridgeConnection(context, new byte[16], null, null, null);

        assertEquals(0, result);
        assertFalse(ShadowMoonBridge.lastStartConnectionEnableMicrophone);
    }

    private static ConnectionContext createConnectionContext(boolean enableMicrophone) {
        ConnectionContext context = new ConnectionContext();
        context.serverAddress = new ComputerDetails.AddressTuple("127.0.0.1", 47984);
        context.serverAppVersion = "1.0.0.0";
        context.serverGfeVersion = "1.0.0.0";
        context.rtspSessionUrl = "rtsp://127.0.0.1";
        context.serverCodecModeSupport = 0;
        context.negotiatedWidth = 1920;
        context.negotiatedHeight = 1080;
        context.negotiatedPacketSize = 1024;
        context.negotiatedRemoteStreaming = StreamConfiguration.STREAM_CFG_LOCAL;
        context.videoCapabilities = 0;
        context.riKey = new SecretKeySpec(new byte[16], "AES");
        context.streamConfig = new StreamConfiguration.Builder()
                .setAudioConfiguration(new MoonBridge.AudioConfiguration(2, 0x3))
                .setEnableMicrophone(enableMicrophone)
                .build();
        return context;
    }
}
