package com.limelight.binding.audio;

import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.media.AudioAttributes;
import android.media.AudioDeviceInfo;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.os.Build;

import androidx.annotation.RequiresApi;

import java.util.Arrays;

final class AndroidAudioCapabilities {
    static final class Result {
        final boolean reportedSupported;
        final String evidence;

        Result(boolean reportedSupported, String evidence) {
            this.reportedSupported = reportedSupported;
            this.evidence = evidence;
        }
    }

    private final Context context;
    private final AudioManager audioManager;

    AndroidAudioCapabilities(Context context) {
        this.context = context.getApplicationContext();
        audioManager = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
    }

    Result probeEncodedRoute(AudioFormat format, AudioAttributes attributes, int encoding) {
        boolean supported;
        StringBuilder evidence = new StringBuilder("sdk=").append(Build.VERSION.SDK_INT);

        if (Build.VERSION.SDK_INT >= 33) {
            int directSupport = audioManager.getDirectPlaybackSupport(format, attributes);
            supported = directSupport != AudioManager.DIRECT_PLAYBACK_NOT_SUPPORTED;
            evidence.append(" directSupport=").append(directSupport);
        } else if (Build.VERSION.SDK_INT >= 29) {
            supported = AudioTrack.isDirectPlaybackSupported(format, attributes);
            evidence.append(" directSupported=").append(supported);
        } else if (Build.VERSION.SDK_INT >= 23) {
            supported = inspectOutputDevices(evidence, encoding);
        } else {
            Intent sticky = context.registerReceiver(null,
                    new IntentFilter(AudioManager.ACTION_HDMI_AUDIO_PLUG));
            int state = sticky == null ? 0 :
                    sticky.getIntExtra(AudioManager.EXTRA_AUDIO_PLUG_STATE, 0);
            int[] encodings = sticky == null ? null :
                    sticky.getIntArrayExtra(AudioManager.EXTRA_ENCODINGS);
            supported = state == 1 && containsEncoding(encodings, encoding);
            evidence.append(" hdmiState=").append(state)
                    .append(" encodings=").append(Arrays.toString(encodings));
        }

        if (Build.VERSION.SDK_INT >= 29) {
            inspectOutputDevices(evidence, encoding);
        }
        return new Result(supported, evidence.toString());
    }

    @RequiresApi(api = Build.VERSION_CODES.M)
    private boolean inspectOutputDevices(StringBuilder evidence, int encoding) {
        boolean supported = false;
        AudioDeviceInfo[] devices = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS);
        evidence.append(" outputs=").append(devices.length);
        for (AudioDeviceInfo device : devices) {
            int type = device.getType();
            boolean compressedRoute = type == AudioDeviceInfo.TYPE_HDMI ||
                    type == AudioDeviceInfo.TYPE_HDMI_ARC ||
                    (Build.VERSION.SDK_INT >= 31 && type == AudioDeviceInfo.TYPE_HDMI_EARC);
            int[] encodings = device.getEncodings();
            evidence.append(" [id=").append(device.getId())
                    .append(" type=").append(type)
                    .append(" product=").append(device.getProductName())
                    .append(" enc=").append(Arrays.toString(encodings)).append(']');
            if (compressedRoute && containsEncoding(encodings, encoding)) {
                supported = true;
            }
        }
        return supported;
    }

    private static boolean containsEncoding(int[] encodings, int target) {
        if (encodings == null) {
            return false;
        }
        for (int encoding : encodings) {
            if (encoding == target) {
                return true;
            }
        }
        return false;
    }
}
