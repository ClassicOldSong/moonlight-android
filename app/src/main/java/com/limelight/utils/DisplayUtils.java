package com.limelight.utils;

import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.graphics.Point;
import android.hardware.display.DisplayManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.view.Display;
import android.annotation.TargetApi;
import android.widget.Toast;

import com.limelight.LimeLog;
import com.limelight.preferences.PreferenceConfiguration;

import java.util.ArrayList;
import java.util.List;

public class DisplayUtils {

    private static AlertDialog openDialog = null;

    public static class DisplayInfo {
        public final int width;
        public final int height;
        public final float refreshRate;
        public final long totalPixels;

        public DisplayInfo(int width, int height, float refreshRate) {
            this.width = Math.max(width, height);
            this.height = Math.min(width, height);
            this.refreshRate = refreshRate;
            this.totalPixels = (long)this.width * this.height;
        }

        @Override
        public String toString() {
            return String.format("%dx%d @ %.1f Hz", width, height, refreshRate);
        }
    }

    public static DisplayInfo getDisplayInfo(Display display) {
        if (display == null) {
            LimeLog.warning("getDisplayInfo called with null display.");
            return null;
        }

        int axeOneLength = 0;
        int axeTwoLength = 0;
        float displayRefreshRate = 0f;

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                Display.Mode currentMode = display.getMode();
                if (currentMode != null) {
                    axeOneLength = currentMode.getPhysicalWidth();
                    axeTwoLength = currentMode.getPhysicalHeight();
                    displayRefreshRate = currentMode.getRefreshRate();
                } else {
                    LimeLog.warning("display.getMode() returned null on API " + Build.VERSION.SDK_INT + ". Falling back to legacy methods.");
                    getLegacyDisplayInfo(display, sizePoint);
                    axeOneLength = sizePoint.x;
                    axeTwoLength = sizePoint.y;
                    displayRefreshRate = display.getRefreshRate();
                }
            } else {
                getLegacyDisplayInfo(display, sizePoint);
                axeOneLength = sizePoint.x;
                axeTwoLength = sizePoint.y;
                displayRefreshRate = display.getRefreshRate();
            }
        } catch (Exception e) {
            LimeLog.severe("Error getting display info for display ID " + display.getDisplayId() + ": " + e.getMessage());
            return null;
        }


        if (axeOneLength <= 0 || axeTwoLength <= 0) {
            LimeLog.warning("Retrieved invalid dimensions (" + axeOneLength + "x" + axeTwoLength + ") for display ID " + display.getDisplayId());
            if (sizePoint.x <= 0 || sizePoint.y <= 0) {
                try {
                    axeOneLength = display.getWidth();
                    axeTwoLength = display.getHeight();
                } catch (Exception ignored) {}
            } else {
                axeOneLength = sizePoint.x;
                axeTwoLength = sizePoint.y;
            }
            if (axeOneLength <= 0 || axeTwoLength <= 0) {
                LimeLog.severe("Could not retrieve valid dimensions for display ID " + display.getDisplayId());
                return null;
            }
        }

        int physicalWidth = Math.max(axeOneLength, axeTwoLength);
        int physicalHeight = Math.min(axeOneLength, axeTwoLength);

        return new DisplayInfo(physicalWidth, physicalHeight, displayRefreshRate);
    }
    private static final Point sizePoint = new Point();

    private static synchronized void getLegacyDisplayInfo(Display display, Point outSize) {
        outSize.set(0, 0);
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1) {
                display.getRealSize(outSize);
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.HONEYCOMB_MR2) {
                display.getSize(outSize);
            } else {
                outSize.x = display.getWidth();
                outSize.y = display.getHeight();
            }
        } catch (Exception e) {
            LimeLog.severe("Exception in getLegacyDisplayInfo: " + e.getMessage());
            outSize.set(0, 0);
        }
    }

    private static class CategorizedDisplays {
        Display mainDefaultDisplay = null;
        Display externalPresentationDisplay = null;
        Display secondaryInternalDisplay = null;
    }

    private static CategorizedDisplays findAndCategorizeDisplays(Context context) {
        CategorizedDisplays info = new CategorizedDisplays();
        DisplayManager displayManager = (DisplayManager) context.getSystemService(Context.DISPLAY_SERVICE);

        if (displayManager == null) {
            LimeLog.warning("DisplayManager service not found. Attempting fallback via WindowManager.");
            try {
                android.view.WindowManager wm = (android.view.WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
                if (wm != null) info.mainDefaultDisplay = wm.getDefaultDisplay();
                else LimeLog.severe("WindowManager service also not found.");
            } catch (Exception e) {
                LimeLog.severe("Could not get default display via WindowManager: " + e.toString());
            }
            if (info.mainDefaultDisplay == null) LimeLog.severe("FATAL: Could not obtain any reference to the main display.");
            return info;
        }

        Display[] displays = {};
        try {
            displays = displayManager.getDisplays();
        } catch (Exception e) {
            LimeLog.severe("Error getting displays from DisplayManager: " + e.toString());
        }


        for (Display display : displays) {
            if (display == null) continue;

            if (display.getDisplayId() == Display.DEFAULT_DISPLAY) {
                info.mainDefaultDisplay = display;
                continue;
            }

            if ((display.getFlags() & Display.FLAG_PRESENTATION) != 0) {
                if (info.externalPresentationDisplay == null) {
                    info.externalPresentationDisplay = display;
                    LimeLog.info("Found external presentation display: " + display.getName() + " (ID: " + display.getDisplayId() + ")");
                } else {
                    LimeLog.info("Ignoring additional external presentation display: " + display.getName());
                }
                continue;
            }

            if (info.secondaryInternalDisplay == null) {
                info.secondaryInternalDisplay = display;
                LimeLog.info("Found secondary internal display: " + display.getName() + " (ID: " + display.getDisplayId() + ")");
            } else {
                LimeLog.info("Ignoring additional secondary internal display: " + display.getName());
            }
        }

        if (info.mainDefaultDisplay == null) {
            try {
                info.mainDefaultDisplay = displayManager.getDisplay(Display.DEFAULT_DISPLAY);
                LimeLog.warning("Main display (ID 0) not found in displays list, using getDisplay(DEFAULT_DISPLAY).");
            } catch (Exception e) {
                LimeLog.severe("FATAL: Could not get display for DEFAULT_DISPLAY ID: " + e.toString());
                if (displays.length > 0 && displays[0] != null) info.mainDefaultDisplay = displays[0];
            }
        }
        if (info.mainDefaultDisplay == null) {
            LimeLog.severe("FATAL: Could not obtain any valid reference to the main display after all fallbacks.");
        }
        // showCategorizationDialog(context, info);
        return info;
    }

    // --- getGameStreamDisplay - Contains Full Logic ---
    public static Display getGameStreamDisplay(Context context) {
        ensureDialogShown(context); // Ensure dialog appears once
        CategorizedDisplays info = findAndCategorizeDisplays(context); // Get current displays
        PreferenceConfiguration prefs = PreferenceConfiguration.readPreferences(context);

        Display defaultDisplay = info.mainDefaultDisplay;
        // Essential fallback
        if (defaultDisplay == null) {
            LimeLog.severe("Cannot determine game stream display: mainDefaultDisplay is null. Using OS default.");
            try {
                DisplayManager dm = (DisplayManager) context.getSystemService(Context.DISPLAY_SERVICE);
                Display fallbackDisplay = (dm != null) ? dm.getDisplay(Display.DEFAULT_DISPLAY) : null;
                if (fallbackDisplay == null) { LimeLog.severe("OS default display is also null!"); }
                return fallbackDisplay;
            } catch (Exception e) {
                LimeLog.severe("Error getting OS default display in fallback: " + e.toString());
                return null;
            }
        }

        // --- Logic Flow ---
        boolean treatAsInternal = false;
        if (prefs.enableFullExDisplay) {
            if (info.externalPresentationDisplay != null) {
                boolean potentiallyMismatchedIDs = false;

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) { // API 34
                    try {
                        String deviceManufacturer = Build.MANUFACTURER;
                        android.hardware.display.DeviceProductInfo productInfo = info.externalPresentationDisplay.getDeviceProductInfo();
                        String displayManufacturerId = (productInfo != null) ? productInfo.getManufacturerPnpId() : null;

                        if (deviceManufacturer != null && displayManufacturerId != null &&
                                displayManufacturerId.toLowerCase().contains(deviceManufacturer.toLowerCase()))
                        {
                            LimeLog.info("External display manufacturer ID matches device. Treating as potentially internal.");
                            treatAsInternal = true;
                        } else {
                            LimeLog.info("External display manufacturer ID does NOT match device or info unavailable.");
                            potentiallyMismatchedIDs = true;
                        }
                    } catch (Exception e) {
                        LimeLog.severe("Error comparing manufacturer IDs:" + e);
                        potentiallyMismatchedIDs = true;
                    }
                } else {
                    LimeLog.info("Manufacturer ID check skipped (Requires API 34+).");
                    potentiallyMismatchedIDs = true;
                }

                if (!treatAsInternal) {
                    DisplayInfo mainInfo = getDisplayInfo(defaultDisplay);
                    DisplayInfo externalInfo = getDisplayInfo(info.externalPresentationDisplay);
                    if (potentiallyMismatchedIDs &&
                            mainInfo != null && externalInfo != null &&
                            // externalInfo.refreshRate < mainInfo.refreshRate - 1.0f && // Refresh rate check removed
                            externalInfo.totalPixels < mainInfo.totalPixels &&
                            !isCommonMonitorAspectRatio(externalInfo))
                    {
                        LimeLog.info("External display (ID " + info.externalPresentationDisplay.getDisplayId() + ") meets heuristics (smaller, non-monitor ratio). Manufacturer check failed/skipped. Assuming it's secondary internal.");
                        treatAsInternal = true;
                    } else {
                        LimeLog.info("enableFullExDisplay ON: Using true external presentation display (ID: " + info.externalPresentationDisplay.getDisplayId() + "). Heuristics did not apply or failed.");
                        return info.externalPresentationDisplay;
                    }
                }
            }

            Display effectiveSecondary = treatAsInternal ? info.externalPresentationDisplay : info.secondaryInternalDisplay;

            if (effectiveSecondary != null) {
                DisplayInfo mainInfo = getDisplayInfo(defaultDisplay);
                DisplayInfo secondaryInfo = getDisplayInfo(effectiveSecondary);
                Display largerScreen = defaultDisplay;

                if (mainInfo != null && secondaryInfo != null) {
                    if (secondaryInfo.totalPixels > mainInfo.totalPixels) {
                        largerScreen = effectiveSecondary;
                    }
                } else {
                    LimeLog.warning("enableFullExDisplay ON: Could not compare internal/effective-secondary displays; assuming default (ID 0) is larger.");
                }
                LimeLog.info("enableFullExDisplay ON (treating as internal screens): Using LARGER display (ID: " + largerScreen.getDisplayId() + ") for game stream.");
                return largerScreen;
            }
            else {
                LimeLog.info("enableFullExDisplay ON: Using main default display (ID: " + defaultDisplay.getDisplayId() + ") for game stream (only internal screen).");
                return defaultDisplay;
            }
        }

        if (info.secondaryInternalDisplay != null) {
            DisplayInfo mainInfo = getDisplayInfo(defaultDisplay);
            DisplayInfo secondaryInfo = getDisplayInfo(info.secondaryInternalDisplay);
            Display largerInternal = defaultDisplay;

            if (mainInfo != null && secondaryInfo != null) {
                if (secondaryInfo.totalPixels > mainInfo.totalPixels) {
                    largerInternal = info.secondaryInternalDisplay;
                }
            } else {
                LimeLog.warning("Default OFF: Could not compare internal displays; assuming default (ID 0) is larger.");
            }
            LimeLog.info("Default OFF: Using LARGER internal display (ID: " + largerInternal.getDisplayId() + ") for game stream.");
            return largerInternal;
        }

        LimeLog.info("Default OFF: Using main default display (ID: " + defaultDisplay.getDisplayId() + ") for game stream (single screen).");
        return defaultDisplay;
    }


    private static boolean isCommonMonitorAspectRatio(DisplayInfo info) {
        if (info == null || info.height <= 0) return false;

        float ratio = (float) info.width / (float) info.height;
        final float EPSILON = 0.05f;

        final float RATIO_16_9 = 16.0f / 9.0f;
        final float RATIO_16_10 = 16.0f / 10.0f;
        final float RATIO_21_9 = 21.0f / 9.0f;
        final float RATIO_32_9 = 32.0f / 9.0f;
        final float RATIO_4_3 = 4.0f / 3.0f;

        boolean isMonitorRatio = Math.abs(ratio - RATIO_16_9) < EPSILON ||
                Math.abs(ratio - RATIO_16_10) < EPSILON ||
                Math.abs(ratio - RATIO_21_9) < EPSILON ||
                Math.abs(ratio - RATIO_32_9) < EPSILON ||
                Math.abs(ratio - RATIO_4_3) < EPSILON;

        LimeLog.info("Aspect ratio check: " + info.width + "x" + info.height + " -> ratio=" + String.format("%.3f", ratio) + ", isMonitorRatio=" + isMonitorRatio);
        return isMonitorRatio;
    }


    // --- getControlsDisplay - Contains Full Logic ---
    public static Display getControlsDisplay(Context context) {
        ensureDialogShown(context); // Ensure dialog appears once
        CategorizedDisplays info = findAndCategorizeDisplays(context); // Get current displays
        // --- Determine where the game WILL be displayed ---
        Display gameDisplay = getGameStreamDisplay(context); // Call the public method directly
        // --- End Game Display Determination ---

        Display defaultDisplay = info.mainDefaultDisplay;
        // Essential Fallbacks
        if (defaultDisplay == null || gameDisplay == null) {
            LimeLog.severe("Cannot determine controls display: mainDefaultDisplay or gameDisplay is null. Using OS default.");
            try {
                DisplayManager dm = (DisplayManager) context.getSystemService(Context.DISPLAY_SERVICE);
                Display fallbackDisplay = (dm != null) ? dm.getDisplay(Display.DEFAULT_DISPLAY) : null;
                if (fallbackDisplay == null) { LimeLog.severe("OS default display is also null!"); }
                return fallbackDisplay;
            } catch (Exception e) {
                LimeLog.severe("Error getting OS default display in controls fallback: " + e.toString());
                return null;
            }
        }

        // --- Identify potential candidates for the controls display ---
        List<Display> controlCandidates = new ArrayList<>();
        if (info.mainDefaultDisplay != null && info.mainDefaultDisplay.getDisplayId() != gameDisplay.getDisplayId()) {
            controlCandidates.add(info.mainDefaultDisplay);
        }
        if (info.secondaryInternalDisplay != null && info.secondaryInternalDisplay.getDisplayId() != gameDisplay.getDisplayId()) {
            controlCandidates.add(info.secondaryInternalDisplay);
        }
        if (info.externalPresentationDisplay != null && info.externalPresentationDisplay.getDisplayId() != gameDisplay.getDisplayId()) {
            controlCandidates.add(info.externalPresentationDisplay);
        }

        List<Display> validControlCandidates = new ArrayList<>();
        for (Display candidate : controlCandidates) {
            DisplayInfo candidateInfo = getDisplayInfo(candidate);
            if (candidateInfo != null) {
                validControlCandidates.add(candidate);
            } else {
                LimeLog.warning("Control candidate display (ID: " + ((candidate != null) ? candidate.getDisplayId() : "null") + ") is below minimum size or info unavailable. Ignoring.");
            }
        }

        // --- Select the best control display ---
        Display selectedControlsDisplay = null;
        if (validControlCandidates.size() == 1) {
            selectedControlsDisplay = validControlCandidates.get(0);
            LimeLog.info("Using the only valid secondary display (ID: " + selectedControlsDisplay.getDisplayId() + ") for controls.");
        } else if (validControlCandidates.size() > 1) {
            Display smallestValid = null;
            long smallestPixels = Long.MAX_VALUE;
            for (Display validCandidate : validControlCandidates) {
                DisplayInfo validInfo = getDisplayInfo(validCandidate);
                if (validInfo != null && validInfo.totalPixels < smallestPixels) {
                    smallestPixels = validInfo.totalPixels;
                    smallestValid = validCandidate;
                }
            }
            selectedControlsDisplay = smallestValid;
            if (selectedControlsDisplay != null) {
                LimeLog.info("Multiple valid secondary displays found. Using the smallest (ID: " + selectedControlsDisplay.getDisplayId() + ") for controls.");
            } else {
                LimeLog.warning("Could not determine smallest among valid control candidates. Falling back to overlay.");
                selectedControlsDisplay = gameDisplay;
            }
        } else {
            LimeLog.info("No valid secondary display found for controls. Using game display (ID: " + gameDisplay.getDisplayId() + ") for overlay controls.");
            selectedControlsDisplay = gameDisplay;
        }

        return selectedControlsDisplay;
    }


    private static String getDisplayDetailsString(Display display) {
        if (display == null) {
            return "null display object";
        }
        StringBuilder details = new StringBuilder();
        DisplayInfo di = getDisplayInfo(display);

        details.append("ID: ").append(display.getDisplayId());
        details.append(", Name: ").append(display.getName());
        details.append(", Res: ").append(di != null ? di.toString() : "N/A"); // Use DisplayInfo.toString()

        try {
            int flags = display.getFlags();
            List<String> flagNames = new ArrayList<>();
            if ((flags & Display.FLAG_PRIVATE) != 0) flagNames.add("PRIVATE");
            if ((flags & Display.FLAG_PRESENTATION) != 0) flagNames.add("PRESENTATION");
            if ((flags & Display.FLAG_SECURE) != 0) flagNames.add("SECURE");
            if ((flags & Display.FLAG_SUPPORTS_PROTECTED_BUFFERS) != 0) flagNames.add("PROTECTED");
            if ((flags & Display.FLAG_ROUND) != 0) flagNames.add("ROUND");

            details.append(", Flags: ");
            if (flagNames.isEmpty()) {
                details.append("None");
            } else {
                details.append("[").append(String.join("|", flagNames)).append("]");
            }
            details.append(" (").append(flags).append(")");
        } catch (Exception e) {
            details.append(", Flags: Error reading flags (").append(e.getMessage()).append(")");
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) { // API 34
            try {
                android.hardware.display.DeviceProductInfo productInfo = display.getDeviceProductInfo();
                if (productInfo != null) {
                    details.append(", Product: [");
                    details.append("Name: ").append(productInfo.getName());
                    String manufId = productInfo.getManufacturerPnpId();
                    if (manufId != null && !manufId.isEmpty()) {
                        details.append(", ManufId: ").append(manufId);
                    }
                    String prodId = productInfo.getProductId();
                    if (prodId != null && !prodId.isEmpty()) {
                        details.append(", ProdId: ").append(prodId);
                    }
                    details.append("]");
                } else {
                    details.append(", ProductInfo: null");
                }
            } catch (NoSuchMethodError e) {
                LimeLog.warning("getProductInfo method not found on API 34+ device?");
                details.append(", ProductInfo: Not Available (API Error)");
            } catch (Exception e) {
                LimeLog.severe("Error getting product info: " + e.getMessage());
                details.append(", ProductInfo: Error reading info");
            }
        } else {
            details.append(", ProductInfo: Not Available (API < 34)");
        }

        return details.toString();
    }


    private static void showCategorizationDialog(Context context, CategorizedDisplays info) {
        if (context == null) {
            LimeLog.severe("Cannot show display dialog: Context is null.");
            return;
        }

        if(openDialog != null && openDialog.isShowing()) {
            try { openDialog.dismiss(); } catch (Exception e) { LimeLog.warning("Error dismissing previous dialog: " + e.getMessage()); }
            openDialog = null;
        }

        final StringBuilder messageBuilder = new StringBuilder();
        boolean displayFound = false;

        messageBuilder.append("--- Displays Found ---\n");

        if (info.mainDefaultDisplay != null) {
            messageBuilder.append("Main (Default): \n  ").append(getDisplayDetailsString(info.mainDefaultDisplay)).append("\n\n");
            displayFound = true;
        } else {
            messageBuilder.append("Main (Default): Not Found!\n\n");
        }
        if (info.externalPresentationDisplay != null) {
            messageBuilder.append("External (Presentation Flag): \n  ").append(getDisplayDetailsString(info.externalPresentationDisplay)).append("\n\n");
            displayFound = true;
        } else {
            messageBuilder.append("External (Presentation Flag): None\n\n");
        }
        if (info.secondaryInternalDisplay != null) {
            messageBuilder.append("Secondary Internal (No Pres. Flag): \n  ").append(getDisplayDetailsString(info.secondaryInternalDisplay)).append("\n\n");
            displayFound = true;
        } else {
            messageBuilder.append("Secondary Internal (No Pres. Flag): None\n\n");
        }

        // --- Determine final selections for the dialog ---
        // Need to replicate the logic *briefly* here to show the result
        Display determinedGameDisplay = null;
        Display determinedControlsDisplay = null;
        try {
            // Replicate getGameStreamDisplay logic (simplified, without dialog call)
            PreferenceConfiguration tempPrefs = PreferenceConfiguration.readPreferences(context);
            Display tempDefault = info.mainDefaultDisplay;
            if (tempDefault != null) {
                boolean tempTreatAsInternal = false;
                if (tempPrefs.enableFullExDisplay && info.externalPresentationDisplay != null) {
                    // Simplified heuristic check for dialog display purpose
                    boolean tempPotentiallyMismatched = true; // Assume mismatch for dialog unless proven otherwise by API 34+ check (not replicated here for brevity)
                    DisplayInfo tempMainInfo = getDisplayInfo(tempDefault);
                    DisplayInfo tempExternalInfo = getDisplayInfo(info.externalPresentationDisplay);
                    if (tempPotentiallyMismatched && tempMainInfo != null && tempExternalInfo != null &&
                            tempExternalInfo.totalPixels < tempMainInfo.totalPixels && !isCommonMonitorAspectRatio(tempExternalInfo)) {
                        tempTreatAsInternal = true;
                    } else {
                        determinedGameDisplay = info.externalPresentationDisplay; // Assume external if heuristics fail/don't apply
                    }
                }

                if (determinedGameDisplay == null) { // If not assigned external
                    Display tempEffectiveSecondary = tempTreatAsInternal ? info.externalPresentationDisplay : info.secondaryInternalDisplay;
                    if (tempEffectiveSecondary != null) {
                        DisplayInfo tempMainInfo = getDisplayInfo(tempDefault);
                        DisplayInfo tempSecondaryInfo = getDisplayInfo(tempEffectiveSecondary);
                        determinedGameDisplay = tempDefault;
                        if (tempMainInfo != null && tempSecondaryInfo != null && tempSecondaryInfo.totalPixels > tempMainInfo.totalPixels) {
                            determinedGameDisplay = tempEffectiveSecondary;
                        }
                    } else {
                        determinedGameDisplay = tempDefault;
                    }
                }
            }
            // Replicate getControlsDisplay logic (simplified)
            if (determinedGameDisplay != null && tempDefault != null) {
                boolean tempGameIsTrulyExternal = info.externalPresentationDisplay != null && determinedGameDisplay.getDisplayId() == info.externalPresentationDisplay.getDisplayId();
                // Additional check needed here to ensure it wasn't treated as internal
                // For simplicity in dialog, we might omit perfect replication
                if (tempGameIsTrulyExternal) {
                    determinedControlsDisplay = (info.secondaryInternalDisplay != null) ?
                            ((getDisplayInfo(info.secondaryInternalDisplay).totalPixels < getDisplayInfo(tempDefault).totalPixels) ? info.secondaryInternalDisplay : tempDefault)
                            : tempDefault; // Simplified: picks smaller of internals, or default
                    // Missing MIN_SIZE check here for dialog brevity
                } else if (info.secondaryInternalDisplay != null) {
                    determinedControlsDisplay = (determinedGameDisplay.getDisplayId() == tempDefault.getDisplayId()) ? info.secondaryInternalDisplay : tempDefault;
                } else {
                    determinedControlsDisplay = determinedGameDisplay; // Overlay
                }
            }

        } catch (Exception e) {
            LimeLog.severe("Error determining selections for dialog: " + e.getMessage());
        }


        messageBuilder.append("--- Final Selection ---\n");
        messageBuilder.append("Game Stream Display: \n  ");
        messageBuilder.append(determinedGameDisplay != null ? getDisplayDetailsString(determinedGameDisplay) : "ERROR (null)").append("\n\n");
        messageBuilder.append("Controls Display: \n  ");
        messageBuilder.append(determinedControlsDisplay != null ? getDisplayDetailsString(determinedControlsDisplay) : "ERROR (null)").append("\n");


        final String message = displayFound ? messageBuilder.toString().trim() : "Error: No displays were categorized.";
        final String title = "Display Selection Info";

        new Handler(Looper.getMainLooper()).post(() -> {
            try {
                if (context instanceof android.app.Activity && ((android.app.Activity) context).isFinishing()) {
                    LimeLog.warning("Activity is finishing, cannot show display dialog.");
                    return;
                }

                openDialog = new AlertDialog.Builder(context)
                        .setTitle(title)
                        .setMessage(message)
                        .setPositiveButton(android.R.string.ok, (dialog, which) -> {
                            dialog.dismiss();
                            openDialog = null;
                        })
                        .setNeutralButton("Copy Info", (dialog, which) -> {
                            ClipboardManager clipboard = (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
                            if (clipboard != null) {
                                ClipData clip = ClipData.newPlainText("Display Info", message);
                                clipboard.setPrimaryClip(clip);
                                Toast.makeText(context, "Display info copied to clipboard", Toast.LENGTH_SHORT).show();
                            } else {
                                Toast.makeText(context, "Failed to access clipboard", Toast.LENGTH_SHORT).show();
                            }
                        })
                        .setCancelable(false)
                        .show();
            } catch (Exception e) {
                LimeLog.severe("Failed to show display selection dialog: " + e.getMessage());
                openDialog = null;
            }
        });
    }

    private static volatile boolean dialogShown = false;
    private static void ensureDialogShown(Context context) {
        if (!dialogShown) {
            synchronized (DisplayUtils.class) {
                if (!dialogShown) {
                    CategorizedDisplays info = findAndCategorizeDisplays(context); // This now calls the dialog
                    // Selections are determined inside the dialog show logic for display
                    dialogShown = true;
                }
            }
        }
    }


    public static boolean hasSecondaryDisplay(Context context) {
        ensureDialogShown(context); // Ensure dialog shows if not already
        CategorizedDisplays info = findAndCategorizeDisplays(context); // Find displays again
        boolean hasSecondary = info.externalPresentationDisplay != null || info.secondaryInternalDisplay != null;
        return hasSecondary;
    }

}