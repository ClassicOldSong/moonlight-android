package com.limelight.binding.input.capture;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class AndroidNativePointerCaptureProviderTest {
    @Test
    public void captureIsActiveOnlyWhenTheFocusedAttachedViewOwnsPointerCapture() {
        assertTrue(AndroidNativePointerCaptureProvider.isCaptureActive(
                true, false, true, true, true));

        assertFalse(AndroidNativePointerCaptureProvider.isCaptureActive(
                false, false, true, true, true));
        assertFalse(AndroidNativePointerCaptureProvider.isCaptureActive(
                true, true, true, true, true));
        assertFalse(AndroidNativePointerCaptureProvider.isCaptureActive(
                true, false, false, true, true));
        assertFalse(AndroidNativePointerCaptureProvider.isCaptureActive(
                true, false, true, false, true));
        assertFalse(AndroidNativePointerCaptureProvider.isCaptureActive(
                true, false, true, true, false));
    }
}
