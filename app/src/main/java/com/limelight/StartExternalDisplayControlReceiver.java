package com.limelight;

import android.app.ActivityManager;
import android.app.ActivityOptions;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Display;

import com.limelight.utils.ExternalDisplayControlActivity;

public class StartExternalDisplayControlReceiver extends BroadcastReceiver {
    private static final long TIMEOUT_MS = 300;
    private static Handler handler = new Handler(Looper.getMainLooper());
    private static boolean isTimeoutActive = false;

    @Override
    public void onReceive(Context context, Intent intent) {
        requestFocusToExternalDisplayControl(context);
    }

    public static boolean requestFocusToExternalDisplayControl(Context context) {
        Intent intentTouchpad = new Intent(context, ExternalDisplayControlActivity.class);
        intentTouchpad.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                Bundle optionsDefault = ActivityOptions.makeBasic().setLaunchDisplayId(Display.DEFAULT_DISPLAY).toBundle();
                context.startActivity(intentTouchpad, optionsDefault);
            }
            else {
                context.startActivity(intentTouchpad);
            }
            return true;
        }
        catch (RuntimeException e) {
            LimeLog.warning("Unable to focus external display controller: " + e);
            return false;
        }
    }

    public static void requestFocusToGameActivity(boolean focusExternalDisplayControl) {
        if (isTimeoutActive) {
            return;
        }

        isTimeoutActive = true;

        if (Game.instance != null) {
            if (focusExternalDisplayControl) {
                requestFocusToExternalDisplayControl(Game.instance);
                handler.postDelayed(() -> isTimeoutActive = false, TIMEOUT_MS);
                return;
            }
            ActivityManager am = (ActivityManager) Game.instance.getSystemService(Context.ACTIVITY_SERVICE);
            if (am != null) {
                am.moveTaskToFront(Game.instance.getTaskId(), 0);
            }
        }

        handler.postDelayed(() -> isTimeoutActive = false, TIMEOUT_MS);
    }
}
