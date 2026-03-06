package com.limelight.utils;

import org.junit.Test;

import static org.junit.Assert.*;

public class Vector2dTest {

    @Test
    public void initialize_setsCoordinatesAndMagnitude() {
        Vector2d v = new Vector2d();
        v.initialize(3.0f, 4.0f);
        assertEquals(3.0f, v.getX(), 0.0001f);
        assertEquals(4.0f, v.getY(), 0.0001f);
        assertEquals(5.0, v.getMagnitude(), 0.0001);
    }

    @Test
    public void zeroVector_hasMagnitudeZero() {
        Vector2d v = new Vector2d();
        v.initialize(0, 0);
        assertEquals(0.0f, v.getX(), 0.0f);
        assertEquals(0.0f, v.getY(), 0.0f);
        assertEquals(0.0, v.getMagnitude(), 0.0);
    }

    @Test
    public void scalarMultiply_scalesComponentsAndMagnitude() {
        Vector2d v = new Vector2d();
        v.initialize(3.0f, 4.0f); // magnitude = 5
        v.scalarMultiply(2.0);
        assertEquals(6.0f, v.getX(), 0.0001f);
        assertEquals(8.0f, v.getY(), 0.0001f);
        assertEquals(10.0, v.getMagnitude(), 0.0001);
    }

    @Test
    public void scalarMultiply_byNegativeFactor_flipsSign_keepsMagnitude() {
        Vector2d v = new Vector2d();
        v.initialize(3.0f, 4.0f); // magnitude = 5
        v.scalarMultiply(-1.0);
        assertEquals(-3.0f, v.getX(), 0.0001f);
        assertEquals(-4.0f, v.getY(), 0.0001f);
        assertEquals(5.0, v.getMagnitude(), 0.0001); // magnitude *= |-1.0| = 5.0 * 1.0 = 5.0
    }
}
