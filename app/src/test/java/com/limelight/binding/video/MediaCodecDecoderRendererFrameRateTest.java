package com.limelight.binding.video;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class MediaCodecDecoderRendererFrameRateTest {
    @Test
    public void normalizeSurfaceFrameRateConvertsMilliHzRefreshRateToHz() {
        assertEquals(30.0f, MediaCodecDecoderRenderer.normalizeSurfaceFrameRate(30000), 0.001f);
    }

    @Test
    public void normalizeSurfaceFrameRateKeepsWholeNumberHz() {
        assertEquals(60.0f, MediaCodecDecoderRenderer.normalizeSurfaceFrameRate(60), 0.001f);
    }
}
