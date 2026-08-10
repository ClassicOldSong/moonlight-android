package com.limelight.binding.video;

import java.util.Arrays;

final class DecoderLatencySampler {
    static final int CAPACITY = 16_384;
    static final String METRIC_NAME =
            "decoder_input_queue_call_start_to_first_output_dequeue_us";

    static final class Summary {
        final int sampleCount;
        final int p50Us;
        final int p95Us;
        final int p99Us;

        private Summary(int sampleCount, int p50Us, int p95Us, int p99Us) {
            this.sampleCount = sampleCount;
            this.p50Us = p50Us;
            this.p95Us = p95Us;
            this.p99Us = p99Us;
        }

        boolean hasMeasurement() {
            return sampleCount != 0;
        }
    }

    private final int[] samples = new int[CAPACITY];
    private int oldestIndex;
    private int sampleCount;

    void recordMicroseconds(int latencyUs) {
        if (latencyUs < 0) {
            return;
        }

        int writeIndex = (oldestIndex + sampleCount) % CAPACITY;
        samples[writeIndex] = latencyUs;
        if (sampleCount < CAPACITY) {
            sampleCount++;
        }
        else {
            oldestIndex = (oldestIndex + 1) % CAPACITY;
        }
    }

    int recordDeltaNs(long queueCallStartNs, long firstOutputDequeueNs) {
        long deltaNs = firstOutputDequeueNs - queueCallStartNs;
        if (deltaNs < 0) {
            return -1;
        }

        long latencyUs = deltaNs / 1_000L;
        int clampedLatencyUs = latencyUs > Integer.MAX_VALUE ?
                Integer.MAX_VALUE : (int) latencyUs;
        recordMicroseconds(clampedLatencyUs);
        return clampedLatencyUs;
    }

    int[] copySamples() {
        int[] copy = new int[sampleCount];
        int firstLength = Math.min(sampleCount, CAPACITY - oldestIndex);
        System.arraycopy(samples, oldestIndex, copy, 0, firstLength);
        if (firstLength < sampleCount) {
            System.arraycopy(samples, 0, copy, firstLength, sampleCount - firstLength);
        }
        return copy;
    }

    void reset() {
        oldestIndex = 0;
        sampleCount = 0;
    }

    static Summary summarize(int[] sampleCopy) {
        if (sampleCopy.length == 0) {
            return new Summary(0, -1, -1, -1);
        }

        Arrays.sort(sampleCopy);
        return new Summary(
                sampleCopy.length,
                nearestRank(sampleCopy, 50),
                nearestRank(sampleCopy, 95),
                nearestRank(sampleCopy, 99));
    }

    private static int nearestRank(int[] sortedSamples, int percentile) {
        int rank = (sortedSamples.length * percentile + 99) / 100;
        return sortedSamples[rank - 1];
    }

    static String formatSummary(Summary summary) {
        return "CodecLatencySummary metric=" + METRIC_NAME +
                " unit=us samples=" + summary.sampleCount +
                " capacity=" + CAPACITY +
                " p50Us=" + percentileValue(summary, summary.p50Us) +
                " p95Us=" + percentileValue(summary, summary.p95Us) +
                " p99Us=" + percentileValue(summary, summary.p99Us);
    }

    private static String percentileValue(Summary summary, int value) {
        return summary.hasMeasurement() ? Integer.toString(value) : "NA";
    }
}
