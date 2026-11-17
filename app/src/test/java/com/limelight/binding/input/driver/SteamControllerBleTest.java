package com.limelight.binding.input.driver;

import static org.junit.Assert.assertEquals;

import com.limelight.nvstream.input.ControllerPacket;
import com.limelight.nvstream.jni.MoonBridge;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

@Config(sdk = {33})
@RunWith(RobolectricTestRunner.class)
public class SteamControllerBleTest {
    private int buttons;
    private float lx, ly, lt, gyroY;

    @Test
    public void parsesTritonStateReport() {
        UsbDriverListener listener = new UsbDriverListener() {
            public void reportControllerState(int id, int b, float lsx, float lsy, float rsx, float rsy, float l, float r) {
                buttons = b; lx = lsx; ly = lsy; lt = l;
            }
            public void reportControllerMotion(int id, byte type, float x, float y, float z) {
                if (type == MoonBridge.LI_MOTION_TYPE_GYRO) gyroY = y;
            }
            public void deviceRemoved(AbstractController c) {}
            public void deviceAdded(AbstractController c) {}
        };
        SteamControllerBle sc = new SteamControllerBle(null, null, 0, listener);

        ByteBuffer b = ByteBuffer.allocate(45).order(ByteOrder.LITTLE_ENDIAN);
        b.putInt(1, 0x00000001 | 0x00010000 | 0x00020000 | 0x00002000); // A, Steam, L4, D-up
        b.putShort(5, (short) 32767);  // left trigger full
        b.putShort(9, (short) 32767);  // left stick full right
        b.putShort(11, (short) 32767); // left stick full up
        b.putShort(43, (short) 16384); // raw gyro Z -> Moonlight gyro Y, half of 2000 dps
        sc.handleInput(b.array());

        assertEquals(ControllerPacket.A_FLAG | ControllerPacket.SPECIAL_BUTTON_FLAG |
                ControllerPacket.PADDLE2_FLAG | ControllerPacket.UP_FLAG, buttons);
        assertEquals(1.0f, lx, 1e-4);
        assertEquals(-1.0f, ly, 1e-4); // Moonlight drivers report Y down-positive
        assertEquals(1.0f, lt, 1e-4);
        assertEquals(1000.0f, gyroY, 1e-2);
    }

    @Test
    public void parsesUsbReportWithIdPrefix() {
        // USB reads (puck/cable) carry the report ID byte before the same body
        UsbDriverListener listener = new UsbDriverListener() {
            public void reportControllerState(int id, int b, float lsx, float lsy, float rsx, float rsy, float l, float r) {
                buttons = b; lx = lsx;
            }
            public void reportControllerMotion(int id, byte type, float x, float y, float z) {}
            public void deviceRemoved(AbstractController c) {}
            public void deviceAdded(AbstractController c) {}
        };
        SteamControllerBle sc = new SteamControllerBle(null, null, 0, listener);

        ByteBuffer b = ByteBuffer.allocate(46).order(ByteOrder.LITTLE_ENDIAN);
        b.put(0, (byte) SteamTritonController.ID_STATE);
        b.putInt(2, 0x00000002); // B
        b.putShort(10, (short) -32767); // left stick full left
        sc.handleState(b.array(), 1, 45);

        assertEquals(ControllerPacket.B_FLAG, buttons);
        assertEquals(-1.0f, lx, 1e-4);
    }
}
