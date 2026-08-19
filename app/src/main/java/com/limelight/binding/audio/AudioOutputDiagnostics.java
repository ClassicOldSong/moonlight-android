package com.limelight.binding.audio;

import com.limelight.LimeLog;

final class AudioOutputDiagnostics {
    enum FailureReason {
        NONE,
        UNSUPPORTED_FORMAT,
        ROUTE_UNSUPPORTED,
        TRACK_INIT_FAILED,
        ENCODER_INIT_FAILED,
        ENCODE_FAILED,
        WRITE_FAILED,
        ROUTE_CHANGED,
        QUEUE_OVERFLOW_LIMIT,
        INTERNAL_STATE_ERROR
    }

    private long callbacks;
    private long callbackNanos;
    private long encodedFrames;
    private long encodeNanos;
    private long writeNanos;
    private long maximumEncodeNanos;
    private long maximumWriteNanos;
    private long windowMaximumEncodeNanos;
    private long windowMaximumWriteNanos;
    private long droppedFrames;
    private int maximumQueueDepth;
    private FailureReason firstFailure = FailureReason.NONE;
    private long lastLogNanos;

    synchronized void recordCallback(long elapsedNanos) {
        callbacks++;
        callbackNanos += elapsedNanos;
    }

    synchronized void recordFrame(long encodeElapsedNanos, long writeElapsedNanos, int queueDepth) {
        encodedFrames++;
        encodeNanos += encodeElapsedNanos;
        writeNanos += writeElapsedNanos;
        maximumEncodeNanos = Math.max(maximumEncodeNanos, encodeElapsedNanos);
        maximumWriteNanos = Math.max(maximumWriteNanos, writeElapsedNanos);
        windowMaximumEncodeNanos = Math.max(windowMaximumEncodeNanos, encodeElapsedNanos);
        windowMaximumWriteNanos = Math.max(windowMaximumWriteNanos, writeElapsedNanos);
        if (queueDepth > maximumQueueDepth) {
            maximumQueueDepth = queueDepth;
        }
    }

    synchronized void recordDroppedFrames(int count) {
        droppedFrames += count;
    }

    synchronized void recordFailure(FailureReason reason) {
        if (firstFailure == FailureReason.NONE) {
            firstFailure = reason;
            LimeLog.warning("Encoded audio first failure: " + reason);
        }
    }

    synchronized FailureReason getFirstFailure() {
        return firstFailure;
    }

    synchronized void logPeriodic(String state, int queueDepth) {
        long now = System.nanoTime();
        if (now - lastLogNanos < 5_000_000_000L) {
            return;
        }
        lastLogNanos = now;
        long averageCallbackUs = callbacks == 0 ? 0 : callbackNanos / callbacks / 1_000;
        long averageEncodeUs = encodedFrames == 0 ? 0 : encodeNanos / encodedFrames / 1_000;
        long averageWriteUs = encodedFrames == 0 ? 0 : writeNanos / encodedFrames / 1_000;
        LimeLog.info("Encoded audio metrics: state=" + state + " frames=" + encodedFrames +
                " queue=" + queueDepth + " maxQueue=" + maximumQueueDepth +
                " drops=" + droppedFrames + " avgCallbackUs=" + averageCallbackUs +
                " avgEncodeUs=" + averageEncodeUs + " avgWriteUs=" + averageWriteUs);
        LimeLog.info("Encoded audio timing peaks: windowMaxEncodeUs=" +
                windowMaximumEncodeNanos / 1_000 + " windowMaxWriteUs=" +
                windowMaximumWriteNanos / 1_000 + " sessionMaxEncodeUs=" +
                maximumEncodeNanos / 1_000 + " sessionMaxWriteUs=" +
                maximumWriteNanos / 1_000);
        windowMaximumEncodeNanos = 0;
        windowMaximumWriteNanos = 0;
    }
}
