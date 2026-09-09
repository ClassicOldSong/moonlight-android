package com.limelight;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class PresentationInputMappingTest {
    @Test
    public void scaleInputCoordinate_mapsCenterToTargetCenter() {
        assertEquals(960.0f, Game.scaleInputCoordinate(540.0f, 1080, 1920), 0.001f);
        assertEquals(540.0f, Game.scaleInputCoordinate(1200.0f, 2400, 1080), 0.001f);
    }

    @Test
    public void scaleInputCoordinate_clampsNegativeAndOverflow() {
        assertEquals(0.0f, Game.scaleInputCoordinate(-10.0f, 1080, 1920), 0.001f);
        assertEquals(1920.0f, Game.scaleInputCoordinate(2000.0f, 1080, 1920), 0.001f);
    }

    @Test
    public void scaleInputCoordinate_handlesZeroSourceOrTarget() {
        assertEquals(1920.0f, Game.scaleInputCoordinate(10.0f, 0, 1920), 0.001f);
        assertEquals(1.0f, Game.scaleInputCoordinate(10.0f, 1080, 0), 0.001f);
    }

    @Test
    public void shouldScalePresentationInput_onlyForPresentationControllerInput() {
        assertTrue(Game.shouldScalePresentationInput(true, true));
        assertFalse(Game.shouldScalePresentationInput(false, true));
        assertFalse(Game.shouldScalePresentationInput(true, false));
    }

    @Test
    public void capturedMouseReference_usesPresentationDisplayWhenStreamViewIsHidden() {
        assertEquals(3840,
                Game.selectCapturedMouseReferenceDimension(true, 3840, 0));
        assertEquals(2160,
                Game.selectCapturedMouseReferenceDimension(true, 2160, 0));
    }

    @Test
    public void capturedMouseReference_preservesStreamViewSizeOutsidePresentation() {
        assertEquals(2246,
                Game.selectCapturedMouseReferenceDimension(false, 3840, 2246));
        assertEquals(1080,
                Game.selectCapturedMouseReferenceDimension(false, 2160, 1080));
    }

    @Test
    public void capturedMouseReference_clampsToProtocolRange() {
        assertEquals(Short.MAX_VALUE,
                Game.selectCapturedMouseReferenceDimension(true, 65535, 0));
    }

    @Test
    public void capturedMouseReference_rejectsDimensionsBelowTwo() {
        assertFalse(Game.isValidCapturedMouseReference(0, 2160));
        assertFalse(Game.isValidCapturedMouseReference(3840, 1));
        assertTrue(Game.isValidCapturedMouseReference(3840, 2160));
    }
}
