package com.limelight.utils;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class GamePresentationDisplayModeTest {
    @Test
    public void shouldApplyPreferredDisplayMode_onlyWhenModeIdIsProvided() {
        assertFalse(GamePresentation.shouldApplyPreferredDisplayMode(0));
        assertTrue(GamePresentation.shouldApplyPreferredDisplayMode(1));
    }
}
