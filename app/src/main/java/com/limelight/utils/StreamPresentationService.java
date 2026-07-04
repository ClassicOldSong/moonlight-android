package com.limelight.utils;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.hardware.display.DisplayManager;
import android.os.Binder;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;
import android.view.Display;
import android.view.Surface;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

import com.limelight.preferences.PreferenceConfiguration;

public class StreamPresentationService extends Service implements GamePresentation.OnSurfaceAvailableListener {

    private static final String TAG = "MoonlightExtDisplay";
    private static final String CHANNEL_ID = "stream_presentation_channel";
    private static final int NOTIFICATION_ID = 2;

    public static final String EXTRA_DISPLAY_ID = "displayId";

    public interface PresentationStateListener {
        void onPresentationFailure(String reason);
        void onPresentationSurfaceDestroyed();
    }

    private final LocalBinder binder = new LocalBinder();
    private GamePresentation gamePresentation;
    private Surface availableSurface;
    private Runnable pendingSurfaceCallback;
    private PresentationStateListener presentationStateListener;
    private String presentationFailureReason;

    public class LocalBinder extends Binder {
        public StreamPresentationService getService() {
            return StreamPresentationService.this;
        }
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return binder;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
        Intent notifyIntent = getPackageManager().getLaunchIntentForPackage(getPackageName());
        PendingIntent pi = null;
        if (notifyIntent != null) {
            notifyIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
            pi = PendingIntent.getActivity(this, 0, notifyIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        }
        NotificationCompat.Builder builder = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("Artemis Streaming")
                .setContentText("Streaming to external display")
                .setSmallIcon(com.limelight.R.drawable.app_icon)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setOngoing(true);
        if (pi != null) builder.setContentIntent(pi);
        startForeground(NOTIFICATION_ID, builder.build());
        Log.i(TAG, "StreamPresentationService started (foreground)");
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && intent.hasExtra(EXTRA_DISPLAY_ID) && gamePresentation == null) {
            int displayId = intent.getIntExtra(EXTRA_DISPLAY_ID, Display.DEFAULT_DISPLAY);
            attachToDisplay(displayId);
        }
        return START_NOT_STICKY;
    }

    private void attachToDisplay(int displayId) {
        if (gamePresentation != null) {
            return;
        }
        DisplayManager dm = (DisplayManager) getSystemService(Context.DISPLAY_SERVICE);
        if (dm == null) {
            failPresentation("DisplayManager unavailable; cannot attach Presentation");
            return;
        }
        Display targetDisplay = dm.getDisplay(displayId);
        if (targetDisplay == null) {
            failPresentation("Display id=" + displayId + " not found");
            return;
        }
        Log.i(TAG, "Attaching GamePresentation to display id=" + displayId);
        PreferenceConfiguration prefConfig = PreferenceConfiguration.readPreferences(this);
        GamePresentation presentation = new GamePresentation(this, targetDisplay, this, prefConfig);
        if (!presentation.showSafely()) {
            failPresentation("Cannot show Presentation on display id=" + displayId);
            return;
        }
        gamePresentation = presentation;
    }

    @Override
    public void onSurfaceAvailable(Surface surface) {
        availableSurface = surface;
        Log.i(TAG, "Service: Presentation surface available");
        if (pendingSurfaceCallback != null) {
            Runnable cb = pendingSurfaceCallback;
            pendingSurfaceCallback = null;
            cb.run();
        }
    }

    @Override
    public void onSurfaceDestroyed() {
        availableSurface = null;
        pendingSurfaceCallback = null;
        Log.i(TAG, "Service: Presentation surface destroyed");
        if (presentationStateListener != null) {
            presentationStateListener.onPresentationSurfaceDestroyed();
        }
    }

    public Surface getSurface() {
        return availableSurface;
    }

    public void requestSurface(Runnable callback) {
        if (presentationFailureReason != null) {
            if (presentationStateListener != null) {
                presentationStateListener.onPresentationFailure(presentationFailureReason);
            }
            return;
        }
        if (availableSurface != null && availableSurface.isValid()) {
            callback.run();
        } else {
            pendingSurfaceCallback = callback;
        }
    }

    public void setPresentationStateListener(PresentationStateListener listener) {
        presentationStateListener = listener;
        if (presentationFailureReason != null && presentationStateListener != null) {
            presentationStateListener.onPresentationFailure(presentationFailureReason);
            stopSelf();
        }
    }

    private void failPresentation(String reason) {
        presentationFailureReason = reason;
        pendingSurfaceCallback = null;
        Log.w(TAG, reason);
        if (presentationStateListener != null) {
            presentationStateListener.onPresentationFailure(reason);
            stopSelf();
        }
    }

    @Override
    public void onDestroy() {
        if (gamePresentation != null) {
            try { gamePresentation.dismiss(); } catch (Throwable ignored) {}
            gamePresentation = null;
        }
        availableSurface = null;
        pendingSurfaceCallback = null;
        presentationStateListener = null;
        Log.i(TAG, "StreamPresentationService destroyed");
        super.onDestroy();
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) {
                NotificationChannel ch = new NotificationChannel(
                        CHANNEL_ID, "Stream Presentation", NotificationManager.IMPORTANCE_LOW);
                ch.setShowBadge(false);
                nm.createNotificationChannel(ch);
            }
        }
    }
}
