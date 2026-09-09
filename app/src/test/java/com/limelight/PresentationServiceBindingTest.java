package com.limelight;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class PresentationServiceBindingTest {
    @Test
    public void shouldEndPresentationSession_whenSurfaceDestroyedAndNotAlreadyEnding() {
        assertTrue(Game.shouldEndPresentationSessionForTest(true, false, false));
    }

    @Test
    public void shouldNotEndPresentationSession_whenNotInPresentationMode() {
        assertFalse(Game.shouldEndPresentationSessionForTest(false, false, false));
    }

    @Test
    public void shouldNotEndPresentationSession_whenAlreadyEnding() {
        assertFalse(Game.shouldEndPresentationSessionForTest(true, true, false));
    }

    @Test
    public void shouldNotEndPresentationSession_whenActivityFinishing() {
        assertFalse(Game.shouldEndPresentationSessionForTest(true, false, true));
    }
}
