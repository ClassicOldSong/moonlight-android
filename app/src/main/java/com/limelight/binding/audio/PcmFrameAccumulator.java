package com.limelight.binding.audio;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.ShortBuffer;

/**
 * Allocation-free, bounded single-producer/single-consumer PCM frame accumulator.
 */
final class PcmFrameAccumulator {
    static final int CHANNELS = 6;
    static final int SAMPLES_PER_CHANNEL = 1_536;
    static final int SHORTS_PER_FRAME = CHANNELS * SAMPLES_PER_CHANNEL;
    static final int MAX_QUEUED_FRAMES = 3;

    static final class Frame {
        final int index;
        final ShortBuffer samples;

        Frame(int index) {
            this.index = index;
            samples = ByteBuffer.allocateDirect(SHORTS_PER_FRAME * 2)
                    .order(ByteOrder.nativeOrder())
                    .asShortBuffer();
        }

        void clear() {
            samples.clear();
        }

        void prepareForRead() {
            samples.position(0);
            samples.limit(SHORTS_PER_FRAME);
        }
    }

    // Three queued frames, one producer frame, and one worker-owned frame.
    private final Frame[] frames = new Frame[MAX_QUEUED_FRAMES + 2];
    private final int[] queue = new int[MAX_QUEUED_FRAMES];
    private final int[] free = new int[MAX_QUEUED_FRAMES + 1];
    private int queueHead;
    private int queueSize;
    private int freeSize;
    private int producerIndex;
    private int partialShorts;
    private boolean running;

    PcmFrameAccumulator() {
        for (int i = 0; i < frames.length; i++) {
            frames[i] = new Frame(i);
        }
        resetStorage();
    }

    synchronized void start() {
        running = true;
        notifyAll();
    }

    synchronized void stop() {
        running = false;
        clearQueuedFrames();
        frames[producerIndex].clear();
        partialShorts = 0;
        notifyAll();
    }

    synchronized int offer(short[] source) {
        if (!running || source == null || source.length == 0) {
            return 0;
        }

        int sourceOffset = 0;
        int dropped = 0;
        while (sourceOffset < source.length) {
            int count = Math.min(SHORTS_PER_FRAME - partialShorts, source.length - sourceOffset);
            Frame producer = frames[producerIndex];
            producer.samples.position(partialShorts);
            producer.samples.put(source, sourceOffset, count);
            partialShorts += count;
            sourceOffset += count;

            if (partialShorts == SHORTS_PER_FRAME) {
                producer.prepareForRead();
                int replacementIndex;
                if (queueSize == MAX_QUEUED_FRAMES) {
                    replacementIndex = dequeueIndex();
                    dropped++;
                } else {
                    replacementIndex = free[--freeSize];
                }
                enqueueIndex(producerIndex);
                producerIndex = replacementIndex;
                frames[producerIndex].clear();
                partialShorts = 0;
                notifyAll();
            }
        }
        return dropped;
    }

    synchronized Frame take() throws InterruptedException {
        while (running && queueSize == 0) {
            wait();
        }
        return queueSize == 0 ? null : frames[dequeueIndex()];
    }

    synchronized void release(Frame frame) {
        if (frame == null) {
            return;
        }
        frame.clear();
        free[freeSize++] = frame.index;
    }

    synchronized int getQueueDepth() {
        return queueSize;
    }

    synchronized int getPartialShorts() {
        return partialShorts;
    }

    private void resetStorage() {
        producerIndex = 0;
        partialShorts = 0;
        queueHead = 0;
        queueSize = 0;
        freeSize = 0;
        frames[producerIndex].clear();
        for (int i = 1; i < frames.length; i++) {
            free[freeSize++] = i;
        }
    }

    private void clearQueuedFrames() {
        while (queueSize > 0) {
            int index = dequeueIndex();
            frames[index].clear();
            free[freeSize++] = index;
        }
    }

    private void enqueueIndex(int index) {
        queue[(queueHead + queueSize) % queue.length] = index;
        queueSize++;
    }

    private int dequeueIndex() {
        int index = queue[queueHead];
        queueHead = (queueHead + 1) % queue.length;
        queueSize--;
        return index;
    }
}
