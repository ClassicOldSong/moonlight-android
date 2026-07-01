package com.limelight.utils;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.hardware.display.DisplayManager;
import android.os.Build;
import android.view.Display;
import android.widget.Toast;

import androidx.annotation.RequiresApi;

import com.limelight.AppView;
import com.limelight.Game;
import com.limelight.LimeLog;
import com.limelight.R;
import com.limelight.ShortcutTrampoline;
import com.limelight.binding.PlatformBinding;
import com.limelight.computers.ComputerManagerService;
import com.limelight.nvstream.http.ComputerDetails;
import com.limelight.nvstream.http.HostHttpResponseException;
import com.limelight.nvstream.http.NvApp;
import com.limelight.nvstream.http.NvHTTP;
import com.limelight.nvstream.jni.MoonBridge;
import com.limelight.preferences.PreferenceConfiguration;

import org.xmlpull.v1.XmlPullParserException;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.net.UnknownHostException;
import java.security.cert.CertificateEncodingException;
import java.util.ArrayList;

public class ServerHelper {
    public static final String CONNECTION_TEST_SERVER = "android.conntest.moonlight-stream.org";

    public static ComputerDetails.AddressTuple getCurrentAddressFromComputer(ComputerDetails computer) throws IOException {
        if (computer.activeAddress == null) {
            throw new IOException("No active address for "+computer.name);
        }
        return computer.activeAddress;
    }

    public static Intent createPcShortcutIntent(Activity parent, ComputerDetails computer) {
        Intent i = new Intent(parent, ShortcutTrampoline.class);
        i.putExtra(AppView.NAME_EXTRA, computer.name);
        i.putExtra(AppView.UUID_EXTRA, computer.uuid);
        i.setAction(Intent.ACTION_DEFAULT);
        return i;
    }

    public static Intent createAppShortcutIntent(Activity parent, ComputerDetails computer, NvApp app) {
        Intent i = new Intent(parent, ShortcutTrampoline.class);
        i.putExtra(AppView.NAME_EXTRA, computer.name);
        i.putExtra(AppView.UUID_EXTRA, computer.uuid);
        i.putExtra(Game.EXTRA_APP_NAME, app.getAppName());
        i.putExtra(Game.EXTRA_APP_UUID, app.getAppUUID());
        i.putExtra(Game.EXTRA_APP_ID, ""+app.getAppId());
        i.putExtra(Game.EXTRA_APP_HDR, app.isHdrSupported());
        i.setAction(Intent.ACTION_DEFAULT);
        return i;
    }
    /**
     * Check if a display's name suggests it's an externally connected display
     * (HDMI, DP, AR glasses, USB monitor, etc.).
     */
    private static boolean hasExternalDisplayName(Display display) {
        String name = display.getName();
        if (name == null) return false;
        String lower = name.toLowerCase();
        return lower.contains("hdmi") || lower.contains("displayport") ||
               lower.contains("dp-") || lower.contains("dp_") ||
               lower.contains("external") || lower.contains("virtual") ||
               lower.contains("miracast") || lower.contains("wireless") ||
               lower.contains("xreal") || lower.contains("rokid") ||
               lower.contains("viture") || lower.contains("nreal") ||
               lower.contains("quest") || lower.contains("pico") ||
               lower.contains("hololens") || lower.contains("magic leap") ||
               lower.contains("vuzix") || lower.contains("epson") ||
               lower.contains("mad gaze") || lower.contains("shadow") ||
               lower.contains("lenovo") || lower.contains("thinkreality");
    }

    /**
     * Check if a display is a built-in/internal screen (not an externally connected display).
     * Uses multiple heuristics: flags, display name patterns, and comparison with default display.
     */
    private static boolean isBuiltInDisplay(Display display) {
        int flags = display.getFlags();

        // FLAG_PRIVATE indicates the display is internal/private to the system
        if ((flags & Display.FLAG_PRIVATE) != 0) {
            return true;
        }

        // Known external display name patterns → definitely external
        if (hasExternalDisplayName(display)) {
            return false;
        }

        // SECURE and PROTECTED flags are typical of built-in displays (DRM-protected panels).
        // External displays (HDMI, DP, AR glasses) rarely have these flags.
        if ((flags & Display.FLAG_SECURE) != 0 ||
            (flags & Display.FLAG_SUPPORTS_PROTECTED_BUFFERS) != 0) {
            return true;
        }

        // Manufacturer name match (internal screens often have device OEM in display name)
        String displayName = display.getName();
        String deviceManufacturer = Build.MANUFACTURER;
        if (displayName != null && deviceManufacturer != null &&
            displayName.toLowerCase().contains(deviceManufacturer.toLowerCase())) {
            return true;
        }

        // Default: treat as external (safer to use external display mode features)
        return false;
    }

    /**
     * Check if the device has two internal screens (dual-screen handheld like AYN Thor).
     * Uses combined heuristics: flag patterns across all displays, name checks.
     */
    private static boolean isDualInternalScreenDevice(DisplayManager displayManager, Display defaultDisplay) {
        Display[] allDisplays = displayManager.getDisplays();
        int internalScreenCount = 0;
        int totalScreens = allDisplays.length;

        for (Display d : allDisplays) {
            int flags = d.getFlags();
            LimeLog.info("Display " + d.getDisplayId() + ": " + d.getName() +
                         " " + d.getMode().getPhysicalWidth() + "x" + d.getMode().getPhysicalHeight() +
                         " flags=" + flags);
            if (isBuiltInDisplay(d)) {
                internalScreenCount++;
            }
        }

        // If standard detection found >= 2 internal screens, we're done
        if (internalScreenCount >= 2) {
            LimeLog.info("Detected " + internalScreenCount + " internal screen(s) — dual internal screen device");
            return true;
        }

        // Fallback: if there are exactly 2 displays and neither has clear external
        // indicators, AND both share SECURE or PROTECTED flags, treat as dual internal.
        // This catches devices like AYN Thor where the secondary screen has PRESENTATION
        // flag but is actually a second built-in panel.
        if (totalScreens == 2 && internalScreenCount == 1) {
            int defaultFlags = defaultDisplay.getFlags();
            boolean defaultIsSecure = (defaultFlags & (Display.FLAG_SECURE | Display.FLAG_SUPPORTS_PROTECTED_BUFFERS)) != 0;

            for (Display d : allDisplays) {
                if (d.getDisplayId() == Display.DEFAULT_DISPLAY) continue;
                if (isBuiltInDisplay(d)) continue; // already counted above

                int dFlags = d.getFlags();
                boolean dIsSecure = (dFlags & (Display.FLAG_SECURE | Display.FLAG_SUPPORTS_PROTECTED_BUFFERS)) != 0;

                // If non-default display shares SECURE/PROTECTED with default AND
                // has no external name, it's likely a secondary internal screen
                if (defaultIsSecure && dIsSecure && !hasExternalDisplayName(d)) {
                    LimeLog.info("Non-default display shares internal flags with default — treating as dual internal screen");
                    return true;
                }
            }
        }

        LimeLog.info("Detected " + internalScreenCount + " internal screen(s) — not dual internal");
        return false;
    }

    /**
     * Return the display with the larger physical area from two candidates.
     */
    private static Display getLargerDisplay(Display a, Display b) {
        int areaA = a.getMode().getPhysicalWidth() * a.getMode().getPhysicalHeight();
        int areaB = b.getMode().getPhysicalWidth() * b.getMode().getPhysicalHeight();
        LimeLog.info("Comparing displays: " + a.getDisplayId() + " area=" + areaA +
                     " vs " + b.getDisplayId() + " area=" + areaB);
        return (areaA >= areaB) ? a : b;
    }

    public static Display getActiveDisplay(Context context, PreferenceConfiguration prefs) {
        DisplayManager displayManager = (DisplayManager) context.getSystemService(Context.DISPLAY_SERVICE);
        Display defaultDisplay = displayManager.getDisplay(Display.DEFAULT_DISPLAY);
        Display secondary = getSecondaryDisplay(context);

        if (secondary != null && prefs.enableFullExDisplay) {
            // Check if both displays are internal (dual-screen device like AYN Thor)
            if (isBuiltInDisplay(defaultDisplay) && isBuiltInDisplay(secondary)) {
                // Dual internal screens: use the LARGER one for streaming
                LimeLog.info("Dual internal screen detected - selecting larger display for streaming");
                return getLargerDisplay(defaultDisplay, secondary);
            }
            // True external display (AR glasses, USB monitor, etc.): use it
            return secondary;
        }

        return defaultDisplay;
    }

    public static Display getSecondaryDisplay(Context context) {
        DisplayManager displayManager = (DisplayManager) context.getSystemService(Context.DISPLAY_SERVICE);
        Display[] displays = displayManager.getDisplays();
        int mainDisplayId = Display.DEFAULT_DISPLAY;
        Display defaultDisplay = displayManager.getDisplay(mainDisplayId);

        // Collect non-default displays
        ArrayList<Display> nonDefaultDisplays = new ArrayList<>();
        for (Display d : displays) {
            LimeLog.info(d.toString());
            if (d.getDisplayId() != mainDisplayId) {
                nonDefaultDisplays.add(d);
            }
        }

        if (nonDefaultDisplays.isEmpty()) {
            return null;
        }

        // On dual-internal-screen devices, prefer the larger non-default internal display
        // as the "secondary" display for dual-screen mode
        if (isDualInternalScreenDevice(displayManager, defaultDisplay)) {
            // Return the largest non-default internal display
            Display best = null;
            int maxArea = 0;
            for (Display d : nonDefaultDisplays) {
                if (isBuiltInDisplay(d)) {
                    int area = d.getMode().getPhysicalWidth() * d.getMode().getPhysicalHeight();
                    if (area > maxArea) {
                        maxArea = area;
                        best = d;
                    }
                }
            }
            if (best != null) {
                LimeLog.info("Dual internal screen: selected secondary display " + best.getDisplayId());
                return best;
            }
        }

        // Prefer truly external displays over secondary internal screens
        for (Display d : nonDefaultDisplays) {
            if (!isBuiltInDisplay(d)) {
                LimeLog.info("External display detected: " + d.getDisplayId());
                return d;
            }
        }

        // Fallback: return the first non-default display
        return nonDefaultDisplays.get(0);
    }

    public static Intent createStartIntent(Activity parent, NvApp app, ComputerDetails computer,
                                           ComputerManagerService.ComputerManagerBinder managerBinder,
                                           boolean withVDisplay) {
        Intent gameIntent = null;
        PreferenceConfiguration prefConfig = PreferenceConfiguration.readPreferences(parent);
        DisplayManager displayManager = (DisplayManager) parent.getSystemService(Context.DISPLAY_SERVICE);
        Display secondaryDisplay = getSecondaryDisplay(parent);
        boolean enableFullEx = prefConfig.enableFullExDisplay && secondaryDisplay != null;
        boolean isDualInternal = enableFullEx && isDualInternalScreenDevice(displayManager,
            displayManager.getDisplay(Display.DEFAULT_DISPLAY));

        // Try to add secondary DisplayContext if supported and connected
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && enableFullEx) {
            Context displayContext;
            if (isDualInternal) {
                // Dual internal screens: use the larger display for Game context
                Display defaultDisplay = displayManager.getDisplay(Display.DEFAULT_DISPLAY);
                Display largerDisplay = getLargerDisplay(defaultDisplay, secondaryDisplay);
                displayContext = parent.createDisplayContext(largerDisplay);
            } else {
                displayContext = parent.createDisplayContext(secondaryDisplay);
            }
            gameIntent = new Intent(displayContext, Game.class);
            gameIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        }
        if(gameIntent == null) gameIntent = new Intent(parent, Game.class);
        gameIntent.putExtra(Game.EXTRA_HOST, computer.activeAddress.address);
        gameIntent.putExtra(Game.EXTRA_PORT, computer.activeAddress.port);
        gameIntent.putExtra(Game.EXTRA_HTTPS_PORT, computer.httpsPort);
        gameIntent.putExtra(Game.EXTRA_APP_NAME, app.getAppName());
        gameIntent.putExtra(Game.EXTRA_APP_UUID, app.getAppUUID());
        gameIntent.putExtra(Game.EXTRA_APP_ID, app.getAppId());
        gameIntent.putExtra(Game.EXTRA_APP_HDR, app.isHdrSupported());
        gameIntent.putExtra(Game.EXTRA_UNIQUEID, managerBinder.getUniqueId());
        gameIntent.putExtra(Game.EXTRA_PC_UUID, computer.uuid);
        gameIntent.putExtra(Game.EXTRA_PC_NAME, computer.name);
        gameIntent.putExtra(Game.EXTRA_VDISPLAY, withVDisplay);
        gameIntent.putExtra(Game.EXTRA_SERVER_COMMANDS, (ArrayList<String>) computer.serverCommands);

        try {
            if (computer.serverCert != null) {
                gameIntent.putExtra(Game.EXTRA_SERVER_CERT, computer.serverCert.getEncoded());
            }
        } catch (CertificateEncodingException e) {
            e.printStackTrace();
        }

        if (enableFullEx) {
            if (isDualInternal) {
                // Dual internal screens (e.g. AYN Thor): stream on larger display, controls on smaller
                Display defaultDisplay = displayManager.getDisplay(Display.DEFAULT_DISPLAY);
                Display largerDisplay = getLargerDisplay(defaultDisplay, secondaryDisplay);
                Display smallerDisplay = (largerDisplay == defaultDisplay) ? secondaryDisplay : defaultDisplay;

                gameIntent.putExtra(Game.EXTRA_DISPLAY_ID, largerDisplay.getDisplayId());
                Intent touchpadIntent = new Intent(parent, ExternalDisplayControlActivity.class);
                touchpadIntent.putExtra(ExternalDisplayControlActivity.EXTRA_LAUNCH_INTENT, gameIntent);
                // Signal to doStart() to launch touchpad on the smaller display
                touchpadIntent.putExtra(ExternalDisplayControlActivity.EXTRA_LAUNCH_DISPLAY_ID, smallerDisplay.getDisplayId());
                return touchpadIntent;
            } else {
                // True external display: original behavior (stream on external, controls on default)
                int secondaryDisplayId = secondaryDisplay.getDisplayId();
                gameIntent.putExtra(Game.EXTRA_DISPLAY_ID, secondaryDisplayId);
                Intent touchpadIntent = new Intent(parent, ExternalDisplayControlActivity.class);
                touchpadIntent.putExtra(ExternalDisplayControlActivity.EXTRA_LAUNCH_INTENT, gameIntent);
                return touchpadIntent;
            }
        }

        return gameIntent;
    }

    @RequiresApi(api = Build.VERSION_CODES.O)
    public static void doStart(
            Activity parent,
            NvApp app,
            ComputerDetails computer,
            ComputerManagerService.ComputerManagerBinder managerBinder,
            boolean withVDisplay
    ) {
        if (computer.state == ComputerDetails.State.OFFLINE || computer.activeAddress == null) {
            Toast.makeText(parent, parent.getString(R.string.pair_pc_offline), Toast.LENGTH_SHORT).show();
            return;
        }

        Intent intent = createStartIntent(parent, app, computer, managerBinder, withVDisplay);

        // For dual internal screen devices, launch the touchpad on the smaller display
        int launchDisplayId = intent.getIntExtra(ExternalDisplayControlActivity.EXTRA_LAUNCH_DISPLAY_ID, -1);
        if (launchDisplayId != -1 && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            android.app.ActivityOptions options = android.app.ActivityOptions.makeBasic();
            options.setLaunchDisplayId(launchDisplayId);
            parent.startActivity(intent, options.toBundle());
        } else {
            parent.startActivity(intent);
        }
    }

    public static void doNetworkTest(final Activity parent) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                SpinnerDialog spinnerDialog = SpinnerDialog.displayDialog(parent,
                        parent.getResources().getString(R.string.nettest_title_waiting),
                        parent.getResources().getString(R.string.nettest_text_waiting),
                        false);

                int ret = MoonBridge.testClientConnectivity(CONNECTION_TEST_SERVER, 443, MoonBridge.ML_PORT_FLAG_ALL);
                spinnerDialog.dismiss();

                String dialogSummary;
                if (ret == MoonBridge.ML_TEST_RESULT_INCONCLUSIVE) {
                    dialogSummary = parent.getResources().getString(R.string.nettest_text_inconclusive);
                }
                else if (ret == 0) {
                    dialogSummary = parent.getResources().getString(R.string.nettest_text_success);
                }
                else {
                    dialogSummary = parent.getResources().getString(R.string.nettest_text_failure);
                    dialogSummary += MoonBridge.stringifyPortFlags(ret, "\n");
                }

                Dialog.displayDialog(parent,
                        parent.getResources().getString(R.string.nettest_title_done),
                        dialogSummary,
                        false);
            }
        }).start();
    }

    public static void doQuit(final Activity parent,
                              final NvHTTP httpConn,
                              final String appName,
                              final Runnable onComplete,
                              final Runnable onFail
    ) {
        parent.runOnUiThread(() -> Toast.makeText(parent, parent.getResources().getString(R.string.applist_quit_app) + " " + appName + "...", Toast.LENGTH_SHORT).show());
        new Thread(new Runnable() {
            @Override
            public void run() {
                String message;
                boolean failed = false;
                try {
                    if (httpConn.quitApp()) {
                        message = parent.getResources().getString(R.string.applist_quit_success) + " " + appName;
                    } else {
                        message = parent.getResources().getString(R.string.applist_quit_fail) + " " + appName;
                    }
                } catch (HostHttpResponseException e) {
                    failed = true;
                    if (e.getErrorCode() == 599) {
                        message = "This session wasn't started by this device," +
                                " so it cannot be quit. End streaming on the original " +
                                "device or the PC itself. (Error code: "+e.getErrorCode()+")";
                    }
                    else {
                        message = e.getMessage();
                    }
                } catch (UnknownHostException e) {
                    failed = true;
                    message = parent.getResources().getString(R.string.error_unknown_host);
                } catch (FileNotFoundException e) {
                    failed = true;
                    message = parent.getResources().getString(R.string.error_404);
                } catch (IOException | XmlPullParserException e) {
                    failed = true;
                    message = e.getMessage();
                    e.printStackTrace();
                } finally {
                    if (failed) {
                        if (onFail != null) {
                            onFail.run();
                        }
                    } else {
                        if (onComplete != null) {
                            onComplete.run();
                        }
                    }
                }

                final String toastMessage = message;
                parent.runOnUiThread(() -> Toast.makeText(parent, toastMessage, Toast.LENGTH_LONG).show());
            }
        }).start();

    }

    public static void doQuit(final Activity parent,
                              final ComputerDetails computer,
                              final NvApp app,
                              final ComputerManagerService.ComputerManagerBinder managerBinder,
                              final Runnable onComplete
    ) {
        try {
            NvHTTP httpConn = new NvHTTP(
                    ServerHelper.getCurrentAddressFromComputer(computer),
                    computer.httpsPort,
                    managerBinder.getUniqueId(),
                    computer.serverCert,
                    PlatformBinding.getCryptoProvider(parent)
            );
            doQuit(
                    parent,
                    httpConn,
                    app.getAppName(),
                    onComplete,
                    null
            );
        } catch (Exception e) {
            e.printStackTrace();

            final String toastMessage = e.getMessage();
            parent.runOnUiThread(() -> Toast.makeText(parent, toastMessage, Toast.LENGTH_LONG).show());
        }
    }
}
