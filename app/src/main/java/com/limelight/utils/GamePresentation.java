package com.limelight.utils;

import android.app.Presentation;
import android.content.Context;
import android.graphics.PixelFormat;
import android.os.Bundle;
import android.util.Log;
import android.view.Display;
import android.view.Surface;
import android.view.WindowManager;
import android.widget.FrameLayout;

import com.limelight.preferences.PreferenceConfiguration;
import com.limelight.ui.StreamContainer;

public class GamePresentation extends Presentation implements StreamContainer.SurfaceAvailableCallback {

    private static final String TAG = "MoonlightExtDisplay";

    public interface OnSurfaceAvailableListener {
        void onSurfaceAvailable(Surface surface);
        void onSurfaceDestroyed();
    }

    private final OnSurfaceAvailableListener listener;
    private final PreferenceConfiguration prefConfig;
    private StreamContainer streamContainer;
    private Surface currentSurface;
    private boolean surfaceReady = false;

    public GamePresentation(Context outerContext, Display display, OnSurfaceAvailableListener listener,
                           PreferenceConfiguration prefConfig) {
        super(outerContext, display);
        this.listener = listener;
        this.prefConfig = prefConfig;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        FrameLayout root = new FrameLayout(getContext());
        root.setLayoutParams(new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));

        streamContainer = new StreamContainer(getContext(), null);
        streamContainer.setLayoutParams(new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));
        streamContainer.setSurfaceAvailableCallback(this);
        streamContainer.initAsPresentation(prefConfig);

        root.addView(streamContainer);
        setContentView(root);

        try {
            getWindow().setFormat(PixelFormat.OPAQUE);
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        } catch (Throwable ignored) {}

        Log.i(TAG, "GamePresentation created on display " + getDisplay());
    }

    public boolean showSafely() {
        try {
            super.show();
            return true;
        } catch (WindowManager.InvalidDisplayException e) {
            Log.w(TAG, "Cannot show GamePresentation on display " + getDisplay() + ": " + e);
        } catch (RuntimeException e) {
            Log.w(TAG, "Cannot show GamePresentation: " + e);
        }
        return false;
    }

    @Override
    public void onSurfaceAvailable(Surface surface) {
        if (surface == null || !surface.isValid()) return;
        currentSurface = surface;
        if (!surfaceReady) {
            surfaceReady = true;
            Log.i(TAG, "GamePresentation surface ready");
            if (listener != null) {
                listener.onSurfaceAvailable(currentSurface);
            }
        }
    }

    @Override
    public void onSurfaceDestroyed() {
        Log.i(TAG, "GamePresentation surface destroyed");
        surfaceReady = false;
        currentSurface = null;
        if (listener != null) {
            listener.onSurfaceDestroyed();
        }
    }

    @Override
    protected void onStop() {
        super.onStop();
        if (streamContainer != null) {
            streamContainer.onDestroy();
        }
    }
}
