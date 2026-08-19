package com.limelight.binding.audio;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

public class PcmFrameAccumulatorTest {
    @Test
    public void accumulatesArbitraryCallbackSizesIntoACompleteFrame() throws Exception {
        PcmFrameAccumulator accumulator = new PcmFrameAccumulator();
        accumulator.start();
        short[] first = new short[1000];
        short[] second = new short[PcmFrameAccumulator.SHORTS_PER_FRAME - first.length];
        first[0] = 11;
        second[second.length - 1] = 22;

        assertEquals(0, accumulator.offer(first));
        assertEquals(1000, accumulator.getPartialShorts());
        assertEquals(0, accumulator.offer(second));
        PcmFrameAccumulator.Frame frame = accumulator.take();
        assertEquals(11, frame.samples.get(0));
        assertEquals(22, frame.samples.get(PcmFrameAccumulator.SHORTS_PER_FRAME - 1));
        accumulator.release(frame);
    }

    @Test
    public void queueIsBoundedAndDropsTheOldestCompleteFrame() {
        PcmFrameAccumulator accumulator = new PcmFrameAccumulator();
        accumulator.start();
        short[] fourFrames = new short[PcmFrameAccumulator.SHORTS_PER_FRAME * 4];
        for (int frame = 0; frame < 4; frame++) {
            fourFrames[frame * PcmFrameAccumulator.SHORTS_PER_FRAME] = (short) (frame + 1);
        }
        assertEquals(1, accumulator.offer(fourFrames));
        assertEquals(PcmFrameAccumulator.MAX_QUEUED_FRAMES, accumulator.getQueueDepth());

        try {
            for (short expected = 2; expected <= 4; expected++) {
                PcmFrameAccumulator.Frame frame = accumulator.take();
                assertEquals(expected, frame.samples.get(0));
                accumulator.release(frame);
            }
        } catch (InterruptedException interrupted) {
            throw new AssertionError(interrupted);
        }
    }

    @Test
    public void stopDiscardsQueuedAndPartialAudio() throws Exception {
        PcmFrameAccumulator accumulator = new PcmFrameAccumulator();
        accumulator.start();
        accumulator.offer(new short[PcmFrameAccumulator.SHORTS_PER_FRAME + 17]);
        accumulator.stop();
        assertEquals(0, accumulator.getQueueDepth());
        assertEquals(0, accumulator.getPartialShorts());
        assertNull(accumulator.take());
    }

    @Test
    public void canRestartAfterStopWithoutLeakingOldAudio() throws Exception {
        PcmFrameAccumulator accumulator = new PcmFrameAccumulator();
        accumulator.start();
        accumulator.offer(new short[PcmFrameAccumulator.SHORTS_PER_FRAME]);
        accumulator.stop();

        accumulator.start();
        short[] newFrame = new short[PcmFrameAccumulator.SHORTS_PER_FRAME];
        newFrame[0] = 42;
        accumulator.offer(newFrame);
        PcmFrameAccumulator.Frame frame = accumulator.take();
        assertEquals(42, frame.samples.get(0));
        accumulator.release(frame);
    }
}
