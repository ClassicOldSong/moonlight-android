package com.limelight;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class ExternalPresentationLifecycleTest {
    @Test
    public void externalPresentationStaysAliveWhenControllerIsForeground() {
        assertTrue(Game.shouldKeepExternalPresentationAliveOnStop(
                true,
                true,
                true,
                false,
                false));
    }

    @Test
    public void externalPresentationStopsWhenActivityIsFinishing() {
        assertFalse(Game.shouldKeepExternalPresentationAliveOnStop(
                true,
                true,
                true,
                true,
                false));
    }

    @Test
    public void externalPresentationStaysAliveAcrossConfigurationTransition() {
        assertTrue(Game.shouldKeepExternalPresentationAliveOnStop(
                true,
                true,
                true,
                false,
                true));
    }

    @Test
    public void externalPresentationStopsWhenControllerIsNotForeground() {
        assertFalse(Game.shouldKeepExternalPresentationAliveOnStop(
                true,
                true,
                false,
                false,
                false));
    }

    @Test
    public void internalDisplayKeepsExistingStopBehavior() {
        assertFalse(Game.shouldKeepExternalPresentationAliveOnStop(
                false,
                false,
                false,
                false,
                false));
    }

    @Test
    public void externalDisplayRemovalListenerRegistersOnlyOnce() {
        assertTrue(Game.shouldRegisterExternalDisplayRemovalListener(true, false));
        assertFalse(Game.shouldRegisterExternalDisplayRemovalListener(true, true));
        assertFalse(Game.shouldRegisterExternalDisplayRemovalListener(false, false));
    }

    @Test
    public void presentationControllerFocusForwardsOnlyWhenCaptureProviderExists() {
        assertTrue(Game.shouldForwardPresentationControllerFocus(true, true));
        assertFalse(Game.shouldForwardPresentationControllerFocus(false, true));
        assertFalse(Game.shouldForwardPresentationControllerFocus(true, false));
    }
}
