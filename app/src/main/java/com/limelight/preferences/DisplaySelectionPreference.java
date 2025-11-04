package com.limelight.preferences;

import android.content.Context;
import android.hardware.display.DisplayManager;
import android.os.Build;
import android.util.AttributeSet;
import android.view.Display;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;
import androidx.preference.ListPreference;

import java.util.ArrayList;
import java.util.List;

/**
 * A custom ListPreference that dynamically populates with available displays.
 * Shows display ID, name, and resolution to help users identify screens.
 */
public class DisplaySelectionPreference extends ListPreference {

    public DisplaySelectionPreference(@NonNull Context context, @Nullable AttributeSet attrs, int defStyleAttr, int defStyleRes) {
        super(context, attrs, defStyleAttr, defStyleRes);
        populateDisplays();
    }

    public DisplaySelectionPreference(@NonNull Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        populateDisplays();
    }

    public DisplaySelectionPreference(@NonNull Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        populateDisplays();
    }

    public DisplaySelectionPreference(@NonNull Context context) {
        super(context);
        populateDisplays();
    }

    /**
     * Populates the preference with available displays from DisplayManager.
     * Each entry shows: "Display [ID]: [Name] ([Width]x[Height])"
     */
    private void populateDisplays() {
        DisplayManager displayManager = (DisplayManager) getContext().getSystemService(Context.DISPLAY_SERVICE);
        if (displayManager == null) {
            // Fallback to simple primary/secondary if DisplayManager unavailable
            setEntries(new CharSequence[]{"Primary display", "Secondary display"});
            setEntryValues(new CharSequence[]{"0", "1"});
            return;
        }

        Display[] displays = displayManager.getDisplays();
        List<CharSequence> entries = new ArrayList<>();
        List<CharSequence> entryValues = new ArrayList<>();

        for (Display display : displays) {
            int displayId = display.getDisplayId();
            String displayName = getDisplayName(display);
            String resolution = getDisplayResolution(display);

            // Format: "Display 0: Built-in Screen (2560x1600)"
            String entry = String.format("Display %d: %s (%s)", displayId, displayName, resolution);

            entries.add(entry);
            entryValues.add(String.valueOf(displayId));
        }

        // If no displays found, add a default entry
        if (entries.isEmpty()) {
            entries.add("Primary display");
            entryValues.add("0");
        }

        setEntries(entries.toArray(new CharSequence[0]));
        setEntryValues(entryValues.toArray(new CharSequence[0]));
    }

    /**
     * Gets a friendly name for the display.
     */
    private String getDisplayName(Display display) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            // Android 11+ has a proper display name API
            return getDisplayNameR(display);
        } else {
            // Fallback for older Android versions
            int displayId = display.getDisplayId();
            if (displayId == Display.DEFAULT_DISPLAY) {
                return "Built-in Screen";
            } else {
                return "External Display";
            }
        }
    }

    @RequiresApi(api = Build.VERSION_CODES.R)
    private String getDisplayNameR(Display display) {
        String name = display.getName();
        int displayId = display.getDisplayId();

        if (name != null && !name.isEmpty()) {
            return name;
        } else if (displayId == Display.DEFAULT_DISPLAY) {
            return "Built-in Screen";
        } else {
            return "External Display";
        }
    }

    /**
     * Gets the display resolution as "WIDTHxHEIGHT"
     */
    private String getDisplayResolution(Display display) {
        Display.Mode mode = display.getMode();
        return mode.getPhysicalWidth() + "x" + mode.getPhysicalHeight();
    }

    /**
     * Refresh the display list when the preference is shown.
     * This ensures we have the latest display information.
     */
    @Override
    protected void onClick() {
        populateDisplays();
        super.onClick();
    }
}
