package com.limelight;

import android.app.Activity;
import android.app.Application;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Bundle;
import android.os.IBinder;
import android.widget.Toast;

import java.lang.ref.WeakReference;

import com.limelight.binding.input.driver.MenuNavigator;
import com.limelight.binding.input.driver.UsbDriverService;
import com.limelight.preferences.PreferenceConfiguration;
import com.limelight.profiles.ProfilesManager;

public class ArtemisApplication extends Application {
    private int startedActivities;
    private boolean boundUsbDriver;

    // Keeps the controller drivers running while the app is visible, so controllers Android
    // can't use on its own (e.g. the Steam Controller) can navigate our menus too.
    // Game and the controller tester take them over by setting their own listener.
    private final ServiceConnection usbDriverConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            ((UsbDriverService.UsbDriverBinder) service).start();
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {}
    };

    @Override
    public void onCreate() {
        super.onCreate();
        ProfilesManager profilesManager = ProfilesManager.getInstance();
        if (!profilesManager.load(this)) {
            Toast.makeText(this, R.string.profile_manager_failed_to_load, Toast.LENGTH_LONG).show();
        }

        registerActivityLifecycleCallbacks(new ActivityLifecycleCallbacks() {
            @Override
            public void onActivityStarted(Activity activity) {
                if (startedActivities++ == 0 && PreferenceConfiguration.readPreferences(activity).usbDriver) {
                    boundUsbDriver = bindService(new Intent(ArtemisApplication.this, UsbDriverService.class),
                            usbDriverConnection, Context.BIND_AUTO_CREATE);
                }
            }

            @Override
            public void onActivityStopped(Activity activity) {
                // Let go once we're in the background, so the controller goes back to normal elsewhere
                if (--startedActivities == 0 && boundUsbDriver) {
                    unbindService(usbDriverConnection);
                    boundUsbDriver = false;
                }
            }

            @Override public void onActivityCreated(Activity activity, Bundle savedInstanceState) {}
            @Override
            public void onActivityResumed(Activity activity) {
                // Game hands its keys to the host, so the menus' fake D-pad must never reach it
                if (!(activity instanceof Game)) {
                    MenuNavigator.target = new WeakReference<>(activity);
                }
            }

            @Override
            public void onActivityPaused(Activity activity) {
                if (MenuNavigator.target.get() == activity) {
                    MenuNavigator.target = new WeakReference<>(null);
                }
            }

            @Override public void onActivitySaveInstanceState(Activity activity, Bundle outState) {}
            @Override public void onActivityDestroyed(Activity activity) {}
        });
    }
}
