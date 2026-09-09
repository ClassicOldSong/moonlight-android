package com.limelight.binding.video;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import android.media.MediaCodec;
import android.media.MediaFormat;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.LinkedHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = {33})
public class DecoderLatencySamplerTest {
    @Test
    public void percentilesUseMicrosecondsAndNearestRank() {
        DecoderLatencySampler sampler = new DecoderLatencySampler();
        sampler.recordMicroseconds(100);
        sampler.recordMicroseconds(200);
        sampler.recordMicroseconds(300);
        sampler.recordMicroseconds(400);

        DecoderLatencySampler.Summary summary =
                DecoderLatencySampler.summarize(sampler.copySamples());

        assertEquals(4, summary.sampleCount);
        assertEquals(200, summary.p50Us);
        assertEquals(400, summary.p95Us);
        assertEquals(400, summary.p99Us);
    }

    @Test
    public void circularBufferKeepsMostRecentSamples() {
        DecoderLatencySampler sampler = new DecoderLatencySampler();
        for (int value = 0; value <= DecoderLatencySampler.CAPACITY; value++) {
            sampler.recordMicroseconds(value);
        }

        int[] samples = sampler.copySamples();
        DecoderLatencySampler.Summary summary = DecoderLatencySampler.summarize(samples);

        assertEquals(DecoderLatencySampler.CAPACITY, samples.length);
        assertEquals(1, samples[0]);
        assertEquals(DecoderLatencySampler.CAPACITY,
                samples[DecoderLatencySampler.CAPACITY - 1]);
        assertEquals(DecoderLatencySampler.CAPACITY, summary.sampleCount);
        assertEquals(8192, summary.p50Us);
        assertEquals(15565, summary.p95Us);
        assertEquals(16221, summary.p99Us);
    }

    @Test
    public void emptySamplerReturnsNoMeasurement() {
        DecoderLatencySampler sampler = new DecoderLatencySampler();

        DecoderLatencySampler.Summary summary =
                DecoderLatencySampler.summarize(sampler.copySamples());

        assertFalse(summary.hasMeasurement());
        assertEquals(
                "CodecLatencySummary metric=" + DecoderLatencySampler.METRIC_NAME +
                        " unit=us samples=0 capacity=16384 p50Us=NA p95Us=NA p99Us=NA",
                DecoderLatencySampler.formatSummary(summary));
    }

    @Test
    public void recordingBeyondCapacityKeepsFixedSampleCount() {
        DecoderLatencySampler sampler = new DecoderLatencySampler();
        for (int value = 0; value < DecoderLatencySampler.CAPACITY * 3; value++) {
            sampler.recordMicroseconds(value);
        }

        assertEquals(DecoderLatencySampler.CAPACITY, sampler.copySamples().length);
    }

    @Test
    public void resetAndSnapshotsAreDefensiveAndDeterministic() {
        DecoderLatencySampler sampler = new DecoderLatencySampler();
        sampler.recordMicroseconds(30);
        sampler.recordMicroseconds(10);
        sampler.recordMicroseconds(20);

        int[] firstCopy = sampler.copySamples();
        firstCopy[0] = 999;
        DecoderLatencySampler.Summary first =
                DecoderLatencySampler.summarize(sampler.copySamples());
        DecoderLatencySampler.Summary second =
                DecoderLatencySampler.summarize(sampler.copySamples());

        assertEquals(20, first.p50Us);
        assertEquals(first.sampleCount, second.sampleCount);
        assertEquals(first.p50Us, second.p50Us);
        assertEquals(first.p95Us, second.p95Us);
        assertEquals(first.p99Us, second.p99Us);

        sampler.reset();
        assertEquals(0, sampler.copySamples().length);
    }

    @Test
    public void deltaRecordingRejectsNegativeAndClampsToIntegerMax() {
        DecoderLatencySampler sampler = new DecoderLatencySampler();

        assertEquals(-1, sampler.recordDeltaNs(2_000L, 1_000L));
        assertEquals(Integer.MAX_VALUE,
                sampler.recordDeltaNs(0L, ((long) Integer.MAX_VALUE + 1L) * 1_000L));

        assertArrayEquals(new int[]{Integer.MAX_VALUE}, sampler.copySamples());
    }

    @Test
    public void firstOutputDequeueRecordsPendingPtsExactlyOnce() {
        MediaCodecDecoderRenderer.DecoderLatencyState state =
                new MediaCodecDecoderRenderer.DecoderLatencyState();
        state.onInputQueueCallStarting(77L, 0, 1_000L);

        assertEquals(200, state.onOutputDequeued(77L, 201_000L));
        assertEquals(-1, state.onOutputDequeued(77L, 401_000L));

        DecoderLatencySampler.Summary summary = DecoderLatencySampler.summarize(
                state.copySamplesForCrash());
        assertEquals(1, summary.sampleCount);
        assertEquals(200, summary.p50Us);
    }

    @Test
    public void failedQueueCancelsOnlyTheExactPendingEntry() {
        MediaCodecDecoderRenderer.DecoderLatencyState state =
                new MediaCodecDecoderRenderer.DecoderLatencyState();
        MediaCodecDecoderRenderer.DecoderLatencyState.PendingInputToken first =
                state.onInputQueueCallStarting(77L, 0, 1_000L);
        state.onInputQueueCallStarting(77L, 0, 2_000L);

        state.onInputQueueFailed(first);
        assertEquals(3, state.onOutputDequeued(77L, 5_000L));

        MediaCodecDecoderRenderer.DecoderLatencyState.PendingInputToken failed =
                state.onInputQueueCallStarting(88L, 0, 10_000L);
        state.onInputQueueFailed(failed);
        assertEquals(-1, state.onOutputDequeued(88L, 20_000L));

        try {
            state.runInputQueueCall(99L, 0, (presentationTimeUs, codecFlags) -> {
                throw new IllegalStateException("queue failed");
            });
        }
        catch (IllegalStateException expected) {
            assertEquals("queue failed", expected.getMessage());
        }
        assertEquals(-1, state.onOutputDequeued(99L, System.nanoTime()));
    }

    @Test
    public void duplicatePtsFailureRemovesOnlyTheFailedOperationToken() {
        MediaCodecDecoderRenderer.DecoderLatencyState state =
                new MediaCodecDecoderRenderer.DecoderLatencyState();
        state.onInputQueueCallStarting(77L, 0, 1_000L);
        MediaCodecDecoderRenderer.DecoderLatencyState.PendingInputToken second =
                state.onInputQueueCallStarting(77L, 0, 2_000L);

        state.onInputQueueFailed(second);

        assertEquals(100, state.onOutputDequeued(77L, 101_000L));
        assertEquals(-1, state.onOutputDequeued(77L, 201_000L));
    }

    @Test
    public void duplicatePtsOutputsConsumeSuccessfulOperationsInFifoOrder() {
        MediaCodecDecoderRenderer.DecoderLatencyState state =
                new MediaCodecDecoderRenderer.DecoderLatencyState();
        state.onInputQueueCallStarting(77L, 0, 10_000L);
        state.onInputQueueCallStarting(77L, 0, 20_000L);

        assertEquals(100, state.onOutputDequeued(77L, 110_000L));
        assertEquals(200, state.onOutputDequeued(77L, 220_000L));
        assertArrayEquals(new int[]{100, 200}, state.copySamplesForCrash());
    }

    @Test
    public void codecConfigInputIsExcluded() {
        MediaCodecDecoderRenderer.DecoderLatencyState state =
                new MediaCodecDecoderRenderer.DecoderLatencyState();
        AtomicBoolean queueCalled = new AtomicBoolean();

        state.runInputQueueCall(
                0L,
                MediaCodec.BUFFER_FLAG_CODEC_CONFIG,
                (presentationTimeUs, codecFlags) -> queueCalled.set(true));

        assertTrue(queueCalled.get());
        assertEquals(-1, state.onOutputDequeued(0L, 2_000L));
        assertEquals(0, state.copySamplesForCrash().length);
    }

    @Test
    public void recoveryClearsPendingWhileRetainingSessionSamples() {
        MediaCodecDecoderRenderer.DecoderLatencyState state =
                new MediaCodecDecoderRenderer.DecoderLatencyState();
        state.onInputQueueCallStarting(1L, 0, 1_000L);
        assertEquals(100, state.onOutputDequeued(1L, 101_000L));
        state.onInputQueueCallStarting(2L, 0, 2_000L);

        state.clearPendingForCodecInvalidation();

        assertEquals(-1, state.onOutputDequeued(2L, 202_000L));
        assertEquals(1, state.copySamplesForCrash().length);
    }

    @Test
    public void recoveryClearSeversEveryRetainedDuplicatePtsLink() {
        MediaCodecDecoderRenderer.DecoderLatencyState state =
                new MediaCodecDecoderRenderer.DecoderLatencyState();
        MediaCodecDecoderRenderer.DecoderLatencyState.PendingInputToken first =
                state.onInputQueueCallStarting(77L, 0, 1_000L);
        MediaCodecDecoderRenderer.DecoderLatencyState.PendingInputToken second =
                state.onInputQueueCallStarting(77L, 0, 2_000L);
        MediaCodecDecoderRenderer.DecoderLatencyState.PendingInputToken third =
                state.onInputQueueCallStarting(77L, 0, 3_000L);
        assertSame(second, first.next);
        assertSame(third, second.next);

        state.clearPendingForCodecInvalidation();

        assertNull(first.next);
        assertNull(second.next);
        assertNull(third.next);
        assertEquals(-1, state.onOutputDequeued(77L, 103_000L));
    }

    @Test
    public void newSessionResetClearsPendingSamplesAndSummaryGuard() {
        MediaCodecDecoderRenderer.DecoderLatencyState state =
                new MediaCodecDecoderRenderer.DecoderLatencyState();
        state.onInputQueueCallStarting(1L, 0, 1_000L);
        state.onOutputDequeued(1L, 101_000L);
        assertNotNull(state.takeSamplesForStop());

        state.resetForNewSession();

        assertEquals(0, state.copySamplesForCrash().length);
        assertNotNull(state.takeSamplesForStop());
    }

    @Test
    public void newSessionResetSeversEveryRetainedDuplicatePtsLink() {
        MediaCodecDecoderRenderer.DecoderLatencyState state =
                new MediaCodecDecoderRenderer.DecoderLatencyState();
        MediaCodecDecoderRenderer.DecoderLatencyState.PendingInputToken first =
                state.onInputQueueCallStarting(77L, 0, 1_000L);
        MediaCodecDecoderRenderer.DecoderLatencyState.PendingInputToken second =
                state.onInputQueueCallStarting(77L, 0, 2_000L);
        MediaCodecDecoderRenderer.DecoderLatencyState.PendingInputToken third =
                state.onInputQueueCallStarting(77L, 0, 3_000L);
        assertSame(second, first.next);
        assertSame(third, second.next);

        state.resetForNewSession();

        assertNull(first.next);
        assertNull(second.next);
        assertNull(third.next);
        assertEquals(-1, state.onOutputDequeued(77L, 103_000L));
    }

    @Test
    public void stopSnapshotAndResetIsIdempotentAndCrashSnapshotIsCopyOnly() {
        MediaCodecDecoderRenderer.DecoderLatencyState state =
                new MediaCodecDecoderRenderer.DecoderLatencyState();
        state.onInputQueueCallStarting(1L, 0, 1_000L);
        state.onOutputDequeued(1L, 101_000L);

        int[] firstCrash = state.copySamplesForCrash();
        firstCrash[0] = 999;
        assertArrayEquals(new int[]{100}, state.copySamplesForCrash());

        assertArrayEquals(new int[]{100}, state.takeSamplesForStop());
        assertNull(state.takeSamplesForStop());
        assertEquals(0, state.copySamplesForCrash().length);
    }

    @Test
    public void stopSnapshotSeversEveryRetainedDuplicatePtsLink() {
        MediaCodecDecoderRenderer.DecoderLatencyState state =
                new MediaCodecDecoderRenderer.DecoderLatencyState();
        MediaCodecDecoderRenderer.DecoderLatencyState.PendingInputToken first =
                state.onInputQueueCallStarting(77L, 0, 1_000L);
        MediaCodecDecoderRenderer.DecoderLatencyState.PendingInputToken second =
                state.onInputQueueCallStarting(77L, 0, 2_000L);
        MediaCodecDecoderRenderer.DecoderLatencyState.PendingInputToken third =
                state.onInputQueueCallStarting(77L, 0, 3_000L);
        assertSame(second, first.next);
        assertSame(third, second.next);

        assertNotNull(state.takeSamplesForStop());

        assertNull(first.next);
        assertNull(second.next);
        assertNull(third.next);
        assertEquals(-1, state.onOutputDequeued(77L, 103_000L));
    }

    @Test
    public void interruptedJoinWaitsForTerminationAndRestoresInterrupt() throws Exception {
        CountDownLatch releaseWorker = new CountDownLatch(1);
        CountDownLatch joinerEntered = new CountDownLatch(1);
        CountDownLatch joinerReturned = new CountDownLatch(1);
        AtomicBoolean interruptRestored = new AtomicBoolean();
        Thread worker = new Thread(() -> awaitUnchecked(releaseWorker));
        Thread joiner = new Thread(() -> {
            Thread.currentThread().interrupt();
            joinerEntered.countDown();
            MediaCodecDecoderRenderer.joinThreadPreservingInterrupt(worker);
            interruptRestored.set(Thread.currentThread().isInterrupted());
            joinerReturned.countDown();
        });

        worker.start();
        joiner.start();
        try {
            assertTrue(joinerEntered.await(5, TimeUnit.SECONDS));
            assertFalse(joinerReturned.await(100, TimeUnit.MILLISECONDS));
        }
        finally {
            releaseWorker.countDown();
        }

        assertTrue(joinerReturned.await(5, TimeUnit.SECONDS));
        worker.join();
        joiner.join();
        assertFalse(worker.isAlive());
        assertTrue(interruptRestored.get());
    }

    @Test
    public void blockedInputQueueCallDoesNotBlockUnrelatedOutputConsumption()
            throws Exception {
        MediaCodecDecoderRenderer.DecoderLatencyState state =
                new MediaCodecDecoderRenderer.DecoderLatencyState();
        state.onInputQueueCallStarting(11L, 0, 1_000L);
        CountDownLatch queueCallEntered = new CountDownLatch(1);
        CountDownLatch releaseQueueCall = new CountDownLatch(1);
        CountDownLatch outputReturned = new CountDownLatch(1);
        AtomicInteger latencyUs = new AtomicInteger(-2);
        Thread queueThread = new Thread(() -> state.runInputQueueCall(
                22L,
                0,
                (presentationTimeUs, codecFlags) -> {
                    queueCallEntered.countDown();
                    awaitUnchecked(releaseQueueCall);
                }));
        Thread outputThread = new Thread(() -> {
            latencyUs.set(state.onOutputDequeued(11L, 101_000L));
            outputReturned.countDown();
        });

        queueThread.start();
        assertTrue(queueCallEntered.await(5, TimeUnit.SECONDS));
        outputThread.start();
        boolean outputCompleted;
        try {
            outputCompleted = outputReturned.await(5, TimeUnit.SECONDS);
        }
        finally {
            releaseQueueCall.countDown();
        }

        queueThread.join();
        outputThread.join();
        assertTrue(outputCompleted);
        assertEquals(100, latencyUs.get());
    }

    @Test
    public void invalidationClearsPendingBeforeCodecCallWithoutHoldingLatencyLock()
            throws Exception {
        MediaCodecDecoderRenderer.DecoderLatencyState state =
                new MediaCodecDecoderRenderer.DecoderLatencyState();
        state.onInputQueueCallStarting(33L, 0, 1_000L);
        CountDownLatch invalidationEntered = new CountDownLatch(1);
        CountDownLatch releaseInvalidation = new CountDownLatch(1);
        CountDownLatch outputReturned = new CountDownLatch(1);
        AtomicInteger latencyUs = new AtomicInteger(-2);
        Thread invalidationThread = new Thread(() ->
                state.clearPendingAndRunCodecInvalidation(() -> {
                    invalidationEntered.countDown();
                    awaitUnchecked(releaseInvalidation);
                }));
        Thread outputThread = new Thread(() -> {
            latencyUs.set(state.onOutputDequeued(33L, 101_000L));
            outputReturned.countDown();
        });

        invalidationThread.start();
        assertTrue(invalidationEntered.await(5, TimeUnit.SECONDS));
        outputThread.start();
        boolean outputCompleted;
        try {
            outputCompleted = outputReturned.await(5, TimeUnit.SECONDS);
        }
        finally {
            releaseInvalidation.countDown();
        }

        invalidationThread.join();
        outputThread.join();
        assertTrue(outputCompleted);
        assertEquals(-1, latencyUs.get());
    }

    @Test
    public void inputQueueCallOverlappingInvalidationRunsButIsNotTracked()
            throws Exception {
        MediaCodecDecoderRenderer.DecoderLatencyState state =
                new MediaCodecDecoderRenderer.DecoderLatencyState();
        CountDownLatch invalidationEntered = new CountDownLatch(1);
        CountDownLatch releaseInvalidation = new CountDownLatch(1);
        CountDownLatch queueCallEntered = new CountDownLatch(1);
        Thread invalidationThread = new Thread(() ->
                state.clearPendingAndRunCodecInvalidation(() -> {
                    invalidationEntered.countDown();
                    awaitUnchecked(releaseInvalidation);
                }));
        Thread queueThread = new Thread(() -> state.runInputQueueCall(
                44L, 0, (presentationTimeUs, codecFlags) ->
                        queueCallEntered.countDown()));

        invalidationThread.start();
        assertTrue(invalidationEntered.await(5, TimeUnit.SECONDS));
        queueThread.start();
        boolean queueCallCompleted;
        try {
            queueCallCompleted = queueCallEntered.await(5, TimeUnit.SECONDS);
        }
        finally {
            releaseInvalidation.countDown();
        }

        invalidationThread.join();
        queueThread.join();
        assertTrue(queueCallCompleted);
        assertEquals(-1, state.onOutputDequeued(44L, System.nanoTime()));
    }

    @Test
    public void codecConfigQueueCallDoesNotAcquireLatencyLockDuringInvalidation()
            throws Exception {
        MediaCodecDecoderRenderer.DecoderLatencyState state =
                new MediaCodecDecoderRenderer.DecoderLatencyState();
        CountDownLatch invalidationEntered = new CountDownLatch(1);
        CountDownLatch releaseInvalidation = new CountDownLatch(1);
        CountDownLatch queueCallEntered = new CountDownLatch(1);
        Thread invalidationThread = new Thread(() ->
                state.clearPendingAndRunCodecInvalidation(() -> {
                    invalidationEntered.countDown();
                    awaitUnchecked(releaseInvalidation);
                }));
        Thread queueThread = new Thread(() -> state.runInputQueueCall(
                0L,
                MediaCodec.BUFFER_FLAG_CODEC_CONFIG,
                (presentationTimeUs, codecFlags) -> queueCallEntered.countDown()));

        invalidationThread.start();
        assertTrue(invalidationEntered.await(5, TimeUnit.SECONDS));
        queueThread.start();
        boolean queueCallCompleted;
        try {
            queueCallCompleted = queueCallEntered.await(5, TimeUnit.SECONDS);
        }
        finally {
            releaseInvalidation.countDown();
        }

        invalidationThread.join();
        queueThread.join();
        assertTrue(queueCallCompleted);
    }

    @Test
    public void aggregateLatencyKeepsExistingMillisecondWindowSemanticsAtDequeue() {
        assertEquals(0,
                MediaCodecDecoderRenderer.aggregateDecoderLatencyMs(999));
        assertEquals(999,
                MediaCodecDecoderRenderer.aggregateDecoderLatencyMs(999_999));
        assertEquals(-1,
                MediaCodecDecoderRenderer.aggregateDecoderLatencyMs(1_000_000));
        assertEquals(-1,
                MediaCodecDecoderRenderer.aggregateDecoderLatencyMs(-1));
    }

    @Test
    public void allFiveOutputDequeueSitesUseImmediateLatencyWrapper() throws IOException {
        String source = readRendererSource();

        assertEquals(1, occurrences(source, "videoDecoder.dequeueOutputBuffer("));
        Matcher wrapperCalls = Pattern.compile(
                "(?<!int )dequeueOutputBufferWithLatency\\(").matcher(source);
        int callCount = 0;
        while (wrapperCalls.find()) {
            callCount++;
        }
        assertEquals(5, callCount);
        assertEquals(0, occurrences(source, "updateDecodeLatencyStats("));
    }

    @Test
    public void crashDiagnosticsUseOnlyActiveSuccessfulProfileAndCopyOnlySummary() {
        LinkedHashMap<String, Integer> options = new LinkedHashMap<>();
        options.put(MediaFormat.KEY_LOW_LATENCY, 1);
        MediaCodecDecoderRenderer.DecoderConfigurationProfile profile =
                MediaCodecDecoderRenderer.DecoderConfigurationProfile.from(
                        new MediaCodecHelper.AppliedLowLatencyOptions(
                                2, "android-standard", options));
        MediaCodecDecoderRenderer.ConfiguredDecoderState state =
                MediaCodecDecoderRenderer.commitConfiguredState(
                        null,
                        MediaFormat.createVideoFormat("video/avc", 1920, 1080),
                        profile,
                        true);
        DecoderLatencySampler sampler = new DecoderLatencySampler();
        sampler.recordMicroseconds(100);
        sampler.recordMicroseconds(300);
        DecoderLatencySampler.Summary summary =
                DecoderLatencySampler.summarize(sampler.copySamples());

        assertEquals(
                "Codec low-latency activeProfileIndex=2 activeProfileName=android-standard|" +
                        "CodecLatencySummary metric=" + DecoderLatencySampler.METRIC_NAME +
                        " unit=us samples=2 capacity=16384 p50Us=100 p95Us=300 p99Us=300",
                MediaCodecDecoderRenderer.formatCrashLatencyDiagnostics(
                        state, summary, "|"));
        assertEquals(2, sampler.copySamples().length);
    }

    private static String readRendererSource() throws IOException {
        File directory = new File(System.getProperty("user.dir"));
        for (int depth = 0; depth < 6 && directory != null; depth++) {
            File appRelative = new File(directory,
                    "app/src/main/java/com/limelight/binding/video/MediaCodecDecoderRenderer.java");
            if (appRelative.isFile()) {
                return new String(Files.readAllBytes(appRelative.toPath()),
                        StandardCharsets.UTF_8);
            }
            File moduleRelative = new File(directory,
                    "src/main/java/com/limelight/binding/video/MediaCodecDecoderRenderer.java");
            if (moduleRelative.isFile()) {
                return new String(Files.readAllBytes(moduleRelative.toPath()),
                        StandardCharsets.UTF_8);
            }
            directory = directory.getParentFile();
        }
        throw new IOException("Unable to locate MediaCodecDecoderRenderer.java");
    }

    private static int occurrences(String source, String needle) {
        int count = 0;
        int offset = 0;
        while ((offset = source.indexOf(needle, offset)) >= 0) {
            count++;
            offset += needle.length();
        }
        return count;
    }

    private static void awaitUnchecked(CountDownLatch latch) {
        try {
            latch.await();
        }
        catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError(e);
        }
    }
}
