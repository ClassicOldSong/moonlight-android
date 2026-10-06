package com.limelight.binding.audio;

import android.content.Context;
import android.content.Intent;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTimestamp;
import android.media.AudioTrack;
import android.media.audiofx.AudioEffect;
import android.os.Build;
import android.os.SystemClock;

import com.limelight.LimeLog;
import com.limelight.binding.audio.PassthroughEncoder.Codec;
import com.limelight.nvstream.av.audio.AudioRenderer;
import com.limelight.nvstream.jni.MoonBridge;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

public class AndroidAudioRenderer implements AudioRenderer {

    // How often to log measured output latency
    private static final long LATENCY_LOG_INTERVAL_MS = 5000;

    private final Context context;
    private final boolean enableAudioFx;
    private final Codec passthroughCodec;
    private final int bufferFrames;
    private AudioTrack track;
    private int sampleRate;
    private int channelCount;
    private int trackBufferBytes;

    // Parameters of the current track, so it can be recreated if the output dies
    // (e.g. HDMI/eARC renegotiation makes writes fail with ERROR_DEAD_OBJECT)
    private AudioFormat passthroughFormat;
    private AudioAttributes passthroughAttributes;
    private boolean passthroughIec61937;
    private int pcmChannelConfig;
    private boolean pcmLowLatency;
    private long lastRecreateTime;
    private long framesWrittenBase;

    // Non-null when we're encoding to a bitstream format for passthrough
    private PassthroughEncoder encoder;
    private boolean loggedWriteError;

    // Latency statistics
    private final AudioTimestamp timestamp = new AudioTimestamp();
    private long pcmFramesWritten;
    private long lastLatencyLogTime;
    private double encoderBufferedSum;
    private int encoderBufferedCount;
    private Method getLatencyMethod;
    private int lastUnderrunCount;

    public AndroidAudioRenderer(Context context, boolean enableAudioFx) {
        this(context, enableAudioFx, null, 0);
    }

    /**
     * @param passthroughCodec bitstream format to encode multichannel audio to, or null for PCM
     * @param bufferFrames passthrough AudioTrack buffer size in codec frames,
     *                     or 0 to use the platform minimum (at least 2 frames)
     */
    public AndroidAudioRenderer(Context context, boolean enableAudioFx,
                                Codec passthroughCodec, int bufferFrames) {
        this.context = context;
        this.enableAudioFx = enableAudioFx;
        this.passthroughCodec = passthroughCodec;
        this.bufferFrames = bufferFrames;
    }

    private AudioTrack createAudioTrack(int channelConfig, int sampleRate, int bufferSize, boolean lowLatency) {
        AudioAttributes.Builder attributesBuilder = new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_GAME);
        AudioFormat format = new AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(sampleRate)
                .setChannelMask(channelConfig)
                .build();

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            // Use FLAG_LOW_LATENCY on L through N
            if (lowLatency) {
                attributesBuilder.setFlags(AudioAttributes.FLAG_LOW_LATENCY);
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            AudioTrack.Builder trackBuilder = new AudioTrack.Builder()
                    .setAudioFormat(format)
                    .setAudioAttributes(attributesBuilder.build())
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .setBufferSizeInBytes(bufferSize);

            // Use PERFORMANCE_MODE_LOW_LATENCY on O and later
            if (lowLatency) {
                trackBuilder.setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY);
            }

            return trackBuilder.build();
        }
        else {
            return new AudioTrack(attributesBuilder.build(),
                    format,
                    bufferSize,
                    AudioTrack.MODE_STREAM,
                    AudioManager.AUDIO_SESSION_ID_GENERATE);
        }
    }

    private static AudioTrack createPassthroughAudioTrack(AudioFormat format, AudioAttributes attributes, int bufferSize) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            // No low latency performance mode here: it's only available for PCM
            return new AudioTrack.Builder()
                    .setAudioFormat(format)
                    .setAudioAttributes(attributes)
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .setBufferSizeInBytes(bufferSize)
                    .build();
        }
        else {
            return new AudioTrack(attributes,
                    format,
                    bufferSize,
                    AudioTrack.MODE_STREAM,
                    AudioManager.AUDIO_SESSION_ID_GENERATE);
        }
    }

    // AudioFormat encoding for a codec, or -1 if this Android version can't pass it through
    private static int getEncoding(Codec codec) {
        switch (codec) {
            case AC3:
                return AudioFormat.ENCODING_AC3;
            case DTS:
                return AudioFormat.ENCODING_DTS;
            case TRUEHD:
                return Build.VERSION.SDK_INT >= Build.VERSION_CODES.N_MR1 ?
                        AudioFormat.ENCODING_DOLBY_TRUEHD : -1;
            default:
                return -1;
        }
    }

    // Bytes in one write unit, used to size the AudioTrack buffer
    private static int getWriteUnitBytes(Codec codec) {
        switch (codec) {
            case AC3:
                // 640 kbps, 1536 samples
                return 2560;
            case DTS:
                // 1509.75 kbps, 512 samples
                return 2012;
            case TRUEHD:
            default:
                // Variable bitrate; 16 access units (640 samples) at ~6 Mbps peak
                return 10240;
        }
    }

    // The requested codec followed by the cheaper formats we fall back to
    private static List<Codec> getFallbackChain(Codec requested) {
        List<Codec> chain = new ArrayList<>();
        switch (requested) {
            case TRUEHD:
                chain.add(Codec.TRUEHD);
                // fall through
            case DTS:
                chain.add(Codec.DTS);
                // fall through
            case AC3:
                chain.add(Codec.AC3);
                break;
        }
        return chain;
    }

    // Samples per frame for the codecs we can wrap in IEC 61937 bursts
    private static int getIecFrameSamples(Codec codec) {
        return codec == Codec.AC3 ? 1536 : 512;
    }

    // Tries to set up a passthrough AudioTrack fed by one of our encoders.
    // Returns false (leaving no track or encoder behind) if that isn't possible,
    // in which case the caller falls back to PCM.
    private boolean setupPassthrough(MoonBridge.AudioConfiguration audioConfiguration, int sampleRate) {
        if (!PassthroughEncoder.isAvailable()) {
            LimeLog.warning("Passthrough: encoder library not included in this build");
            return false;
        }

        // Same attributes as video players use, which is what TV audio HALs expect
        // to route to a direct/passthrough HDMI output.
        AudioAttributes attributes = new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
                .build();

        for (Codec codec : getFallbackChain(passthroughCodec)) {
            if (!PassthroughEncoder.isSupported(codec)) {
                LimeLog.info("Passthrough: " + codec.displayName + " not available in this build");
                continue;
            }

            // Preferred: wrap frames in IEC 61937 bursts ourselves and write them
            // as 48 kHz 16-bit stereo. Every burst lasts exactly one frame, so the
            // framework paces the stream like PCM. With plain ENCODING_AC3/DTS some
            // HALs drain the bitstream faster than real time and underrun constantly.
            if (codec != Codec.TRUEHD && Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                if (tryPassthroughTrack(audioConfiguration, sampleRate, attributes, codec, true,
                        AudioFormat.ENCODING_IEC61937, AudioFormat.CHANNEL_OUT_STEREO,
                        getIecFrameSamples(codec) * 4)) {
                    return true;
                }
            }

            int encoding = getEncoding(codec);
            if (encoding < 0) {
                LimeLog.info("Passthrough: " + codec.displayName + " not supported on this Android version");
                continue;
            }

            // Some HALs only accept compressed formats declared as stereo
            int[] channelMasks = { AudioFormat.CHANNEL_OUT_5POINT1, AudioFormat.CHANNEL_OUT_STEREO };
            for (int channelMask : channelMasks) {
                if (tryPassthroughTrack(audioConfiguration, sampleRate, attributes, codec, false,
                        encoding, channelMask, getWriteUnitBytes(codec))) {
                    return true;
                }
            }
        }

        return false;
    }

    private boolean tryPassthroughTrack(MoonBridge.AudioConfiguration audioConfiguration, int sampleRate,
                                        AudioAttributes attributes, Codec codec, boolean iec61937,
                                        int encoding, int channelMask, int unitBytes) {
        String mode = codec.displayName + (iec61937 ? " (IEC 61937)" : "");
        AudioFormat format = new AudioFormat.Builder()
                .setEncoding(encoding)
                .setSampleRate(sampleRate)
                .setChannelMask(channelMask)
                .build();

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
                !AudioTrack.isDirectPlaybackSupported(format, attributes)) {
            LimeLog.info(String.format("Passthrough: %s direct playback not supported with channel mask 0x%X",
                    mode, channelMask));
            return false;
        }

        // AudioFlinger won't start a streaming track until its buffer is full,
        // so the buffer size is effectively our output latency. Try the requested
        // size first, then something larger.
        int minBufferSize = AudioTrack.getMinBufferSize(sampleRate, channelMask, encoding);
        int requested = bufferFrames > 0 ?
                unitBytes * bufferFrames : Math.max(minBufferSize, unitBytes * 2);
        if (iec61937) {
            // Whole bursts only
            requested = (requested + unitBytes - 1) / unitBytes * unitBytes;
        }
        int[] bufferSizes = { requested, Math.max(minBufferSize, requested + unitBytes * 2) };

        for (int bufferSize : bufferSizes) {
            try {
                track = createPassthroughAudioTrack(format, attributes, bufferSize);
                if (track.getState() != AudioTrack.STATE_INITIALIZED) {
                    throw new IllegalStateException("AudioTrack not initialized");
                }

                encoder = new PassthroughEncoder(codec, sampleRate, audioConfiguration.channelCount, 0, iec61937);
                track.play();
                trackBufferBytes = bufferSize;
                passthroughFormat = format;
                passthroughAttributes = attributes;
                passthroughIec61937 = iec61937;

                LimeLog.info(String.format("Passthrough enabled: %s, mask 0x%X, buffer %d bytes = %.1f frames (setting: %s, platform minimum %d bytes, capacity %s)",
                        mode, channelMask, bufferSize, (double) bufferSize / unitBytes,
                        bufferFrames > 0 ? bufferFrames + " frames" : "auto", minBufferSize,
                        Build.VERSION.SDK_INT >= Build.VERSION_CODES.N ?
                                String.valueOf(track.getBufferCapacityInFrames()) : "n/a"));
                if (codec != passthroughCodec) {
                    LimeLog.warning("Passthrough: fell back from " + passthroughCodec.displayName +
                            " to " + codec.displayName);
                }
                return true;
            } catch (Exception e) {
                LimeLog.warning(String.format("Passthrough: %s mask 0x%X buffer %d failed: %s",
                        mode, channelMask, bufferSize, e));
                releasePassthrough();
            }
        }
        return false;
    }

    private void releasePassthrough() {
        if (track != null) {
            try {
                track.release();
            } catch (Exception ignored) {}
            track = null;
        }
        if (encoder != null) {
            encoder.release();
            encoder = null;
        }
    }

    @Override
    public int setup(MoonBridge.AudioConfiguration audioConfiguration, int sampleRate, int samplesPerFrame) {
        int channelConfig;
        int bytesPerFrame;

        this.sampleRate = sampleRate;
        this.channelCount = audioConfiguration.channelCount;

        // Passthrough is only worth it for multichannel audio; stereo PCM works everywhere
        if (passthroughCodec != null && audioConfiguration.channelCount == 6) {
            if (setupPassthrough(audioConfiguration, sampleRate)) {
                return 0;
            }
            LimeLog.warning("Passthrough unavailable, falling back to PCM");
        }

        switch (audioConfiguration.channelCount)
        {
            case 2:
                channelConfig = AudioFormat.CHANNEL_OUT_STEREO;
                break;
            case 4:
                channelConfig = AudioFormat.CHANNEL_OUT_QUAD;
                break;
            case 6:
                channelConfig = AudioFormat.CHANNEL_OUT_5POINT1;
                break;
            case 8:
                // AudioFormat.CHANNEL_OUT_7POINT1_SURROUND isn't available until Android 6.0,
                // yet the CHANNEL_OUT_SIDE_LEFT and CHANNEL_OUT_SIDE_RIGHT constants were added
                // in 5.0, so just hardcode the constant so we can work on Lollipop.
                channelConfig = 0x000018fc; // AudioFormat.CHANNEL_OUT_7POINT1_SURROUND
                break;
            default:
                LimeLog.severe("Decoder returned unhandled channel count");
                return -1;
        }

        LimeLog.info("Audio channel config: "+String.format("0x%X", channelConfig));

        bytesPerFrame = audioConfiguration.channelCount * samplesPerFrame * 2;

        // We're not supposed to request less than the minimum
        // buffer size for our buffer, but it appears that we can
        // do this on many devices and it lowers audio latency.
        // We'll try the small buffer size first and if it fails,
        // use the recommended larger buffer size.

        for (int i = 0; i < 4; i++) {
            boolean lowLatency;
            int bufferSize;

            // We will try:
            // 1) Small buffer, low latency mode
            // 2) Large buffer, low latency mode
            // 3) Small buffer, standard mode
            // 4) Large buffer, standard mode

            switch (i) {
                case 0:
                case 1:
                    lowLatency = true;
                    break;
                case 2:
                case 3:
                    lowLatency = false;
                    break;
                default:
                    // Unreachable
                    throw new IllegalStateException();
            }

            switch (i) {
                case 0:
                case 2:
                    bufferSize = bytesPerFrame * 2;
                    break;

                case 1:
                case 3:
                    // Try the larger buffer size
                    bufferSize = Math.max(AudioTrack.getMinBufferSize(sampleRate,
                                    channelConfig,
                                    AudioFormat.ENCODING_PCM_16BIT),
                            bytesPerFrame * 2);

                    // Round to next frame
                    bufferSize = (((bufferSize + (bytesPerFrame - 1)) / bytesPerFrame) * bytesPerFrame);
                    break;
                default:
                    // Unreachable
                    throw new IllegalStateException();
            }

            // Skip low latency options if hardware sample rate doesn't match the content
            if (AudioTrack.getNativeOutputSampleRate(AudioManager.STREAM_MUSIC) != sampleRate && lowLatency) {
                continue;
            }

            // Skip low latency options when using audio effects, since low latency mode
            // precludes the use of the audio effect pipeline (as of Android 13).
            if (enableAudioFx && lowLatency) {
                continue;
            }

            try {
                track = createAudioTrack(channelConfig, sampleRate, bufferSize, lowLatency);
                track.play();
                trackBufferBytes = bufferSize;
                pcmChannelConfig = channelConfig;
                pcmLowLatency = lowLatency;

                // Successfully created working AudioTrack. We're done here.
                LimeLog.info("Audio track configuration: "+bufferSize+" "+lowLatency);
                break;
            } catch (Exception e) {
                // Try to release the AudioTrack if we got far enough
                e.printStackTrace();
                try {
                    if (track != null) {
                        track.release();
                        track = null;
                    }
                } catch (Exception ignored) {}
            }
        }

        if (track == null) {
            // Couldn't create any audio track for playback
            return -2;
        }

        return 0;
    }

    @Override
    public void playDecodedAudio(short[] audioData) {
        // Only queue up to 40 ms of pending audio data in addition to what AudioTrack is buffering for us.
        if (MoonBridge.getPendingAudioDuration() < 40) {
            if (encoder != null) {
                // Accumulates PCM and returns complete frames
                int len = encoder.encode(audioData);
                if (len > 0) {
                    // This blocks until the frames fit in the AudioTrack buffer
                    int ret = track.write(encoder.getOutput(), 0, len);
                    if (ret == AudioTrack.ERROR_DEAD_OBJECT) {
                        recreateTrack();
                    }
                    else if (ret < 0 && !loggedWriteError) {
                        LimeLog.warning("Passthrough AudioTrack write failed: " + ret);
                        loggedWriteError = true;
                    }
                }
                else if (len < 0 && !loggedWriteError) {
                    LimeLog.warning("Passthrough encode failed: " + len);
                    loggedWriteError = true;
                }

                encoderBufferedSum += encoder.getBufferedSamples();
                encoderBufferedCount++;
            }
            else {
                // This will block until the write is completed. That can cause a backlog
                // of pending audio data, so we do the above check to be able to bound
                // latency at 40 ms in that situation.
                int ret = track.write(audioData, 0, audioData.length);
                if (ret > 0) {
                    pcmFramesWritten += ret / channelCount;
                }
                else if (ret == AudioTrack.ERROR_DEAD_OBJECT) {
                    recreateTrack();
                }
            }

            logLatencyIfDue();
        }
        else {
            LimeLog.info("Too much pending audio data: " + MoonBridge.getPendingAudioDuration() +" ms");
        }
    }

    // The audio output went away (HDMI/eARC renegotiation, soundbar reconnect...).
    // Build an identical track so audio comes back instead of staying silent.
    private void recreateTrack() {
        long now = SystemClock.elapsedRealtime();
        if (now - lastRecreateTime < 1000) {
            return;
        }
        lastRecreateTime = now;

        LimeLog.warning("Audio output died (ERROR_DEAD_OBJECT), recreating AudioTrack");
        try {
            track.release();
        } catch (Exception ignored) {}

        try {
            if (encoder != null) {
                track = createPassthroughAudioTrack(passthroughFormat, passthroughAttributes, trackBufferBytes);
                framesWrittenBase = encoder.getSamplesOutput();
            }
            else {
                track = createAudioTrack(pcmChannelConfig, sampleRate, trackBufferBytes, pcmLowLatency);
                pcmFramesWritten = 0;
            }
            track.play();
            lastUnderrunCount = 0;
        } catch (Exception e) {
            LimeLog.warning("Failed to recreate AudioTrack: " + e);
        }
    }

    // AudioTrack.getLatency() is hidden but widely implemented; it reports the
    // framework + HAL latency in ms. Returns -1 if unavailable.
    private int getFrameworkLatency() {
        try {
            if (getLatencyMethod == null) {
                getLatencyMethod = AudioTrack.class.getMethod("getLatency");
            }
            return (Integer) getLatencyMethod.invoke(track);
        } catch (Exception e) {
            return -1;
        }
    }

    private void logLatencyIfDue() {
        long now = SystemClock.elapsedRealtime();
        if (lastLatencyLogTime == 0) {
            lastLatencyLogTime = now;
            return;
        }
        if (now - lastLatencyLogTime < LATENCY_LOG_INTERVAL_MS) {
            return;
        }
        lastLatencyLogTime = now;

        StringBuilder sb = new StringBuilder("Audio latency [");
        long framesWritten;
        if (encoder != null) {
            sb.append(encoder.getCodec().displayName);
            if (passthroughIec61937) {
                sb.append(" IEC61937");
            }
            framesWritten = encoder.getSamplesOutput() - framesWrittenBase;
        }
        else {
            sb.append("PCM");
            framesWritten = pcmFramesWritten;
        }
        sb.append(' ').append(channelCount).append("ch, buffer ").append(trackBufferBytes).append(" B]: ");

        // Output latency: what we've written minus what the HAL reports as presented,
        // projected to now from the timestamp's capture time
        if (!track.getTimestamp(timestamp)) {
            sb.append("output n/a (no timestamp)");
        }
        else if (System.nanoTime() - timestamp.nanoTime > 1000000000L) {
            // Playback stalled (stream paused, output reconfigured...): the
            // projection would be meaningless
            sb.append("output n/a (stale timestamp)");
        }
        else {
            double presented = timestamp.framePosition +
                    (System.nanoTime() - timestamp.nanoTime) * sampleRate / 1e9;
            double outputMs = (framesWritten - presented) * 1000.0 / sampleRate;
            sb.append(String.format("output %.1f ms (written %d, presented %d)",
                    outputMs, framesWritten, timestamp.framePosition));
        }

        int frameworkLatency = getFrameworkLatency();
        if (frameworkLatency >= 0) {
            sb.append(", AudioTrack latency ").append(frameworkLatency).append(" ms");
            if (encoder != null && !passthroughIec61937) {
                // For compressed tracks the framework counts each buffer byte as a
                // frame, so subtract that to estimate the HAL's own latency
                sb.append(String.format(" (~HAL %.0f ms)",
                        frameworkLatency - trackBufferBytes * 1000.0 / sampleRate));
            }
        }

        if (encoder != null && encoderBufferedCount > 0) {
            double bufferedMs = encoderBufferedSum / encoderBufferedCount * 1000.0 / sampleRate;
            double codecDelayMs = encoder.getCodecDelay() * 1000.0 / sampleRate;
            sb.append(String.format(", encoder wait avg %.1f ms + codec delay %.1f ms", bufferedMs, codecDelayMs));
            encoderBufferedSum = 0;
            encoderBufferedCount = 0;
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            int underruns = track.getUnderrunCount();
            sb.append(", underruns ").append(underruns - lastUnderrunCount)
                    .append(" in last ").append(LATENCY_LOG_INTERVAL_MS / 1000).append(" s (total ")
                    .append(underruns).append(')');
            lastUnderrunCount = underruns;
        }

        LimeLog.info(sb.toString());
    }

    @Override
    public void start() {
        // Audio effects can't process a compressed bitstream
        if (enableAudioFx && encoder == null) {
            // Open an audio effect control session to allow equalizers to apply audio effects
            Intent i = new Intent(AudioEffect.ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION);
            i.putExtra(AudioEffect.EXTRA_AUDIO_SESSION, track.getAudioSessionId());
            i.putExtra(AudioEffect.EXTRA_PACKAGE_NAME, context.getPackageName());
            i.putExtra(AudioEffect.EXTRA_CONTENT_TYPE, AudioEffect.CONTENT_TYPE_GAME);
            context.sendBroadcast(i);
        }
    }

    @Override
    public void stop() {
        if (enableAudioFx && encoder == null) {
            // Close our audio effect control session when we're stopping
            Intent i = new Intent(AudioEffect.ACTION_CLOSE_AUDIO_EFFECT_CONTROL_SESSION);
            i.putExtra(AudioEffect.EXTRA_AUDIO_SESSION, track.getAudioSessionId());
            i.putExtra(AudioEffect.EXTRA_PACKAGE_NAME, context.getPackageName());
            context.sendBroadcast(i);
        }
    }

    @Override
    public void cleanup() {
        // Immediately drop all pending data
        try {
            track.pause();
            track.flush();
        } catch (IllegalStateException e) {
            // Track was already released (failed recreation after the output died)
        }
        track.release();

        if (encoder != null) {
            encoder.release();
            encoder = null;
        }
    }
}
