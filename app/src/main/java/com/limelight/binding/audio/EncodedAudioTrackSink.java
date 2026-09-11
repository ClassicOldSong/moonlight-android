package com.limelight.binding.audio;

import android.media.AudioAttributes;
import android.media.AudioDeviceInfo;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.os.Build;

import java.nio.ByteBuffer;

final class EncodedAudioTrackSink {
    private final EncodedAudioFormat outputFormat;
    private final int bufferBytes;
    private AudioTrack track;
    private final byte[] legacyPacket = new byte[NativeAc3Encoder.OUTPUT_CAPACITY];

    EncodedAudioTrackSink(EncodedAudioFormat outputFormat) {
        this.outputFormat = outputFormat;
        bufferBytes = outputFormat.frameBytes * 8;
    }

    static AudioAttributes buildAttributes() {
        return new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
                .build();
    }

    static AudioFormat buildFormat(EncodedAudioFormat outputFormat) {
        return new AudioFormat.Builder()
                .setEncoding(outputFormat.androidEncoding)
                .setSampleRate(48_000)
                .setChannelMask(AudioFormat.CHANNEL_OUT_5POINT1)
                .build();
    }

    @SuppressWarnings("deprecation")
    boolean createAndValidate() {
        release();
        AudioAttributes attributes = buildAttributes();
        AudioFormat format = buildFormat(outputFormat);
        try {
            if (Build.VERSION.SDK_INT >= 23) {
                track = new AudioTrack.Builder()
                        .setAudioAttributes(attributes)
                        .setAudioFormat(format)
                        .setTransferMode(AudioTrack.MODE_STREAM)
                        .setBufferSizeInBytes(bufferBytes)
                        .build();
            } else {
                track = new AudioTrack(attributes, format, bufferBytes,
                        AudioTrack.MODE_STREAM, AudioManager.AUDIO_SESSION_ID_GENERATE);
            }
            return track.getState() == AudioTrack.STATE_INITIALIZED;
        } catch (RuntimeException error) {
            release();
            return false;
        }
    }

    boolean start() {
        if (track == null) {
            return false;
        }
        try {
            track.play();
            return track.getPlayState() == AudioTrack.PLAYSTATE_PLAYING;
        } catch (RuntimeException error) {
            return false;
        }
    }

    int writeCompleteAccessUnit(ByteBuffer packet, int encodedBytes) {
        if (track == null || encodedBytes <= 0 || encodedBytes > packet.capacity()) {
            return AudioTrack.ERROR_BAD_VALUE;
        }
        packet.position(0);
        packet.limit(encodedBytes);
        int written = 0;
        if (Build.VERSION.SDK_INT >= 23) {
            while (written < encodedBytes) {
                int result = track.write(packet, encodedBytes - written, AudioTrack.WRITE_BLOCKING);
                if (result <= 0) {
                    return result;
                }
                written += result;
            }
        } else {
            packet.get(legacyPacket, 0, encodedBytes);
            while (written < encodedBytes) {
                int result = track.write(legacyPacket, written, encodedBytes - written);
                if (result <= 0) {
                    return result;
                }
                written += result;
            }
        }
        return written;
    }

    String describeRoute() {
        if (track == null || Build.VERSION.SDK_INT < 23) {
            return "route unavailable";
        }
        AudioDeviceInfo route = track.getRoutedDevice();
        return route == null ? "route=null" :
                "routeId=" + route.getId() + " type=" + route.getType() +
                        " product=" + route.getProductName();
    }

    AudioTrack getTrack() {
        return track;
    }

    void stopAndFlush() {
        if (track == null) {
            return;
        }
        try {
            track.pause();
            track.flush();
        } catch (RuntimeException ignored) {
        }
    }

    void release() {
        if (track != null) {
            stopAndFlush();
            track.release();
            track = null;
        }
    }
}
