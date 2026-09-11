package com.limelight.binding.audio;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.media.AudioDeviceCallback;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import android.media.AudioRouting;
import android.media.AudioTrack;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;

final class AudioRouteMonitor {
    interface Listener {
        void onAudioRouteChanged();
    }

    private final Context context;
    private final AudioManager audioManager;
    private final Listener listener;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private AudioTrack monitoredTrack;
    private AudioRouting.OnRoutingChangedListener routingChangedListener;
    private AudioDeviceCallback deviceCallback;
    private BroadcastReceiver hdmiReceiver;
    private int routedDeviceId = -1;
    private boolean registered;

    AudioRouteMonitor(Context context, Listener listener) {
        this.context = context.getApplicationContext();
        this.listener = listener;
        audioManager = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
    }

    void register(AudioTrack track) {
        if (registered) {
            return;
        }
        registered = true;
        monitoredTrack = track;
        updateRoutedDevice(false);

        if (Build.VERSION.SDK_INT >= 24) {
            routingChangedListener = audioRouting -> updateRoutedDevice(true);
            track.addOnRoutingChangedListener(routingChangedListener, handler);
        } else if (Build.VERSION.SDK_INT == 23) {
            deviceCallback = new AudioDeviceCallback() {
                @Override
                public void onAudioDevicesAdded(AudioDeviceInfo[] addedDevices) {
                    updateRoutedDevice(true);
                }

                @Override
                public void onAudioDevicesRemoved(AudioDeviceInfo[] removedDevices) {
                    updateRoutedDevice(true);
                }
            };
            audioManager.registerAudioDeviceCallback(deviceCallback, handler);
        } else {
            hdmiReceiver = new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    if (!isInitialStickyBroadcast()) {
                        listener.onAudioRouteChanged();
                    }
                }
            };
            context.registerReceiver(hdmiReceiver,
                    new IntentFilter(AudioManager.ACTION_HDMI_AUDIO_PLUG));
        }
    }

    private void updateRoutedDevice(boolean notifyChange) {
        if (monitoredTrack == null || Build.VERSION.SDK_INT < 23) {
            return;
        }

        AudioDeviceInfo route = monitoredTrack.getRoutedDevice();
        int newRouteId = route == null ? -1 : route.getId();
        if (notifyChange && routedDeviceId != -1 && routedDeviceId != newRouteId) {
            listener.onAudioRouteChanged();
        }
        routedDeviceId = newRouteId;
    }

    void unregister() {
        if (!registered) {
            return;
        }
        registered = false;
        if (Build.VERSION.SDK_INT >= 24 && routingChangedListener != null &&
                monitoredTrack != null) {
            monitoredTrack.removeOnRoutingChangedListener(routingChangedListener);
            routingChangedListener = null;
        }
        if (Build.VERSION.SDK_INT == 23 && deviceCallback != null) {
            audioManager.unregisterAudioDeviceCallback(deviceCallback);
            deviceCallback = null;
        }
        if (hdmiReceiver != null) {
            try {
                context.unregisterReceiver(hdmiReceiver);
            } catch (IllegalArgumentException ignored) {
            }
            hdmiReceiver = null;
        }
        monitoredTrack = null;
        routedDeviceId = -1;
    }
}
