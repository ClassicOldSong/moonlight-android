package com.limelight.binding.audio;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.widget.Toast;

import com.limelight.LimeLog;
import com.limelight.R;
import com.limelight.nvstream.av.audio.AudioRenderer;
import com.limelight.nvstream.jni.MoonBridge;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

public final class Ac3AudioRenderer implements AudioRenderer {
    private enum State {
        NEW,
        READY_ENCODED,
        READY_PCM_FALLBACK,
        RUNNING_ENCODED,
        FALLING_BACK,
        RUNNING_PCM_FALLBACK,
        STOPPED,
        CLEANED
    }

    private final Context context;
    private final AudioOutputMode requestedMode;
    private final EncodedAudioFormat outputFormat;
    private final boolean pcmAudioFxCompatibilityValue;
    private final boolean disableWarnings;
    private final AndroidAudioCapabilities capabilities;
    private final PcmFrameAccumulator accumulator = new PcmFrameAccumulator();
    private final NativeAc3Encoder encoder = new NativeAc3Encoder();
    private final EncodedAudioTrackSink encodedSink;
    private final AudioOutputDiagnostics diagnostics = new AudioOutputDiagnostics();
    private final QueueOverloadMonitor overloadMonitor = new QueueOverloadMonitor();
    private final AudioRouteMonitor routeMonitor;
    private final AndroidAudioRenderer pcmFallback;
    private final ByteBuffer encodedPacket = ByteBuffer
            .allocateDirect(NativeAc3Encoder.OUTPUT_CAPACITY)
            .order(ByteOrder.nativeOrder());
    private final Object stateLock = new Object();

    private volatile State state = State.NEW;
    private volatile boolean workerRunning;
    private volatile boolean stopRequested;
    private volatile AudioOutputDiagnostics.FailureReason fallbackRequested =
            AudioOutputDiagnostics.FailureReason.NONE;
    private Thread workerThread;
    private MoonBridge.AudioConfiguration inputConfiguration;
    private int inputSampleRate;
    private int inputSamplesPerFrame;
    private boolean streamStarted;
    private boolean pcmSetupSucceeded;

    public Ac3AudioRenderer(Context context, AudioOutputMode requestedMode,
                            boolean pcmAudioFxCompatibilityValue, boolean disableWarnings) {
        this.context = context.getApplicationContext();
        this.requestedMode = requestedMode;
        outputFormat = EncodedAudioFormat.fromMode(requestedMode);
        this.pcmAudioFxCompatibilityValue = pcmAudioFxCompatibilityValue;
        this.disableWarnings = disableWarnings;
        capabilities = new AndroidAudioCapabilities(this.context);
        encodedSink = new EncodedAudioTrackSink(outputFormat);
        pcmFallback = new AndroidAudioRenderer(this.context, pcmAudioFxCompatibilityValue);
        routeMonitor = new AudioRouteMonitor(this.context,
                () -> requestFallback(AudioOutputDiagnostics.FailureReason.ROUTE_CHANGED));
    }

    @Override
    public int setup(MoonBridge.AudioConfiguration audioConfiguration, int sampleRate,
                     int samplesPerFrame) {
        synchronized (stateLock) {
            if (state != State.NEW) {
                return -1;
            }
            stopRequested = false;
            inputConfiguration = audioConfiguration;
            inputSampleRate = sampleRate;
            inputSamplesPerFrame = samplesPerFrame;
            fallbackRequested = AudioOutputDiagnostics.FailureReason.NONE;
            overloadMonitor.reset();

            LimeLog.info("Audio output requested=" + requestedMode +
                    " input=" + audioConfiguration.channelCount + "ch@" + sampleRate +
                    " callbackSamples=" + samplesPerFrame);

            if (audioConfiguration.channelCount != PcmFrameAccumulator.CHANNELS ||
                    sampleRate != 48_000) {
                return setupPcmFallback(AudioOutputDiagnostics.FailureReason.UNSUPPORTED_FORMAT);
            }

            AndroidAudioCapabilities.Result capability = capabilities.probeEncodedRoute(
                    EncodedAudioTrackSink.buildFormat(outputFormat),
                    EncodedAudioTrackSink.buildAttributes(), outputFormat.androidEncoding);
            LimeLog.info(outputFormat.displayName + " capability: " + capability.evidence);
            if (requestedMode.isAutomatic() && !capability.reportedSupported) {
                return setupPcmFallback(AudioOutputDiagnostics.FailureReason.ROUTE_UNSUPPORTED);
            }

            if (!encoder.create(outputFormat)) {
                return setupPcmFallback(AudioOutputDiagnostics.FailureReason.ENCODER_INIT_FAILED);
            }
            if (!encodedSink.createAndValidate()) {
                encoder.destroy();
                return setupPcmFallback(AudioOutputDiagnostics.FailureReason.TRACK_INIT_FAILED);
            }

            LimeLog.info(outputFormat.displayName + " effective mode=" + requestedMode + " encoder=" +
                    NativeAc3Encoder.getBuildInfo() + " bitrate=" +
                    outputFormat.bitrate +
                    " channelMap=FL,FR,FC,LFE,BL,BR->FL,FR,FC,LFE,BL,BR");
            state = State.READY_ENCODED;
            return 0;
        }
    }

    private int setupPcmFallback(AudioOutputDiagnostics.FailureReason reason) {
        diagnostics.recordFailure(reason);
        int result = pcmFallback.setup(inputConfiguration, inputSampleRate, inputSamplesPerFrame);
        pcmSetupSucceeded = result == 0;
        state = pcmSetupSucceeded ? State.READY_PCM_FALLBACK : State.STOPPED;
        LimeLog.warning(outputFormat.displayName +
                " unavailable during setup; effective mode=PCM reason=" + reason);
        if (pcmSetupSucceeded) {
            showFallbackMessage();
        }
        return result;
    }

    @Override
    public void start() {
        synchronized (stateLock) {
            if (streamStarted || stopRequested) {
                return;
            }
            if (state != State.READY_PCM_FALLBACK && state != State.READY_ENCODED) {
                return;
            }
            streamStarted = true;
            if (state == State.READY_PCM_FALLBACK) {
                pcmFallback.start();
                state = State.RUNNING_PCM_FALLBACK;
                return;
            }
            if (!encodedSink.start()) {
                performFallback(AudioOutputDiagnostics.FailureReason.TRACK_INIT_FAILED);
                return;
            }
            accumulator.start();
            workerRunning = true;
            state = State.RUNNING_ENCODED;
            routeMonitor.register(encodedSink.getTrack());
            workerThread = new Thread(this::workerLoop, "ArtemisEncodedAudio");
            workerThread.start();
            LimeLog.info(outputFormat.displayName + " track started: " + encodedSink.describeRoute());
        }
    }

    @Override
    public void playDecodedAudio(short[] audioData) {
        State current = state;
        if (current == State.RUNNING_PCM_FALLBACK) {
            pcmFallback.playDecodedAudio(audioData);
            return;
        }
        if (current != State.RUNNING_ENCODED) {
            return;
        }

        long startNanos = System.nanoTime();
        int dropped = accumulator.offer(audioData);
        if (dropped > 0) {
            diagnostics.recordDroppedFrames(dropped);
            boolean sustainedOverload = overloadMonitor.recordDrops(dropped, System.nanoTime());
            LimeLog.warning(outputFormat.displayName + " queue overflow: dropped=" + dropped +
                    " windowDrops=" + overloadMonitor.getWindowDrops() +
                    "/" + QueueOverloadMonitor.DROP_THRESHOLD);
            if (sustainedOverload) {
                requestFallback(AudioOutputDiagnostics.FailureReason.QUEUE_OVERFLOW_LIMIT);
            }
        }
        diagnostics.recordCallback(System.nanoTime() - startNanos);
    }

    private void workerLoop() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO);
        AudioOutputDiagnostics.FailureReason terminalReason = AudioOutputDiagnostics.FailureReason.NONE;
        while (workerRunning) {
            if (fallbackRequested != AudioOutputDiagnostics.FailureReason.NONE) {
                terminalReason = fallbackRequested;
                break;
            }

            PcmFrameAccumulator.Frame frame = null;
            try {
                frame = accumulator.take();
                if (frame == null) {
                    if (fallbackRequested != AudioOutputDiagnostics.FailureReason.NONE) {
                        terminalReason = fallbackRequested;
                    }
                    break;
                }

                long encodeStart = System.nanoTime();
                int encodedBytes = encoder.encode(frame.samples, encodedPacket);
                long encodeElapsed = System.nanoTime() - encodeStart;
                if (encodedBytes != outputFormat.frameBytes ||
                        (encodedPacket.get(0) & 0xFF) != 0x0B ||
                        (encodedPacket.get(1) & 0xFF) != 0x77) {
                    terminalReason = AudioOutputDiagnostics.FailureReason.ENCODE_FAILED;
                    break;
                }

                long writeStart = System.nanoTime();
                int written = encodedSink.writeCompleteAccessUnit(encodedPacket, encodedBytes);
                long writeElapsed = System.nanoTime() - writeStart;
                if (written != encodedBytes) {
                    terminalReason = AudioOutputDiagnostics.FailureReason.WRITE_FAILED;
                    break;
                }
                diagnostics.recordFrame(encodeElapsed, writeElapsed, accumulator.getQueueDepth());
                diagnostics.logPeriodic(state.name(), accumulator.getQueueDepth());
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                if (fallbackRequested != AudioOutputDiagnostics.FailureReason.NONE) {
                    terminalReason = fallbackRequested;
                }
                break;
            } catch (RuntimeException error) {
                LimeLog.warning(outputFormat.displayName + " worker exception: " + error);
                terminalReason = AudioOutputDiagnostics.FailureReason.INTERNAL_STATE_ERROR;
                break;
            } finally {
                if (frame != null) {
                    accumulator.release(frame);
                }
            }
        }

        workerRunning = false;
        if (terminalReason != AudioOutputDiagnostics.FailureReason.NONE &&
                state == State.RUNNING_ENCODED) {
            performFallback(terminalReason);
        }
    }

    private void requestFallback(AudioOutputDiagnostics.FailureReason reason) {
        if (state != State.RUNNING_ENCODED ||
                fallbackRequested != AudioOutputDiagnostics.FailureReason.NONE) {
            return;
        }
        fallbackRequested = reason;
        accumulator.stop();
        Thread worker = workerThread;
        if (worker != null) {
            worker.interrupt();
        }
    }

    private void performFallback(AudioOutputDiagnostics.FailureReason reason) {
        synchronized (stateLock) {
            if (state == State.RUNNING_PCM_FALLBACK || state == State.FALLING_BACK ||
                    state == State.STOPPED || state == State.CLEANED || stopRequested) {
                return;
            }
            state = State.FALLING_BACK;
            diagnostics.recordFailure(reason);
            workerRunning = false;
            accumulator.stop();
            routeMonitor.unregister();
            encodedSink.release();
            encoder.destroy();

            if (stopRequested) {
                state = State.STOPPED;
                return;
            }

            int result = pcmFallback.setup(inputConfiguration, inputSampleRate, inputSamplesPerFrame);
            pcmSetupSucceeded = result == 0;
            if (pcmSetupSucceeded) {
                if (streamStarted && !stopRequested) {
                    pcmFallback.start();
                    state = State.RUNNING_PCM_FALLBACK;
                } else {
                    state = State.READY_PCM_FALLBACK;
                }
                LimeLog.warning(outputFormat.displayName +
                        " runtime fallback complete; effective mode=PCM reason=" + reason);
                showFallbackMessage();
            } else {
                state = State.STOPPED;
                LimeLog.severe(outputFormat.displayName +
                        " runtime fallback failed to initialize PCM: " + result);
            }
        }
    }

    private void showFallbackMessage() {
        if (disableWarnings) {
            return;
        }
        new Handler(Looper.getMainLooper()).post(() -> Toast.makeText(context,
                context.getString(R.string.encoded_audio_fallback_to_pcm),
                Toast.LENGTH_LONG).show());
    }

    @Override
    public void stop() {
        stopRequested = true;
        Thread worker = null;
        synchronized (stateLock) {
            State current = state;
            if (current == State.CLEANED || current == State.STOPPED) {
                streamStarted = false;
                return;
            }
            if (current == State.READY_PCM_FALLBACK || current == State.RUNNING_PCM_FALLBACK) {
                if (current == State.RUNNING_PCM_FALLBACK && streamStarted && pcmSetupSucceeded) {
                    pcmFallback.stop();
                }
                streamStarted = false;
                state = State.STOPPED;
                return;
            }

            routeMonitor.unregister();
            workerRunning = false;
            accumulator.stop();
            worker = workerThread;
            if (worker != null) {
                worker.interrupt();
            }
            streamStarted = false;
            state = State.STOPPED;
        }

        if (worker != null && worker != Thread.currentThread()) {
            joinWorker(worker, 1_000);
        }
        encodedSink.stopAndFlush();
        if (worker != null && worker != Thread.currentThread() && worker.isAlive()) {
            worker.interrupt();
            joinWorker(worker, 1_000);
        }
        if (worker == null || !worker.isAlive()) {
            workerThread = null;
        } else {
            LimeLog.warning(outputFormat.displayName + " worker did not stop within timeout");
        }
    }

    private static void joinWorker(Thread worker, long timeoutMillis) {
        try {
            worker.join(timeoutMillis);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    @Override
    public void cleanup() {
        stop();
        synchronized (stateLock) {
            if (state == State.CLEANED) {
                return;
            }
            routeMonitor.unregister();
            accumulator.stop();
            encodedSink.release();
            if (workerThread == null || !workerThread.isAlive()) {
                encoder.destroy();
            }
            if (pcmSetupSucceeded) {
                pcmFallback.cleanup();
                pcmSetupSucceeded = false;
            }
            state = State.CLEANED;
        }
    }
}
