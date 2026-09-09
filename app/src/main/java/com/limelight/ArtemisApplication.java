package com.limelight;

import android.app.Application;
import android.widget.Toast;

import com.limelight.binding.input.virtual_controller.OscProfilesManager;
import com.limelight.profiles.ProfilesManager;

public class ArtemisApplication extends Application {
    @Override
    public void onCreate() {
        super.onCreate();
        ProfilesManager profilesManager = ProfilesManager.getInstance();
        if (!profilesManager.load(this)) {
            Toast.makeText(this, R.string.profile_manager_failed_to_load, Toast.LENGTH_LONG).show();
        }

        // Load OSC profiles
        OscProfilesManager oscProfilesManager = OscProfilesManager.getInstance();
        oscProfilesManager.load(this);
    }
}