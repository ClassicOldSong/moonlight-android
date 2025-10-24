package com.limelight;

import static com.limelight.utils.DisplayUtils.getControlsDisplay;

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

import androidx.annotation.RequiresApi;

import com.limelight.utils.ExternalDisplayControlActivity;

public class StartExternalDisplayControlReceiver extends BroadcastReceiver {
    private static final long TIMEOUT_MS = 300;
    private static Handler handler = new Handler(Looper.getMainLooper());
    private static boolean isTimeoutActive = false;

    @RequiresApi(api = Build.VERSION_CODES.O)
    @Override
    public void onReceive(Context context, Intent intent) {
        requestFocusToGameActivity(true);
    }

    public static void requestFocusToExternalDisplayControl(Context context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Intent intentTouchpad = new Intent(context, ExternalDisplayControlActivity.class);
            intentTouchpad.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            Bundle optionsDefault = ActivityOptions.makeBasic().setLaunchDisplayId(getControlsDisplay(context).getDisplayId()).toBundle();
            context.startActivity(intentTouchpad, optionsDefault);
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
            }
            ActivityManager am = (ActivityManager) Game.instance.getSystemService(Context.ACTIVITY_SERVICE);
            if (am != null) {
                am.moveTaskToFront(Game.instance.getTaskId(), 0);
            }
        }

        handler.postDelayed(() -> isTimeoutActive = false, TIMEOUT_MS);
    }
}
