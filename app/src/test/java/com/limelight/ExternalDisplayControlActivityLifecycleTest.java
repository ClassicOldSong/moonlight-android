package com.limelight;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.limelight.utils.ExternalDisplayControlActivity;
import com.limelight.utils.ExternalDisplayControlActivity.PresentationState;

import org.junit.Test;

public class ExternalDisplayControlActivityLifecycleTest {
    @Test
    public void presentationSessionStaysAliveDuringLaunchAndControllerTakeover() {
        assertTrue(ExternalDisplayControlActivity.shouldKeepPresentationAlive(
                PresentationState.LAUNCHING_GAME, true));
        assertTrue(ExternalDisplayControlActivity.shouldKeepPresentationAlive(
                PresentationState.WAITING_FOR_CONTROLLER_FOCUS, true));
        assertTrue(ExternalDisplayControlActivity.shouldKeepPresentationAlive(
                PresentationState.CONTROLLER_ACTIVE, true));
        assertFalse(ExternalDisplayControlActivity.shouldKeepPresentationAlive(
                PresentationState.ENDING, true));
        assertFalse(ExternalDisplayControlActivity.shouldKeepPresentationAlive(
                PresentationState.CONTROLLER_ACTIVE, false));
    }

    @Test
    public void controllerStopEndsOnlyAfterActiveUserLeave() {
        assertFalse(ExternalDisplayControlActivity.shouldEndPresentationOnControllerStop(
                PresentationState.LAUNCHING_GAME, true, false));
        assertFalse(ExternalDisplayControlActivity.shouldEndPresentationOnControllerStop(
                PresentationState.WAITING_FOR_CONTROLLER_FOCUS, true, false));
        assertFalse(ExternalDisplayControlActivity.shouldEndPresentationOnControllerStop(
                PresentationState.CONTROLLER_ACTIVE, false, false));
        assertFalse(ExternalDisplayControlActivity.shouldEndPresentationOnControllerStop(
                PresentationState.CONTROLLER_ACTIVE, true, true));
        assertTrue(ExternalDisplayControlActivity.shouldEndPresentationOnControllerStop(
                PresentationState.CONTROLLER_ACTIVE, true, false));
    }

    @Test
    public void controllerDestroyEndsLiveSessionUnlessGameAlreadyOwnsCleanup() {
        assertTrue(ExternalDisplayControlActivity.shouldEndPresentationOnControllerDestroy(
                PresentationState.LAUNCHING_GAME, false, false));
        assertTrue(ExternalDisplayControlActivity.shouldEndPresentationOnControllerDestroy(
                PresentationState.CONTROLLER_ACTIVE, false, false));
        assertFalse(ExternalDisplayControlActivity.shouldEndPresentationOnControllerDestroy(
                PresentationState.CONTROLLER_ACTIVE, true, false));
        assertFalse(ExternalDisplayControlActivity.shouldEndPresentationOnControllerDestroy(
                PresentationState.CONTROLLER_ACTIVE, false, true));
        assertFalse(ExternalDisplayControlActivity.shouldEndPresentationOnControllerDestroy(
                PresentationState.ENDING, false, false));
    }

    @Test
    public void shouldRequestGameFocusForControllerInput_onlyOutsidePresentationMode() {
        assertTrue(ExternalDisplayControlActivity.shouldRequestGameFocusForControllerInput(
                true,
                false,
                1));

        assertFalse(ExternalDisplayControlActivity.shouldRequestGameFocusForControllerInput(
                true,
                true,
                1));
        assertFalse(ExternalDisplayControlActivity.shouldRequestGameFocusForControllerInput(
                false,
                false,
                1));
        assertFalse(ExternalDisplayControlActivity.shouldRequestGameFocusForControllerInput(
                true,
                false,
                -1));
    }

    @Test
    public void shouldFinishWhenGameUnavailable_waitsDuringPendingLaunch() {
        assertFalse(ExternalDisplayControlActivity.shouldFinishWhenGameUnavailable(
                true,
                false));
        assertFalse(ExternalDisplayControlActivity.shouldFinishWhenGameUnavailable(
                false,
                true));
        assertTrue(ExternalDisplayControlActivity.shouldFinishWhenGameUnavailable(
                false,
                false));
    }

    @Test
    public void activatesControllerOnlyAfterPresentationRootHasWindowFocus() {
        assertTrue(ExternalDisplayControlActivity.shouldActivatePresentationController(
                PresentationState.WAITING_FOR_CONTROLLER_FOCUS,
                true,
                true,
                true,
                true));
        assertTrue(ExternalDisplayControlActivity.shouldActivatePresentationController(
                PresentationState.CONTROLLER_ACTIVE,
                true,
                true,
                true,
                true));
        assertFalse(ExternalDisplayControlActivity.shouldActivatePresentationController(
                PresentationState.LAUNCHING_GAME,
                true,
                true,
                true,
                true));
        assertFalse(ExternalDisplayControlActivity.shouldActivatePresentationController(
                PresentationState.WAITING_FOR_CONTROLLER_FOCUS,
                true,
                true,
                false,
                true));
        assertFalse(ExternalDisplayControlActivity.shouldActivatePresentationController(
                PresentationState.WAITING_FOR_CONTROLLER_FOCUS,
                true,
                true,
                true,
                false));
    }
}
