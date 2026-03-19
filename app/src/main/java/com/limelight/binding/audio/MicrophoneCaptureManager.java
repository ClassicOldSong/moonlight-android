package com.limelight.binding.audio;

import android.content.Context;
import android.content.pm.PackageManager;
import android.media.AudioDeviceInfo;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import androidx.core.content.ContextCompat;

import com.limelight.LimeLog;
import com.limelight.R;
import com.limelight.nvstream.jni.MoonBridge;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class MicrophoneCaptureManager {
    public interface LevelListener {
        void onLevelUpdate(double level, boolean signalDetected, String status);
    }

    public static final class InputDeviceEntry {
        public final int id;
        public final String label;

        public InputDeviceEntry(int id, String label) {
            this.id = id;
            this.label = label;
        }
    }

    private static final int SAMPLE_RATE = 48000;
    private static final int CHANNEL_COUNT = 1;
    private static final int FRAME_SIZE = 960;
    private static final int DEFAULT_BITRATE = 24000;
    private static final int LEVEL_UPDATE_INTERVAL_MS = 50;
    private static final int SIGNAL_THRESHOLD = 700;

    private final Context context;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private AudioRecord audioRecord;
    private Thread captureThread;
    private volatile boolean running;
    private boolean streamingToHost;
    private LevelListener levelListener;
    private String currentStatus;
    private double currentLevel;
    private boolean signalDetected;

    public MicrophoneCaptureManager(Context context) {
        this.context = context.getApplicationContext();
        this.currentStatus = string(R.string.microphone_preview_inactive);
    }

    public static boolean hasRecordAudioPermission(Context context) {
        return ContextCompat.checkSelfPermission(context, android.Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED;
    }

    public static List<InputDeviceEntry> getAvailableInputDevices(Context context) {
        List<InputDeviceEntry> entries = new ArrayList<>();

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            return entries;
        }

        AudioManager audioManager = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
        if (audioManager == null) {
            return entries;
        }

        for (AudioDeviceInfo deviceInfo : audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS)) {
            entries.add(new InputDeviceEntry(deviceInfo.getId(), describeDevice(deviceInfo)));
        }

        return entries;
    }

    public boolean startPreview(int preferredDeviceId, LevelListener listener) {
        return startCapture(preferredDeviceId, listener, false);
    }

    public boolean startStreaming(int preferredDeviceId, LevelListener listener) {
        return startCapture(preferredDeviceId, listener, true);
    }

    public void stop() {
        AudioRecord recordToRelease;
        Thread threadToJoin;
        boolean wasStreaming;

        running = false;
        recordToRelease = audioRecord;
        threadToJoin = captureThread;
        wasStreaming = streamingToHost;

        if (recordToRelease != null) {
            try {
                recordToRelease.stop();
            }
            catch (IllegalStateException ignored) {
            }
        }

        if (threadToJoin != null) {
            try {
                threadToJoin.join(2000);
            }
            catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        captureThread = null;
        audioRecord = null;
        if (recordToRelease != null) {
            recordToRelease.release();
        }

        if (wasStreaming) {
            MoonBridge.stopMicrophoneStreaming();
            MoonBridge.cleanupMicrophoneEncoder();
        }
        streamingToHost = false;

        currentLevel = 0.0;
        signalDetected = false;
        dispatchStatus(string(R.string.microphone_preview_inactive), 0.0, false);
    }

    private boolean startCapture(int preferredDeviceId, LevelListener listener, boolean streamToHost) {
        CaptureConfig config;
        AudioRecord newRecord;
        final int bufferSamples;

        stop();
        levelListener = listener;

        if (!hasRecordAudioPermission(context)) {
            dispatchStatus(string(R.string.microphone_preview_permission_required), 0.0, false);
            return false;
        }

        if (streamToHost && !MoonBridge.isMicrophoneStreamActive()) {
            dispatchStatus(string(R.string.microphone_host_not_negotiated), 0.0, false);
            return false;
        }

        config = createCaptureConfig(preferredDeviceId);
        if (config == null) {
            dispatchStatus(string(R.string.microphone_preview_open_failed), 0.0, false);
            return false;
        }

        newRecord = config.record;
        bufferSamples = config.bufferSamples;

        if (streamToHost && MoonBridge.setupMicrophoneEncoder(SAMPLE_RATE, CHANNEL_COUNT, DEFAULT_BITRATE) != 0) {
            newRecord.release();
            dispatchStatus(string(R.string.microphone_encoder_setup_failed), 0.0, false);
            return false;
        }

        try {
            newRecord.startRecording();
        }
        catch (IllegalStateException e) {
            if (streamToHost) {
                MoonBridge.cleanupMicrophoneEncoder();
            }
            newRecord.release();
            dispatchStatus(string(R.string.microphone_capture_start_failed), 0.0, false);
            return false;
        }

        if (newRecord.getRecordingState() != AudioRecord.RECORDSTATE_RECORDING) {
            if (streamToHost) {
                MoonBridge.cleanupMicrophoneEncoder();
            }
            newRecord.release();
            dispatchStatus(string(R.string.microphone_capture_start_failed), 0.0, false);
            return false;
        }

        LimeLog.info(String.format((Locale) null,
                "Microphone capture active using source %s, device %s, buffer %d samples",
                config.sourceName,
                config.deviceLabel,
                bufferSamples));

        audioRecord = newRecord;
        streamingToHost = streamToHost;
        currentStatus = config.statusMessage;
        currentLevel = 0.0;
        signalDetected = false;
        running = true;

        if (streamToHost) {
            MoonBridge.startMicrophoneStreaming();
        }

        captureThread = new Thread(() -> runCaptureLoop(bufferSamples), streamToHost ? "MicStreamCapture" : "MicPreviewCapture");
        captureThread.start();
        dispatchStatus(config.statusMessage, 0.0, false);
        return true;
    }

    private void runCaptureLoop(int bufferSamples) {
        short[] readBuffer = new short[bufferSamples];
        int pendingPeak = 0;
        long lastUpdateTime = SystemClock.elapsedRealtime();

        while (running && audioRecord != null) {
            int samplesRead;

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                samplesRead = audioRecord.read(readBuffer, 0, readBuffer.length, AudioRecord.READ_BLOCKING);
            }
            else {
                samplesRead = audioRecord.read(readBuffer, 0, readBuffer.length);
            }

            if (samplesRead <= 0) {
                continue;
            }

            int peak = calculatePeak(readBuffer, samplesRead);
            if (peak > pendingPeak) {
                pendingPeak = peak;
            }

            if (streamingToHost) {
                int queued = MoonBridge.queueMicrophonePcm(readBuffer, samplesRead);
                if (queued < 0) {
                    LimeLog.warning("Failed to queue microphone PCM data for native encoding");
                }
            }

            long now = SystemClock.elapsedRealtime();
            if (now - lastUpdateTime >= LEVEL_UPDATE_INTERVAL_MS) {
                double instantaneousLevel = pendingPeak / 32767.0;
                double nextLevel = Math.max(instantaneousLevel, currentLevel * 0.72);
                boolean nextSignalDetected = pendingPeak >= SIGNAL_THRESHOLD;
                pendingPeak = 0;
                lastUpdateTime = now;
                currentLevel = nextLevel;
                signalDetected = nextSignalDetected;
                dispatchStatus(currentStatus, nextLevel, nextSignalDetected);
            }
        }
    }

    private CaptureConfig createCaptureConfig(int preferredDeviceId) {
        int[] preferredSources = new int[] {
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.N ? MediaRecorder.AudioSource.UNPROCESSED : -1,
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                MediaRecorder.AudioSource.MIC
        };
        int minBufferSizeBytes = AudioRecord.getMinBufferSize(SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT);
        if (minBufferSizeBytes <= 0) {
            minBufferSizeBytes = FRAME_SIZE * 4 * 2;
        }
        int bufferSizeBytes = Math.max(minBufferSizeBytes, FRAME_SIZE * 4 * 2);
        int bufferSamples = Math.max(FRAME_SIZE, bufferSizeBytes / 2);
        boolean missingSelectedDevice = false;
        AudioDeviceInfo preferredDevice = null;
        String preferredDeviceLabel = string(R.string.microphone_device_default);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && preferredDeviceId != 0) {
            preferredDevice = findInputDevice(preferredDeviceId);
            if (preferredDevice != null) {
                preferredDeviceLabel = describeDevice(preferredDevice);
            }
            else {
                missingSelectedDevice = true;
            }
        }

        for (int source : preferredSources) {
            AudioRecord candidate;

            if (source < 0) {
                continue;
            }

            candidate = buildAudioRecord(source, bufferSizeBytes);
            if (candidate == null) {
                continue;
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && preferredDevice != null) {
                boolean preferredApplied = candidate.setPreferredDevice(preferredDevice);
                if (!preferredApplied) {
                    LimeLog.info("Preferred microphone device selection was rejected by AudioRecord");
                }
            }

            if (candidate.getState() != AudioRecord.STATE_INITIALIZED) {
                candidate.release();
                continue;
            }

            CaptureConfig config = new CaptureConfig();
            config.record = candidate;
            config.bufferSamples = bufferSamples;
            config.sourceName = audioSourceToString(source);
            config.deviceLabel = preferredDevice != null ? preferredDeviceLabel : string(R.string.microphone_device_default);
            config.statusMessage = missingSelectedDevice ?
                    string(R.string.microphone_preview_selected_missing) :
                    (preferredDevice != null ?
                            string(R.string.microphone_preview_selected_active) :
                            string(R.string.microphone_preview_default_active));
            return config;
        }

        return null;
    }

    private AudioRecord buildAudioRecord(int audioSource, int bufferSizeBytes) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            return new AudioRecord.Builder()
                    .setAudioSource(audioSource)
                    .setAudioFormat(new AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(SAMPLE_RATE)
                            .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                            .build())
                    .setBufferSizeInBytes(bufferSizeBytes)
                    .build();
        }

        return new AudioRecord(audioSource,
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufferSizeBytes);
    }

    private AudioDeviceInfo findInputDevice(int deviceId) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            return null;
        }

        AudioManager audioManager = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
        if (audioManager == null) {
            return null;
        }

        for (AudioDeviceInfo deviceInfo : audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS)) {
            if (deviceInfo.getId() == deviceId) {
                return deviceInfo;
            }
        }

        return null;
    }

    private void dispatchStatus(String status, double level, boolean detected) {
        currentStatus = status;
        mainHandler.post(() -> {
            if (levelListener != null) {
                levelListener.onLevelUpdate(level, detected, status);
            }
        });
    }

    private String string(int resId) {
        return context.getString(resId);
    }

    private static int calculatePeak(short[] samples, int sampleCount) {
        int peak = 0;

        for (int i = 0; i < sampleCount; i++) {
            int sample = Math.abs(samples[i]);
            if (sample > peak) {
                peak = sample;
            }
        }

        return peak;
    }

    private static String audioSourceToString(int audioSource) {
        if (audioSource == MediaRecorder.AudioSource.VOICE_RECOGNITION) {
            return "VOICE_RECOGNITION";
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N &&
                audioSource == MediaRecorder.AudioSource.UNPROCESSED) {
            return "UNPROCESSED";
        }
        return "MIC";
    }

    private static String describeDevice(AudioDeviceInfo deviceInfo) {
        CharSequence productName = deviceInfo.getProductName();
        if (productName != null && productName.length() > 0) {
            return productName.toString();
        }

        switch (deviceInfo.getType()) {
            case AudioDeviceInfo.TYPE_BUILTIN_MIC:
                return "Built-in microphone";
            case AudioDeviceInfo.TYPE_BLUETOOTH_SCO:
                return "Bluetooth headset microphone";
            case AudioDeviceInfo.TYPE_BLUETOOTH_A2DP:
                return "Bluetooth audio input";
            case AudioDeviceInfo.TYPE_WIRED_HEADSET:
                return "Wired headset microphone";
            case AudioDeviceInfo.TYPE_USB_DEVICE:
            case AudioDeviceInfo.TYPE_USB_HEADSET:
                return "USB microphone";
            default:
                return "Input device " + deviceInfo.getId();
        }
    }

    private static final class CaptureConfig {
        AudioRecord record;
        int bufferSamples;
        String sourceName;
        String deviceLabel;
        String statusMessage;
    }
}
