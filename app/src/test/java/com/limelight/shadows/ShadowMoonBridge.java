package com.limelight.shadows;

import com.limelight.nvstream.NvConnectionListener;
import com.limelight.nvstream.av.audio.AudioRenderer;
import com.limelight.nvstream.av.video.VideoDecoderRenderer;

import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;

@Implements(value = com.limelight.nvstream.jni.MoonBridge.class, isInAndroidSdk = false)
public class ShadowMoonBridge {
    public static boolean lastStartConnectionEnableMicrophone;
    public static boolean microphoneStreamActive;
    public static boolean microphoneEncryptionEnabled;
    public static int setupMicrophoneEncoderResult;
    public static int lastQueuedMicrophoneSampleCount;
    public static boolean microphoneStreamingStarted;

    // Static initializer override to prevent System.loadLibrary
    @Implementation
    protected static void __staticInitializer__() {
        // no-op
    }

    // Provide minimal nested AudioConfiguration
    public static class AudioConfiguration {
        public final int channelCount;
        public final int channelMask;
        public AudioConfiguration(int c, int m) {
            this.channelCount = c; this.channelMask = m;
        }
        public int toInt() { return 0; }
    }

    // Define constants minimally needed by code under test
    public static final AudioConfiguration AUDIO_CONFIGURATION_STEREO = new AudioConfiguration(2, 0x3);
    public static final AudioConfiguration AUDIO_CONFIGURATION_51_SURROUND = new AudioConfiguration(6, 0x3F);
    public static final AudioConfiguration AUDIO_CONFIGURATION_71_SURROUND = new AudioConfiguration(8, 0x63F);

    public static final int DR_OK = 0;

    public static int CAPABILITY_SLICES_PER_FRAME(byte s) { return 0; }

    public static int getPendingAudioDuration() { return 0; }

    // stubbed methods used by code but not relevant to unit tests
    public static void reset() {
        lastStartConnectionEnableMicrophone = false;
        microphoneStreamActive = false;
        microphoneEncryptionEnabled = false;
        setupMicrophoneEncoderResult = 0;
        lastQueuedMicrophoneSampleCount = 0;
        microphoneStreamingStarted = false;
    }

    @Implementation
    protected static void init() {
    }

    @Implementation
    protected static void setupBridge(VideoDecoderRenderer videoRenderer,
                                      AudioRenderer audioRenderer,
                                      NvConnectionListener connectionListener) {
    }

    public static void cleanupBridge() {}

    @Implementation
    protected static int startConnection(String address, String appVersion, String gfeVersion,
                                         String rtspSessionUrl, int serverCodecModeSupport,
                                         int width, int height, int fps,
                                         int bitrate, int packetSize, int streamingRemotely,
                                         int audioConfiguration, int supportedVideoFormats,
                                         int clientRefreshRateX100,
                                         byte[] riAesKey, byte[] riAesIv,
                                         int videoCapabilities,
                                         int colorSpace, int colorRange,
                                         boolean enableMicrophone) {
        lastStartConnectionEnableMicrophone = enableMicrophone;
        return 0;
    }

    @Implementation
    protected static void stopConnection() {
    }

    @Implementation
    protected static void interruptConnection() {
    }

    @Implementation
    protected static boolean isMicrophoneStreamActive() {
        return microphoneStreamActive;
    }

    @Implementation
    protected static boolean isMicrophoneEncryptionEnabled() {
        return microphoneEncryptionEnabled;
    }

    @Implementation
    protected static int setupMicrophoneEncoder(int sampleRate, int channelCount, int bitrate) {
        return setupMicrophoneEncoderResult;
    }

    @Implementation
    protected static void startMicrophoneStreaming() {
        microphoneStreamingStarted = true;
    }

    @Implementation
    protected static void stopMicrophoneStreaming() {
        microphoneStreamingStarted = false;
    }

    @Implementation
    protected static void cleanupMicrophoneEncoder() {
    }

    @Implementation
    protected static int queueMicrophonePcm(short[] pcmData, int sampleCount) {
        lastQueuedMicrophoneSampleCount = sampleCount;
        return sampleCount;
    }
}
