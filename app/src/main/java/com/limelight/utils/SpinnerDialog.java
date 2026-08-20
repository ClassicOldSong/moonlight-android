package com.limelight.utils;

import android.app.Activity;
import android.view.View;

import com.limelight.R;
import com.limelight.ui.AppDialog;

import java.util.ArrayList;
import java.util.Iterator;

/** Application-owned blocking progress overlay. */
public class SpinnerDialog implements Runnable {
    private final String title;
    private String message;
    private final Activity activity;
    private AppDialog progress;
    private final boolean finish;

    private static final ArrayList<SpinnerDialog> rundownDialogs = new ArrayList<>();

    private SpinnerDialog(Activity activity, String title, String message, boolean finish) {
        this.activity = activity;
        this.title = title;
        this.message = message;
        this.finish = finish;
    }

    public static SpinnerDialog displayDialog(Activity activity, String title, String message,
                                              boolean finish) {
        SpinnerDialog spinner = new SpinnerDialog(activity, title, message, finish);
        activity.runOnUiThread(spinner);
        return spinner;
    }

    public static void closeDialogs(Activity activity) {
        synchronized (rundownDialogs) {
            Iterator<SpinnerDialog> iterator = rundownDialogs.iterator();
            while (iterator.hasNext()) {
                SpinnerDialog dialog = iterator.next();
                if (dialog.activity == activity) {
                    iterator.remove();
                    if (dialog.progress != null && dialog.progress.isShowing()) {
                        dialog.progress.dismiss();
                    }
                }
            }
        }
    }

    public void dismiss() {
        activity.runOnUiThread(this);
    }

    public void setMessage(final String message) {
        activity.runOnUiThread(() -> {
            this.message = message;
            if (progress != null) {
                progress.setMessage(message);
            }
        });
    }

    @Override
    public void run() {
        if (activity.isFinishing()) {
            return;
        }

        if (progress == null) {
            View spinner = AppDialog.inflateContent(activity, R.layout.app_dialog_progress_content);
            progress = AppDialog.builder(activity)
                    .setTitle(title)
                    .setMessage(message)
                    .setView(spinner)
                    .setCancelable(finish)
                    .setCanceledOnTouchOutside(false)
                    .setOnCancel(finish ? activity::finish : null)
                    .show();
            synchronized (rundownDialogs) {
                rundownDialogs.add(this);
            }
        } else {
            synchronized (rundownDialogs) {
                if (rundownDialogs.remove(this) && progress.isShowing()) {
                    progress.dismiss();
                }
            }
        }
    }
}
