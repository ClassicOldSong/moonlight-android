package com.limelight.ui;

import android.app.Activity;
import android.os.Build;

import androidx.annotation.RequiresApi;
import androidx.core.content.ContextCompat;
import androidx.window.layout.FoldingFeature;
import androidx.window.layout.WindowInfoTracker;
import androidx.window.layout.WindowInfoTrackerCallbackAdapter;
import androidx.window.layout.WindowLayoutInfo;

/**
 * Observes foldable display state via Jetpack WindowManager and notifies a listener
 * when the device enters or exits table-top / book posture.
 */
public class FoldStateManager {

    public enum FoldPosture {
        FLAT,
        HALF_OPEN,
        UNKNOWN
    }

    public enum Orientation {
        HORIZONTAL,
        VERTICAL,
        UNKNOWN
    }

    public static class FoldState {
        public final FoldPosture posture;
        public final Orientation orientation;
        public final boolean isSeparating;

        FoldState(FoldPosture posture, Orientation orientation, boolean isSeparating) {
            this.posture = posture;
            this.orientation = orientation;
            this.isSeparating = isSeparating;
        }

        /** True when the device is in book mode (hinge vertical, side-by-side panels). */
        public boolean isBookMode() {
            return posture == FoldPosture.HALF_OPEN && orientation == Orientation.VERTICAL;
        }

        /** True when the device is in table-top mode (hinge horizontal, top/bottom panels). */
        public boolean isTableTopMode() {
            return posture == FoldPosture.HALF_OPEN && orientation == Orientation.HORIZONTAL;
        }
    }

    public interface FoldStateListener {
        void onFoldStateChanged(FoldState state);
    }

    private final Activity activity;
    private FoldStateListener listener;

    public FoldStateManager(Activity activity) {
        this.activity = activity;
    }

    public void setListener(FoldStateListener listener) {
        this.listener = listener;
    }

    /**
     * Start observing fold state. Must be called after the activity reaches STARTED.
     * Observations stop automatically when the activity is stopped.
     * No-op on API < 24 (foldable hardware requires API 29+ in practice).
     */
    public void startObserving() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            startObservingImpl();
        }
    }

    @RequiresApi(Build.VERSION_CODES.N)
    private void startObservingImpl() {
        WindowInfoTrackerCallbackAdapter adapter =
                new WindowInfoTrackerCallbackAdapter(WindowInfoTracker.getOrCreate(activity));
        adapter.addWindowLayoutInfoListener(
                activity,
                ContextCompat.getMainExecutor(activity),
                this::onWindowLayoutInfo
        );
    }

    private void onWindowLayoutInfo(WindowLayoutInfo info) {
        if (listener != null) {
            listener.onFoldStateChanged(parseFoldState(info));
        }
    }

    private FoldState parseFoldState(WindowLayoutInfo info) {
        for (androidx.window.layout.DisplayFeature feature : info.getDisplayFeatures()) {
            if (feature instanceof FoldingFeature) {
                FoldingFeature fold = (FoldingFeature) feature;

                FoldPosture posture;
                if (fold.getState() == FoldingFeature.State.HALF_OPENED) {
                    posture = FoldPosture.HALF_OPEN;
                } else if (fold.getState() == FoldingFeature.State.FLAT) {
                    posture = FoldPosture.FLAT;
                } else {
                    posture = FoldPosture.UNKNOWN;
                }

                Orientation orientation;
                if (fold.getOrientation() == FoldingFeature.Orientation.VERTICAL) {
                    orientation = Orientation.VERTICAL;
                } else if (fold.getOrientation() == FoldingFeature.Orientation.HORIZONTAL) {
                    orientation = Orientation.HORIZONTAL;
                } else {
                    orientation = Orientation.UNKNOWN;
                }

                return new FoldState(posture, orientation, fold.isSeparating());
            }
        }
        return new FoldState(FoldPosture.FLAT, Orientation.UNKNOWN, false);
    }
}
