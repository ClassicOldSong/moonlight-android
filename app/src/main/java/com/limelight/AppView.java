package com.limelight;

import java.io.IOException;
import java.io.StringReader;
import java.util.HashSet;
import java.util.List;

import com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton;
import com.limelight.computers.ComputerManagerListener;
import com.limelight.computers.ComputerManagerService;
import com.limelight.grid.AppGridAdapter;
import com.limelight.nvstream.http.ComputerDetails;
import com.limelight.nvstream.http.NvApp;
import com.limelight.nvstream.http.NvHTTP;
import com.limelight.nvstream.http.PairingManager;
import com.limelight.preferences.PreferenceConfiguration;
import com.limelight.profiles.ProfilesManager;
import com.limelight.ui.AdapterFragment;
import com.limelight.ui.AdapterFragmentCallbacks;
import com.limelight.utils.CacheHelper;
import com.limelight.utils.Dialog;
import com.limelight.utils.ServerHelper;
import com.limelight.utils.ShortcutHelper;
import com.limelight.utils.SpinnerDialog;
import com.limelight.utils.UiHelper;

import android.app.Activity;
import android.app.Service;
import android.content.ComponentName;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.drawable.BitmapDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.view.ContextMenu;
import android.view.Menu;
import android.view.MenuItem;
import android.view.SubMenu;
import android.view.View;
import android.view.ContextMenu.ContextMenuInfo;
import android.widget.AbsListView;
import android.widget.AdapterView;
import android.widget.AdapterView.OnItemClickListener;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.AdapterView.AdapterContextMenuInfo;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import org.xmlpull.v1.XmlPullParserException;

public class AppView extends AppCompatActivity implements AdapterFragmentCallbacks {
    private AppGridAdapter appGridAdapter;
    private String uuidString;
    private String computerUniqueId;
    private ShortcutHelper shortcutHelper;

    private ComputerDetails computer;
    private ComputerManagerService.ApplistPoller poller;
    private SpinnerDialog blockingLoadSpinner;
    private String lastRawApplist;
    private int lastRunningAppId;
    private boolean suspendGridUpdates;
    private boolean inForeground;
    private boolean showHiddenApps;
    private HashSet<Integer> hiddenAppIds = new HashSet<>();

    private PreferenceConfiguration prefConfig;

    // Store the currently selected app for context menu operations (especially OSC profile submenu)
    private AppObject currentContextMenuApp;

    private final static int START_OR_RESUME_ID = 1;
    private final static int QUIT_ID = 2;
    private final static int START_WITH_QUIT = 4;
    private final static int VIEW_DETAILS_ID = 5;
    private final static int CREATE_SHORTCUT_ID = 6;
    private final static int EXPORT_LAUNCHER_FILE_ID = 7;
    private final static int HIDE_APP_ID = 8;
    private final static int STREAM_SETTINGS_ID = 9;
    private final static int START_WITH_VDISPLAY = 20;
    private final static int START_WITH_QUIT_VDISPLAY = 21;
    private final static int OSC_PROFILE_SUBMENU_ID = 100;
    private final static int OSC_PROFILE_BASE_ID = 1000; // Base ID for OSC profiles

    public final static String HIDDEN_APPS_PREF_FILENAME = "HiddenApps";
    public final static String SORT_MODE_PREF_FILENAME = "AppSortMode";

    public final static String NAME_EXTRA = "Name";
    public final static String UUID_EXTRA = "UUID";
    public final static String NEW_PAIR_EXTRA = "NewPair";
    public final static String SHOW_HIDDEN_APPS_EXTRA = "ShowHiddenApps";

    private ComputerManagerService.ComputerManagerBinder managerBinder;
    private final ServiceConnection serviceConnection = new ServiceConnection() {
        public void onServiceConnected(ComponentName className, IBinder binder) {
            final ComputerManagerService.ComputerManagerBinder localBinder =
                    ((ComputerManagerService.ComputerManagerBinder)binder);

            // Wait in a separate thread to avoid stalling the UI
            new Thread() {
                @Override
                public void run() {
                    // Wait for the binder to be ready
                    localBinder.waitForReady();

                    // Get the computer object
                    computer = localBinder.getComputer(uuidString);
                    if (computer == null) {
                        finish();
                        return;
                    }

                    // Add a launcher shortcut for this PC (forced, since this is user interaction)
                    shortcutHelper.createAppViewShortcut(computer, true, getIntent().getBooleanExtra(NEW_PAIR_EXTRA, false));
                    shortcutHelper.reportComputerShortcutUsed(computer);

                    try {
                        computerUniqueId = localBinder.getUniqueId();
                        appGridAdapter = new AppGridAdapter(AppView.this,
                                PreferenceConfiguration.readPreferences(AppView.this),
                                computer, computerUniqueId,
                                showHiddenApps);
                    } catch (Exception e) {
                        e.printStackTrace();
                        finish();
                        return;
                    }

                    appGridAdapter.updateHiddenApps(hiddenAppIds, true);

                    // Restore saved sort mode
                    appGridAdapter.setSortMode(getSavedSortMode());

                    // Now make the binder visible. We must do this after appGridAdapter
                    // is set to prevent us from reaching updateUiWithServerinfo() and
                    // touching the appGridAdapter prior to initialization.
                    managerBinder = localBinder;

                    // Load the app grid with cached data (if possible).
                    // This must be done _before_ startComputerUpdates()
                    // so the initial serverinfo response can update the running
                    // icon.
                    populateAppGridWithCache();

                    // Start updates
                    startComputerUpdates();

                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            if (isFinishing() || isChangingConfigurations()) {
                                return;
                            }

                            // Despite my best efforts to catch all conditions that could
                            // cause the activity to be destroyed when we try to commit
                            // I haven't been able to, so we have this try-catch block.
                            try {
                                getFragmentManager().beginTransaction()
                                        .replace(R.id.appFragmentContainer, new AdapterFragment())
                                        .commitAllowingStateLoss();
                            } catch (IllegalStateException e) {
                                e.printStackTrace();
                            }
                        }
                    });
                }
            }.start();
        }

        public void onServiceDisconnected(ComponentName className) {
            managerBinder = null;
        }
    };

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);

        this.prefConfig = PreferenceConfiguration.readPreferences(this);

        // If appGridAdapter is initialized, let it know about the configuration change.
        // If not, it will pick it up when it initializes.
        if (appGridAdapter != null) {
            // Update the app grid adapter to create grid items with the correct layout
            appGridAdapter.updateLayoutWithPreferences(this, this.prefConfig);

            try {
                // Reinflate the app grid itself to pick up the layout change
                getFragmentManager().beginTransaction()
                        .replace(R.id.appFragmentContainer, new AdapterFragment())
                        .commitAllowingStateLoss();
            } catch (IllegalStateException e) {
                e.printStackTrace();
            }
        }
    }

    private void startComputerUpdates() {
        // Don't start polling if we're not bound or in the foreground
        if (managerBinder == null || !inForeground) {
            return;
        }

        managerBinder.startPolling(new ComputerManagerListener() {
            @Override
            public void notifyComputerUpdated(final ComputerDetails details) {
                // Do nothing if updates are suspended
                if (suspendGridUpdates) {
                    return;
                }

                // Don't care about other computers
                if (!details.uuid.equalsIgnoreCase(uuidString)) {
                    return;
                }

                if (details.state == ComputerDetails.State.OFFLINE) {
                    // The PC is unreachable now
                    AppView.this.runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            // Display a toast to the user and quit the activity
                            Toast.makeText(AppView.this, R.string.lost_connection, Toast.LENGTH_SHORT).show();
                            finish();
                        }
                    });

                    return;
                }

                // Close immediately if the PC is no longer paired
                if (details.state == ComputerDetails.State.ONLINE && details.pairState != PairingManager.PairState.PAIRED) {
                    AppView.this.runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            // Disable shortcuts referencing this PC for now
                            shortcutHelper.disableComputerShortcut(details,
                                    getResources().getString(R.string.scut_not_paired));

                            // Display a toast to the user and quit the activity
                            Toast.makeText(AppView.this, R.string.scut_not_paired, Toast.LENGTH_SHORT).show();
                            finish();
                        }
                    });

                    return;
                }

                // App list is the same or empty
                if (details.rawAppList == null || details.rawAppList.equals(lastRawApplist)) {

                    // Let's check if the running app ID changed
                    if (details.runningGameId != lastRunningAppId) {
                        // Update the currently running game using the app ID
                        lastRunningAppId = details.runningGameId;
                        updateUiWithServerinfo(details);
                    }

                    return;
                }

                lastRunningAppId = details.runningGameId;
                lastRawApplist = details.rawAppList;

                try {
                    updateUiWithAppList(NvHTTP.getAppListByReader(new StringReader(details.rawAppList)));
                    updateUiWithServerinfo(details);

                    if (blockingLoadSpinner != null) {
                        blockingLoadSpinner.dismiss();
                        blockingLoadSpinner = null;
                    }
                } catch (XmlPullParserException | IOException e) {
                    e.printStackTrace();
                }
            }
        });

        if (poller == null) {
            poller = managerBinder.createAppListPoller(computer);
        }
        poller.start();
    }

    private void stopComputerUpdates() {
        if (poller != null) {
            poller.stop();
        }

        if (managerBinder != null) {
            managerBinder.stopPolling();
        }

        if (appGridAdapter != null) {
            appGridAdapter.cancelQueuedOperations();
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Assume we're in the foreground when created to avoid a race
        // between binding to CMS and onResume()
        inForeground = true;

        shortcutHelper = new ShortcutHelper(this);

        UiHelper.setLocale(this);

        // Load OSC profiles
        com.limelight.binding.input.virtual_controller.OscProfilesManager.getInstance().load(this);

        // Initialize app-specific OSC profile manager
        com.limelight.binding.input.virtual_controller.AppOscProfileManager.getInstance().initialize(this);

        // Initialize app-specific stream profile manager (for per-game resolution settings)
        com.limelight.profiles.AppStreamProfileManager.getInstance().initialize(this);

        setContentView(R.layout.activity_app_view);

        // Allow floating expanded PiP overlays while browsing apps
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            setShouldDockBigOverlays(false);
        }

        UiHelper.notifyNewRootView(this);

        // Setup the profiles button
        findViewById(R.id.profilesButton)
            .setOnClickListener(v -> startActivity(new Intent(this, ProfilesActivity.class)));

        // Setup the sort button
        ImageView sortButton = findViewById(R.id.sortButton);
        sortButton.setOnClickListener(v -> cycleSortMode());

        showHiddenApps = getIntent().getBooleanExtra(SHOW_HIDDEN_APPS_EXTRA, false);
        uuidString = getIntent().getStringExtra(UUID_EXTRA);

        SharedPreferences hiddenAppsPrefs = getSharedPreferences(HIDDEN_APPS_PREF_FILENAME, MODE_PRIVATE);
        for (String hiddenAppIdStr : hiddenAppsPrefs.getStringSet(uuidString, new HashSet<String>())) {
            hiddenAppIds.add(Integer.parseInt(hiddenAppIdStr));
        }

        String computerName = getIntent().getStringExtra(NAME_EXTRA);

        TextView label = findViewById(R.id.appListText);
        setTitle(computerName);
        label.setText(computerName);

        this.prefConfig = PreferenceConfiguration.readPreferences(this);

        // Bind to the computer manager service
        bindService(new Intent(this, ComputerManagerService.class), serviceConnection,
                Service.BIND_AUTO_CREATE);
    }

    private void updateHiddenApps(boolean hideImmediately) {
        HashSet<String> hiddenAppIdStringSet = new HashSet<>();

        for (Integer hiddenAppId : hiddenAppIds) {
            hiddenAppIdStringSet.add(hiddenAppId.toString());
        }

        getSharedPreferences(HIDDEN_APPS_PREF_FILENAME, MODE_PRIVATE)
                .edit()
                .putStringSet(uuidString, hiddenAppIdStringSet)
                .apply();

        appGridAdapter.updateHiddenApps(hiddenAppIds, hideImmediately);
    }

    private AppGridAdapter.SortMode getSavedSortMode() {
        SharedPreferences sortModePrefs = getSharedPreferences(SORT_MODE_PREF_FILENAME, MODE_PRIVATE);
        String sortModeStr = sortModePrefs.getString(uuidString, AppGridAdapter.SortMode.ALPHABETICAL_ASC.name());
        try {
            return AppGridAdapter.SortMode.valueOf(sortModeStr);
        } catch (IllegalArgumentException e) {
            return AppGridAdapter.SortMode.ALPHABETICAL_ASC;
        }
    }

    private void saveSortMode(AppGridAdapter.SortMode mode) {
        getSharedPreferences(SORT_MODE_PREF_FILENAME, MODE_PRIVATE)
                .edit()
                .putString(uuidString, mode.name())
                .apply();
    }

    private void recordLastPlayed(int appId) {
        SharedPreferences prefs = getSharedPreferences("LastPlayed_" + computerUniqueId, MODE_PRIVATE);
        prefs.edit()
                .putLong("app_" + appId, System.currentTimeMillis())
                .apply();
    }

    private void cycleSortMode() {
        if (appGridAdapter == null) {
            return;
        }

        AppGridAdapter.SortMode currentMode = appGridAdapter.getSortMode();
        AppGridAdapter.SortMode nextMode;

        // Cycle through all 3 sort modes
        switch (currentMode) {
            case LAST_PLAYED:
                nextMode = AppGridAdapter.SortMode.ALPHABETICAL_ASC;
                break;
            case ALPHABETICAL_ASC:
                nextMode = AppGridAdapter.SortMode.ALPHABETICAL_DESC;
                break;
            case ALPHABETICAL_DESC:
            default:
                nextMode = AppGridAdapter.SortMode.LAST_PLAYED;
                break;
        }

        appGridAdapter.setSortMode(nextMode);
        saveSortMode(nextMode);

        // Show toast with current sort mode
        String message;
        switch (nextMode) {
            case LAST_PLAYED:
                message = getString(R.string.sort_mode_last_played);
                break;
            case ALPHABETICAL_ASC:
                message = getString(R.string.sort_mode_alphabetical_asc);
                break;
            case ALPHABETICAL_DESC:
                message = getString(R.string.sort_mode_alphabetical_desc);
                break;
            default:
                message = "";
                break;
        }
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
    }

    private void populateAppGridWithCache() {
        try {
            // Try to load from cache
            lastRawApplist = CacheHelper.readInputStreamToString(CacheHelper.openCacheFileForInput(getCacheDir(), "applist", uuidString));
            List<NvApp> applist = NvHTTP.getAppListByReader(new StringReader(lastRawApplist));
            updateUiWithAppList(applist);
            LimeLog.info("Loaded applist from cache");
        } catch (IOException | XmlPullParserException e) {
            if (lastRawApplist != null) {
                LimeLog.warning("Saved applist corrupted: "+lastRawApplist);
                e.printStackTrace();
            }
            LimeLog.info("Loading applist from the network");
            // We'll need to load from the network
            loadAppsBlocking();
        }
    }

    private void loadAppsBlocking() {
        blockingLoadSpinner = SpinnerDialog.displayDialog(this, getResources().getString(R.string.applist_refresh_title),
                getResources().getString(R.string.applist_refresh_msg), true);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();

        SpinnerDialog.closeDialogs(this);
        Dialog.closeDialogs();

        if (managerBinder != null) {
            unbindService(serviceConnection);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();

        // Display a decoder crash notification if we've returned after a crash
        UiHelper.showDecoderCrashDialog(this);

        inForeground = true;
        startComputerUpdates();

        ExtendedFloatingActionButton profilesButton = findViewById(R.id.profilesButton);
        // User report Samsung and Xiaomi devices have this problem
        // Why just these two brands have the most problems?
        if (profilesButton == null) {
            return;
        }
        String activeProfileName = ProfilesManager.getInstance().getActiveName();
        if (activeProfileName.isEmpty()) {
            profilesButton.shrink();
        } else {
            profilesButton.setText(activeProfileName);
            profilesButton.extend();
        }
    }

    @Override
    protected void onPause() {
        super.onPause();

        inForeground = false;
        stopComputerUpdates();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        if (requestCode == ShortcutHelper.REQUEST_CODE_EXPORT_ART_FILE) {
            if (resultCode == Activity.RESULT_OK && data != null && data.getData() != null) {
                Uri uri = data.getData();
                ShortcutHelper.writeArtFileToUri(this, uri);
            } else {
                // Clear the content if the user cancelled or if there was an error before this point
                ShortcutHelper.artFileContentToExport = null;
                // Show "File export cancelled." toast only if the user explicitly cancelled.
                if (resultCode == Activity.RESULT_CANCELED) {
                    Toast.makeText(this, R.string.file_export_cancelled, Toast.LENGTH_SHORT).show();
                }
            }
        }
    }

    @Override
    public void onCreateContextMenu(ContextMenu menu, View v, ContextMenuInfo menuInfo) {
        super.onCreateContextMenu(menu, v, menuInfo);

        AdapterContextMenuInfo info = (AdapterContextMenuInfo) menuInfo;
        AppObject selectedApp = (AppObject) appGridAdapter.getItem(info.position);

        // Store the current app for OSC profile submenu operations
        currentContextMenuApp = selectedApp;

        menu.setHeaderTitle(selectedApp.app.getAppName());

        if (lastRunningAppId == 0) {
            if (prefConfig.useVirtualDisplay) {
                menu.add(Menu.NONE, START_OR_RESUME_ID, 1, getResources().getString(R.string.applist_menu_start_primarydisplay));
            } else {
                menu.add(Menu.NONE, START_WITH_VDISPLAY, 1, getResources().getString(R.string.applist_menu_start_vdisplay));
            }
        } else {
            if (lastRunningAppId == selectedApp.app.getAppId()) {
                menu.add(Menu.NONE, START_OR_RESUME_ID, 1, getResources().getString(R.string.applist_menu_resume));
                menu.add(Menu.NONE, QUIT_ID, 2, getResources().getString(R.string.applist_menu_quit));
            }
            else {
                if (prefConfig.useVirtualDisplay) {
                    menu.add(Menu.NONE, START_WITH_QUIT_VDISPLAY, 1, getResources().getString(R.string.applist_menu_quit_and_start));
                    menu.add(Menu.NONE, START_WITH_QUIT, 2, getResources().getString(R.string.applist_menu_quit_and_start_primarydisplay));
                } else{
                    menu.add(Menu.NONE, START_WITH_QUIT, 1, getResources().getString(R.string.applist_menu_quit_and_start));
                    menu.add(Menu.NONE, START_WITH_QUIT_VDISPLAY, 2, getResources().getString(R.string.applist_menu_quit_and_start_vdisplay));
                }
            }
        }

        // Only show the hide checkbox if this is not the currently running app or it's already hidden
        if (lastRunningAppId != selectedApp.app.getAppId() || selectedApp.isHidden) {
            MenuItem hideAppItem = menu.add(Menu.NONE, HIDE_APP_ID, 3, getResources().getString(R.string.applist_menu_hide_app));
            hideAppItem.setCheckable(true);
            hideAppItem.setChecked(selectedApp.isHidden);
        }

        menu.add(Menu.NONE, VIEW_DETAILS_ID, 4, getResources().getString(R.string.applist_menu_details));

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            // Only add an option to create shortcut if box art is loaded
            // and when we're in grid-mode (not list-mode).
            ImageView appImageView = info.targetView.findViewById(R.id.grid_image);
            if (appImageView != null) {
                // We have a grid ImageView, so we must be in grid-mode
                BitmapDrawable drawable = (BitmapDrawable)appImageView.getDrawable();
                if (drawable != null && drawable.getBitmap() != null) {
                    // We have a bitmap loaded too
                    menu.add(Menu.NONE, CREATE_SHORTCUT_ID, 5, getResources().getString(R.string.applist_menu_scut));
                }
            }
        }

        menu.add(Menu.NONE, EXPORT_LAUNCHER_FILE_ID, 6, getResources().getString(R.string.applist_menu_export_launcher));

        // Add Stream Settings menu item
        menu.add(Menu.NONE, STREAM_SETTINGS_ID, 7, getResources().getString(R.string.stream_settings_menu));

        // Add OSC Profile submenu
        SubMenu oscProfileSubmenu = menu.addSubMenu(Menu.NONE, OSC_PROFILE_SUBMENU_ID, 8, getResources().getString(R.string.osc_profile_menu));
        addOscProfilesToSubmenu(oscProfileSubmenu);
    }

    private void addOscProfilesToSubmenu(SubMenu submenu) {
        com.limelight.binding.input.virtual_controller.OscProfilesManager manager =
                com.limelight.binding.input.virtual_controller.OscProfilesManager.getInstance();
        java.util.List<com.limelight.binding.input.virtual_controller.OscProfile> profiles = manager.getProfiles();

        // Get the default profile for the current app
        java.util.UUID defaultProfileForApp = null;
        if (currentContextMenuApp != null && currentContextMenuApp.app.getAppUUID() != null) {
            com.limelight.binding.input.virtual_controller.AppOscProfileManager appOscManager =
                    com.limelight.binding.input.virtual_controller.AppOscProfileManager.getInstance();
            defaultProfileForApp = appOscManager.getDefaultProfileForApp(currentContextMenuApp.app.getAppUUID());
        }

        int index = 0;
        for (com.limelight.binding.input.virtual_controller.OscProfile profile : profiles) {
            String menuText = profile.getName();

            // Mark which profile is the default for this app
            if (defaultProfileForApp != null && profile.getUuid().equals(defaultProfileForApp)) {
                menuText += " ✓"; // Show checkmark for default profile
            }

            MenuItem item = submenu.add(Menu.NONE, OSC_PROFILE_BASE_ID + index, index, menuText);
            index++;
        }
    }

    /**
     * Show dialog for configuring per-game stream settings (resolution, FPS)
     */
    private void showStreamSettingsDialog(final AppObject appObject) {
        final String appUUID = appObject.app.getAppUUID();
        final String appName = appObject.app.getAppName();
        
        if (appUUID == null || appUUID.isEmpty()) {
            Toast.makeText(this, "Cannot configure stream settings: App UUID is missing", Toast.LENGTH_SHORT).show();
            return;
        }
        
        // Ensure manager is initialized
        com.limelight.profiles.AppStreamProfileManager manager = 
                com.limelight.profiles.AppStreamProfileManager.getInstance();
        manager.initialize(this);
        
        com.limelight.profiles.GameStreamProfile existingProfile = manager.getProfileForApp(appUUID);
        
        // Build the dialog
        android.app.AlertDialog.Builder builder = new android.app.AlertDialog.Builder(this);
        builder.setTitle(getString(R.string.stream_settings_dialog_title, appName));
        
        // Create a custom layout for the dialog
        android.widget.LinearLayout layout = new android.widget.LinearLayout(this);
        layout.setOrientation(android.widget.LinearLayout.VERTICAL);
        int padding = (int) (16 * getResources().getDisplayMetrics().density);
        layout.setPadding(padding, padding, padding, padding);
        
        // Resolution section
        android.widget.TextView resLabel = new android.widget.TextView(this);
        resLabel.setText(R.string.stream_settings_resolution);
        resLabel.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 16);
        resLabel.setPadding(0, 0, 0, (int)(8 * getResources().getDisplayMetrics().density));
        layout.addView(resLabel);
        
        // Resolution spinner
        android.widget.Spinner resSpinner = new android.widget.Spinner(this);
        String[] resOptions = {
            getString(R.string.stream_settings_resolution_default),
            "1280x720",
            "1920x1080",
            "2560x1440",
            "3840x2160",
            getString(R.string.stream_settings_resolution_native),
            getString(R.string.stream_settings_resolution_custom)
        };
        android.widget.ArrayAdapter<String> resAdapter = new android.widget.ArrayAdapter<>(
            this, android.R.layout.simple_spinner_item, resOptions);
        resAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        resSpinner.setAdapter(resAdapter);
        layout.addView(resSpinner);
        
        // Custom resolution input
        android.widget.EditText customResInput = new android.widget.EditText(this);
        customResInput.setHint(R.string.stream_settings_custom_resolution_hint);
        customResInput.setInputType(android.text.InputType.TYPE_CLASS_TEXT);
        customResInput.setVisibility(View.GONE);
        layout.addView(customResInput);
        
        // FPS section
        android.widget.TextView fpsLabel = new android.widget.TextView(this);
        fpsLabel.setText(R.string.stream_settings_fps);
        fpsLabel.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 16);
        fpsLabel.setPadding(0, (int)(16 * getResources().getDisplayMetrics().density), 0, (int)(8 * getResources().getDisplayMetrics().density));
        layout.addView(fpsLabel);
        
        // FPS spinner
        android.widget.Spinner fpsSpinner = new android.widget.Spinner(this);
        String[] fpsOptions = {
            getString(R.string.stream_settings_fps_default),
            "30",
            "60",
            "90",
            "120",
            getString(R.string.stream_settings_fps_custom)
        };
        android.widget.ArrayAdapter<String> fpsAdapter = new android.widget.ArrayAdapter<>(
            this, android.R.layout.simple_spinner_item, fpsOptions);
        fpsAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        fpsSpinner.setAdapter(fpsAdapter);
        layout.addView(fpsSpinner);
        
        // Custom FPS input
        android.widget.EditText customFpsInput = new android.widget.EditText(this);
        customFpsInput.setHint(R.string.stream_settings_custom_fps_hint);
        customFpsInput.setInputType(android.text.InputType.TYPE_CLASS_NUMBER | android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL);
        customFpsInput.setVisibility(View.GONE);
        layout.addView(customFpsInput);
        
        // Set up spinner listeners to show/hide custom input fields
        resSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                // Show custom input when "Custom" is selected (last option)
                customResInput.setVisibility(position == resOptions.length - 1 ? View.VISIBLE : View.GONE);
            }
            @Override
            public void onNothingSelected(AdapterView<?> parent) {}
        });
        
        fpsSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                // Show custom input when "Custom" is selected (last option)
                customFpsInput.setVisibility(position == fpsOptions.length - 1 ? View.VISIBLE : View.GONE);
            }
            @Override
            public void onNothingSelected(AdapterView<?> parent) {}
        });
        
        // Pre-populate with existing profile if present
        if (existingProfile != null && existingProfile.hasAnyOverride()) {
            // Set resolution
            if (existingProfile.hasResolutionOverride()) {
                if (existingProfile.isNativeResolution()) {
                    resSpinner.setSelection(5); // Native
                } else {
                    String customRes = existingProfile.getCustomResolution();
                    if (customRes != null) {
                        // Check if it matches a preset
                        boolean found = false;
                        for (int i = 1; i < resOptions.length - 2; i++) {
                            if (resOptions[i].equalsIgnoreCase(customRes)) {
                                resSpinner.setSelection(i);
                                found = true;
                                break;
                            }
                        }
                        if (!found) {
                            resSpinner.setSelection(resOptions.length - 1); // Custom
                            customResInput.setText(customRes);
                            customResInput.setVisibility(View.VISIBLE);
                        }
                    }
                }
            }
            
            // Set FPS
            if (existingProfile.hasFpsOverride()) {
                Float fps = existingProfile.getFps();
                String customRefresh = existingProfile.getCustomRefreshRate();
                String fpsStr = customRefresh != null ? customRefresh : (fps != null ? String.valueOf(fps.intValue()) : null);
                
                if (fpsStr != null) {
                    boolean found = false;
                    for (int i = 1; i < fpsOptions.length - 1; i++) {
                        if (fpsOptions[i].equals(fpsStr)) {
                            fpsSpinner.setSelection(i);
                            found = true;
                            break;
                        }
                    }
                    if (!found) {
                        fpsSpinner.setSelection(fpsOptions.length - 1); // Custom
                        customFpsInput.setText(fpsStr);
                        customFpsInput.setVisibility(View.VISIBLE);
                    }
                }
            }
        }
        
        builder.setView(layout);
        
        // Save button
        builder.setPositiveButton(android.R.string.ok, (dialog, which) -> {
            int resSelection = resSpinner.getSelectedItemPosition();
            int fpsSelection = fpsSpinner.getSelectedItemPosition();
            
            LimeLog.info("StreamSettings: Save clicked - resSelection=" + resSelection + ", fpsSelection=" + fpsSelection);
            LimeLog.info("StreamSettings: resOptions.length=" + resOptions.length + ", fpsOptions.length=" + fpsOptions.length);
            
            // Check if everything is set to default
            if (resSelection == 0 && fpsSelection == 0) {
                // Clear the profile
                LimeLog.info("StreamSettings: Both defaults selected, clearing profile");
                manager.removeProfileForApp(appUUID);
                Toast.makeText(this, getString(R.string.stream_settings_cleared, appName), Toast.LENGTH_SHORT).show();
                return;
            }
            
            // Create or update profile
            com.limelight.profiles.GameStreamProfile profile = new com.limelight.profiles.GameStreamProfile(appUUID, appName);
            
            // Process resolution
            if (resSelection > 0) {
                LimeLog.info("StreamSettings: Processing resolution, selection=" + resSelection);
                if (resSelection == 5) {
                    // Native
                    LimeLog.info("StreamSettings: Setting Native resolution");
                    profile.setResolution("Native");
                } else if (resSelection == resOptions.length - 1) {
                    // Custom
                    String customRes = customResInput.getText().toString().trim();
                    LimeLog.info("StreamSettings: Custom resolution selected, value='" + customRes + "'");
                    if (!customRes.isEmpty()) {
                        // Validate format
                        if (!customRes.matches("\\d+x\\d+")) {
                            LimeLog.warning("StreamSettings: Invalid resolution format: " + customRes);
                            Toast.makeText(this, R.string.stream_settings_invalid_resolution, Toast.LENGTH_SHORT).show();
                            return;
                        }
                        profile.setCustomResolution(customRes);
                        LimeLog.info("StreamSettings: Set custom resolution to " + customRes);
                    } else {
                        LimeLog.warning("StreamSettings: Custom resolution selected but input is empty");
                    }
                } else {
                    // Preset resolution
                    LimeLog.info("StreamSettings: Preset resolution selected: " + resOptions[resSelection]);
                    profile.setCustomResolution(resOptions[resSelection]);
                }
            }
            
            // Process FPS
            if (fpsSelection > 0) {
                if (fpsSelection == fpsOptions.length - 1) {
                    // Custom
                    String customFps = customFpsInput.getText().toString().trim();
                    if (!customFps.isEmpty()) {
                        try {
                            float fps = Float.parseFloat(customFps);
                            if (fps > 0) {
                                profile.setFps(fps);
                            } else {
                                Toast.makeText(this, R.string.stream_settings_invalid_fps, Toast.LENGTH_SHORT).show();
                                return;
                            }
                        } catch (NumberFormatException e) {
                            Toast.makeText(this, R.string.stream_settings_invalid_fps, Toast.LENGTH_SHORT).show();
                            return;
                        }
                    }
                } else {
                    // Preset FPS
                    profile.setFps(Float.parseFloat(fpsOptions[fpsSelection]));
                }
            }
            
            // Save if there are any overrides
            LimeLog.info("StreamSettings: Profile state before save - hasResOverride=" + profile.hasResolutionOverride() + 
                        ", hasFpsOverride=" + profile.hasFpsOverride() + ", hasAnyOverride=" + profile.hasAnyOverride());
            LimeLog.info("StreamSettings: Profile details: " + profile.toString());
            
            if (profile.hasAnyOverride()) {
                LimeLog.info("StreamSettings: Saving profile for " + appUUID);
                manager.setProfileForApp(appUUID, profile);
                Toast.makeText(this, getString(R.string.stream_settings_saved, appName), Toast.LENGTH_SHORT).show();
            } else {
                LimeLog.info("StreamSettings: No overrides detected, clearing profile");
                manager.removeProfileForApp(appUUID);
                Toast.makeText(this, getString(R.string.stream_settings_cleared, appName), Toast.LENGTH_SHORT).show();
            }
        });
        
        // Cancel button
        builder.setNegativeButton(android.R.string.cancel, null);
        
        // Clear button (only show if there's an existing profile)
        if (existingProfile != null && existingProfile.hasAnyOverride()) {
            builder.setNeutralButton(R.string.stream_settings_clear, (dialog, which) -> {
                manager.removeProfileForApp(appUUID);
                Toast.makeText(this, getString(R.string.stream_settings_cleared, appName), Toast.LENGTH_SHORT).show();
            });
        }
        
        builder.show();
    }

    @Override
    public void onContextMenuClosed(Menu menu) {
    }

    @Override
    public boolean onContextItemSelected(MenuItem item) {
        int itemId = item.getItemId();

        // Check if this is an OSC profile selection first (before trying to access MenuInfo)
        // OSC profile items are from a submenu and don't have AdapterContextMenuInfo
        if (itemId >= OSC_PROFILE_BASE_ID && itemId < OSC_PROFILE_BASE_ID + 1000) {
            int profileIndex = itemId - OSC_PROFILE_BASE_ID;
            com.limelight.binding.input.virtual_controller.OscProfilesManager manager =
                    com.limelight.binding.input.virtual_controller.OscProfilesManager.getInstance();
            java.util.List<com.limelight.binding.input.virtual_controller.OscProfile> profiles = manager.getProfiles();

            if (profileIndex >= 0 && profileIndex < profiles.size() && currentContextMenuApp != null) {
                com.limelight.binding.input.virtual_controller.OscProfile selectedProfile = profiles.get(profileIndex);
                String appUUID = currentContextMenuApp.app.getAppUUID();
                String appName = currentContextMenuApp.app.getAppName();

                if (appUUID != null && !appUUID.isEmpty()) {
                    // Set this profile as the default for this app
                    com.limelight.binding.input.virtual_controller.AppOscProfileManager appOscManager =
                            com.limelight.binding.input.virtual_controller.AppOscProfileManager.getInstance();
                    appOscManager.setDefaultProfileForApp(appUUID, selectedProfile.getUuid());

                    Toast.makeText(this,
                            getString(R.string.osc_profile_set_default, selectedProfile.getName(), appName),
                            Toast.LENGTH_SHORT).show();
                } else {
                    Toast.makeText(this, "Cannot set default profile: App UUID is missing", Toast.LENGTH_SHORT).show();
                }
                return true;
            }
            return super.onContextItemSelected(item);
        }

        // For all other items, we need the AdapterContextMenuInfo
        AdapterContextMenuInfo info = (AdapterContextMenuInfo) item.getMenuInfo();
        final AppObject app = (AppObject) appGridAdapter.getItem(info.position);

        switch (itemId) {
            case START_WITH_QUIT:
            case START_WITH_QUIT_VDISPLAY: {
                boolean withVDiaplay = itemId == START_WITH_QUIT_VDISPLAY;
                if (withVDiaplay && !(computer.vDisplaySupported && computer.vDisplayDriverReady)) {
                    UiHelper.displayVdisplayConfirmationDialog(
                        AppView.this,
                        computer,
                        () -> UiHelper.displayQuitConfirmationDialog(this, new Runnable() {
                            @Override
                            public void run() {
                                recordLastPlayed(app.app.getAppId());
                                ServerHelper.doStart(AppView.this, app.app, computer, managerBinder, true);
                            }
                        }, null),
                        null
                    );
                } else {
                    // Display a confirmation dialog first
                    UiHelper.displayQuitConfirmationDialog(this, new Runnable() {
                        @Override
                        public void run() {
                            recordLastPlayed(app.app.getAppId());
                            ServerHelper.doStart(AppView.this, app.app, computer, managerBinder, withVDiaplay);
                        }
                    }, null);
                }
                return true;
            }

            case START_OR_RESUME_ID:
            case START_WITH_VDISPLAY: {
                boolean withVDiaplay = itemId == START_WITH_VDISPLAY;
                if (withVDiaplay && !(computer.vDisplaySupported && computer.vDisplayDriverReady)) {
                    UiHelper.displayVdisplayConfirmationDialog(
                            AppView.this,
                            computer,
                            () -> {
                                recordLastPlayed(app.app.getAppId());
                                ServerHelper.doStart(AppView.this, app.app, computer, managerBinder, true);
                            },
                            null
                    );
                } else {
                    // Resume is the same as start for us
                    recordLastPlayed(app.app.getAppId());
                    ServerHelper.doStart(AppView.this, app.app, computer, managerBinder, withVDiaplay);
                }
                return true;
            }

            case QUIT_ID: {
                // Display a confirmation dialog first
                UiHelper.displayQuitConfirmationDialog(this, new Runnable() {
                    @Override
                    public void run() {
                        suspendGridUpdates = true;
                        ServerHelper.doQuit(AppView.this, computer,
                                app.app, managerBinder, new Runnable() {
                                    @Override
                                    public void run() {
                                        // Trigger a poll immediately
                                        suspendGridUpdates = false;
                                        if (poller != null) {
                                            poller.pollNow();
                                        }
                                    }
                                });
                    }
                }, null);
                return true;
            }

            case VIEW_DETAILS_ID: {
                Dialog.displayDialog(AppView.this, getResources().getString(R.string.title_details), app.app.toString(), false);
                return true;
            }

            case HIDE_APP_ID: {
                if (item.isChecked()) {
                    // Transitioning hidden to shown
                    hiddenAppIds.remove(app.app.getAppId());
                } else {
                    // Transitioning shown to hidden
                    hiddenAppIds.add(app.app.getAppId());
                }
                updateHiddenApps(false);
                return true;
            }

            case CREATE_SHORTCUT_ID: {
                ImageView appImageView = info.targetView.findViewById(R.id.grid_image);
                Bitmap appBits = ((BitmapDrawable) appImageView.getDrawable()).getBitmap();
                if (!shortcutHelper.createPinnedGameShortcut(computer, app.app, appBits)) {
                    Toast.makeText(AppView.this, getResources().getString(R.string.unable_to_pin_shortcut), Toast.LENGTH_LONG).show();
                }
                return true;
            }

            case EXPORT_LAUNCHER_FILE_ID: {
                if (app.app.getAppUUID() == null || (app.app.getAppUUID() != null && app.app.getAppUUID().isEmpty())) {
                    UiHelper.displayConfirmationDialog(
                            AppView.this,
                            getResources().getString(R.string.title_export_sunshine_launcher_file),
                            getResources().getString(R.string.message_export_sunshine_launcher_file),
                            getResources().getString(R.string.proceed),
                            getResources().getString(R.string.cancel),
                            () -> shortcutHelper.exportLauncherFile(computer, app.app),
                            null
                    );
                } else {
                    shortcutHelper.exportLauncherFile(computer, app.app);
                }
                return true;
            }

            case STREAM_SETTINGS_ID: {
                showStreamSettingsDialog(app);
                return true;
            }

            default: {
                return super.onContextItemSelected(item);
            }
        }
    }

    private void updateUiWithServerinfo(final ComputerDetails details) {
        AppView.this.runOnUiThread(new Runnable() {
            @Override
            public void run() {
                boolean updated = false;

                    // Look through our current app list to tag the running app
                for (int i = 0; i < appGridAdapter.getCount(); i++) {
                    AppObject existingApp = (AppObject) appGridAdapter.getItem(i);

                    // There can only be one or zero apps running.
                    if (existingApp.isRunning &&
                            existingApp.app.getAppId() == details.runningGameId) {
                        // This app was running and still is, so we're done now
                        return;
                    }
                    else if (existingApp.app.getAppId() == details.runningGameId) {
                        // This app wasn't running but now is
                        existingApp.isRunning = true;
                        updated = true;
                    }
                    else if (existingApp.isRunning) {
                        // This app was running but now isn't
                        existingApp.isRunning = false;
                        updated = true;
                    }
                    else {
                        // This app wasn't running and still isn't
                    }
                }

                if (updated) {
                    appGridAdapter.notifyDataSetChanged();
                }
            }
        });
    }

    private void updateUiWithAppList(final List<NvApp> appList) {
        AppView.this.runOnUiThread(new Runnable() {
            @Override
            public void run() {
                boolean updated = false;

                // First handle app updates and additions
                for (NvApp app : appList) {
                    boolean foundExistingApp = false;

                    // Try to update an existing app in the list first
                    for (int i = 0; i < appGridAdapter.getCount(); i++) {
                        AppObject existingApp = (AppObject) appGridAdapter.getItem(i);
                        if (existingApp.app.getAppId() == app.getAppId()) {
                            // Found the app; update its properties
                            if (!existingApp.app.getAppName().equals(app.getAppName())) {
                                existingApp.app.setAppName(app.getAppName());
                                updated = true;
                            }

                            foundExistingApp = true;
                            break;
                        }
                    }

                    if (!foundExistingApp) {
                        // This app must be new
                        appGridAdapter.addApp(new AppObject(app));

                        // We could have a leftover shortcut from last time this PC was paired
                        // or if this app was removed then added again. Enable those shortcuts
                        // again if present.
                        shortcutHelper.enableAppShortcut(computer, app);

                        updated = true;
                    }
                }

                // Next handle app removals
                int i = 0;
                while (i < appGridAdapter.getCount()) {
                    boolean foundExistingApp = false;
                    AppObject existingApp = (AppObject) appGridAdapter.getItem(i);

                    // Check if this app is in the latest list
                    for (NvApp app : appList) {
                        if (existingApp.app.getAppId() == app.getAppId()) {
                            foundExistingApp = true;
                            break;
                        }
                    }

                    // This app was removed in the latest app list
                    if (!foundExistingApp) {
                        shortcutHelper.disableAppShortcut(computer, existingApp.app, getString(R.string.app_removed_from_pc));
                        appGridAdapter.removeApp(existingApp);
                        updated = true;

                        // Check this same index again because the item at i+1 is now at i after
                        // the removal
                        continue;
                    }

                    // Move on to the next item
                    i++;
                }

                if (updated) {
                    appGridAdapter.notifyDataSetChanged();
                }
            }
        });
    }

    @Override
    public int getAdapterFragmentLayoutId() {
        return PreferenceConfiguration.readPreferences(AppView.this).smallIconMode ?
                    R.layout.app_grid_view_small : R.layout.app_grid_view;
    }

    @Override
    public void receiveAbsListView(AbsListView listView) {
        listView.setAdapter(appGridAdapter);
        listView.setOnItemClickListener(new OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> arg0, View arg1, int pos,
                                    long id) {
                AppObject app = (AppObject) appGridAdapter.getItem(pos);

                // Only open the context menu if something is running, otherwise start it
                if (lastRunningAppId != 0) {
                    if (prefConfig.resumeWithoutConfirm && lastRunningAppId == app.app.getAppId()) {
                        recordLastPlayed(app.app.getAppId());
                        ServerHelper.doStart(AppView.this, app.app, computer, managerBinder, prefConfig.useVirtualDisplay);
                    } else {
                        openContextMenu(arg1);
                    }
                } else {
                    if (prefConfig.useVirtualDisplay && !(computer.vDisplaySupported && computer.vDisplayDriverReady)) {
                        UiHelper.displayVdisplayConfirmationDialog(
                                AppView.this,
                                computer,
                                () -> {
                                    recordLastPlayed(app.app.getAppId());
                                    ServerHelper.doStart(AppView.this, app.app, computer, managerBinder, true);
                                },
                                null
                        );
                    } else {
                        recordLastPlayed(app.app.getAppId());
                        ServerHelper.doStart(AppView.this, app.app, computer, managerBinder, prefConfig.useVirtualDisplay);
                    }
                }
            }
        });
        UiHelper.applyStatusBarPadding(listView);
        registerForContextMenu(listView);
        listView.requestFocus();
    }

    public static class AppObject {
        public final NvApp app;
        public boolean isRunning;
        public boolean isHidden;

        public AppObject(NvApp app) {
            if (app == null) {
                throw new IllegalArgumentException("app must not be null");
            }
            this.app = app;
        }

        @Override
        public String toString() {
            return app.getAppName();
        }
    }
}
