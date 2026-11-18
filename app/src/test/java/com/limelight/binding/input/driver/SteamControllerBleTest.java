package com.limelight.binding.input.driver;

import static org.junit.Assert.assertEquals;

import com.limelight.nvstream.input.ControllerPacket;
import com.limelight.nvstream.jni.MoonBridge;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.nio.ByteOrder;

@Config(sdk = {33})
@RunWith(RobolectricTestRunner.class)
public class SteamControllerBleTest {
    private int buttons;
    private float lx, ly, rx, ry, lt, gyroY;

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

    @Test
    public void reportsRawTrackpadsToTester() {
        UsbDriverListener listener = new UsbDriverListener() {
            public void reportControllerState(int id, int b, float lsx, float lsy, float rsx, float rsy, float l, float r) {}
            public void reportControllerMotion(int id, byte type, float x, float y, float z) {}
            public void deviceRemoved(AbstractController c) {}
            public void deviceAdded(AbstractController c) {}
        };
        SteamControllerBle sc = new SteamControllerBle(null, null, 0, listener);
        int[] got = new int[7];
        SteamTritonController.rawListener = (btn, lpx, lpy, lpp, rpx, rpy, rpp) -> {
            got[0] = btn; got[1] = lpx; got[2] = lpy; got[3] = lpp; got[4] = rpx; got[5] = rpy; got[6] = rpp;
        };
        try {
            ByteBuffer b = ByteBuffer.allocate(45).order(ByteOrder.LITTLE_ENDIAN);
            b.putInt(1, 0x00800000); // a bit the driver doesn't map
            b.putShort(17, (short) -100);
            b.putShort(19, (short) 200);
            b.putShort(21, (short) 0xFFFF); // pressure is unsigned
            b.putShort(23, (short) 300);
            b.putShort(25, (short) -400);
            b.putShort(27, (short) 500);
            sc.handleInput(b.array());
        } finally {
            SteamTritonController.rawListener = null;
        }

        assertEquals(0x00800000, got[0]);
        assertEquals(-100, got[1]);
        assertEquals(200, got[2]);
        assertEquals(0xFFFF, got[3]);
        assertEquals(300, got[4]);
        assertEquals(-400, got[5]);
        assertEquals(500, got[6]);
    }

    @Test
    public void trackpadsDriveTheSticks() {
        UsbDriverListener listener = new UsbDriverListener() {
            public void reportControllerState(int id, int b, float lsx, float lsy, float rsx, float rsy, float l, float r) {
                buttons = b; lx = lsx; ly = lsy; rx = rsx; ry = rsy;
            }
            public void reportControllerMotion(int id, byte type, float x, float y, float z) {}
            public void deviceRemoved(AbstractController c) {}
            public void deviceAdded(AbstractController c) {}
        };
        SteamControllerBle sc = new SteamControllerBle(null, null, 0, listener);

        // Left pad touched at the top edge and clicked, right pad in the top-right corner
        ByteBuffer b = ByteBuffer.allocate(45).order(ByteOrder.LITTLE_ENDIAN);
        b.putInt(1, SteamTritonController.LPAD_TOUCH | SteamTritonController.LPAD_CLICK | SteamTritonController.RPAD_TOUCH);
        b.putShort(19, (short) 32767);
        b.putShort(23, (short) 32767);
        b.putShort(25, (short) 32767);
        sc.handleInput(b.array());

        assertEquals(ControllerPacket.LS_CLK_FLAG, buttons);
        assertEquals(0.0f, lx, 1e-4);
        assertEquals(-1.0f, ly, 1e-4);            // up, in the drivers' down-positive Y
        assertEquals(0.7071f, rx, 1e-3);          // corner pulled onto the unit circle
        assertEquals(-0.7071f, ry, 1e-3);

        // A stick pushed further than the pad wins; an untouched pad is ignored
        b.putInt(1, SteamTritonController.LPAD_TOUCH);
        b.putShort(9, (short) -32767);            // left stick full left
        b.putShort(19, (short) 3000);             // pad barely above center
        b.putShort(23, (short) 32767);            // right pad coordinates without its touch bit
        sc.handleInput(b.array());

        assertEquals(0, buttons);
        assertEquals(-1.0f, lx, 1e-4);
        assertEquals(0.0f, ly, 1e-4);
        assertEquals(0.0f, rx, 1e-4);
    }

    @Test
    public void rightTrackpadAsMouse() {
        List<String> mouse = new ArrayList<>();
        UsbDriverListener listener = new UsbDriverListener() {
            public void reportControllerState(int id, int b, float lsx, float lsy, float rsx, float rsy, float l, float r) {
                buttons = b; rx = rsx;
            }
            public void reportControllerMotion(int id, byte type, float x, float y, float z) {}
            public void reportControllerMouse(int id, short dx, short dy, boolean left) {
                mouse.add(dx + "," + dy + (left ? " L" : ""));
            }
            public void deviceRemoved(AbstractController c) {}
            public void deviceAdded(AbstractController c) {}
        };
        SteamControllerBle sc = new SteamControllerBle(null, null, 0, listener);
        sc.setRightPadMouse(true);

        ByteBuffer b = ByteBuffer.allocate(45).order(ByteOrder.LITTLE_ENDIAN);
        b.putInt(1, SteamTritonController.RPAD_TOUCH);
        b.putShort(23, (short) 32000);
        sc.handleInput(b.array());                     // finger lands: no jump
        b.putShort(23, (short) 32400);
        b.putShort(25, (short) 400);                    // right and up by 400 units each
        sc.handleInput(b.array());
        b.putInt(1, SteamTritonController.RPAD_TOUCH | SteamTritonController.RPAD_CLICK);
        sc.handleInput(b.array());                     // click without moving

        assertEquals(Arrays.asList("10,-10", "0,0 L"), mouse);
        assertEquals(0, buttons);                       // no right stick click
        assertEquals(0.0f, rx, 1e-4);                   // and no right stick motion
    }
}
