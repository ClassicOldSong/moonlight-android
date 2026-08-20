package com.limelight.utils;

import android.app.Activity;

import com.limelight.R;
import com.limelight.ui.AppDialog;

import java.util.ArrayList;

/** Non-cancelable application-owned message overlay with help support. */
public class Dialog implements Runnable {
    private final String title;
    private final String message;
    private final Activity activity;
    private final Runnable runOnDismiss;
    private AppDialog alert;

    private static final ArrayList<Dialog> rundownDialogs = new ArrayList<>();

    private Dialog(Activity activity, String title, String message, Runnable runOnDismiss) {
        this.activity = activity;
        this.title = title;
        this.message = message;
        this.runOnDismiss = runOnDismiss;
    }

    public static void closeDialogs() {
        synchronized (rundownDialogs) {
            for (Dialog dialog : rundownDialogs) {
                if (dialog.alert != null && dialog.alert.isShowing()) {
                    dialog.alert.dismiss();
                }
            }
            rundownDialogs.clear();
        }
    }

    public static void displayDialog(final Activity activity, String title, String message,
                                     final boolean endAfterDismiss) {
        activity.runOnUiThread(new Dialog(activity, title, message, () -> {
            if (endAfterDismiss) {
                activity.finish();
            }
        }));
    }

    public static void displayDialog(Activity activity, String title, String message,
                                     Runnable runOnDismiss) {
        activity.runOnUiThread(new Dialog(activity, title, message, runOnDismiss));
    }

    @Override
    public void run() {
        if (activity.isFinishing()) {
            return;
        }

        alert = AppDialog.builder(activity)
                .setTitle(title)
                .setMessage(message)
                .setCancelable(false)
                .setNeutralButton(activity.getText(R.string.help), dialog -> {
                    removeAndRunCallback();
                    HelpLauncher.launchTroubleshooting(activity);
                    return true;
                })
                .setPositiveButton(android.R.string.ok, dialog -> {
                    removeAndRunCallback();
                    return true;
                })
                .show();
        synchronized (rundownDialogs) {
            rundownDialogs.add(this);
        }
    }

    private void removeAndRunCallback() {
        synchronized (rundownDialogs) {
            rundownDialogs.remove(this);
        }
        runOnDismiss.run();
    }
}
