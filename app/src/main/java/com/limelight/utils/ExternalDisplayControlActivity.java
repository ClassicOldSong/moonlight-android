package com.limelight.utils;

import static com.limelight.StartExternalDisplayControlReceiver.requestFocusToGameActivity;
import static com.limelight.utils.ServerHelper.getSecondaryDisplay;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.ActivityOptions;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.hardware.display.DisplayManager;
import android.content.res.Configuration;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Display;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.inputmethod.InputMethodManager;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;

import com.limelight.Game;
import com.limelight.GameMenu;
import com.limelight.LimeLog;
import com.limelight.R;
import com.limelight.StartExternalDisplayControlReceiver;
import com.limelight.binding.input.virtual_controller.keyboard.KeyBoardLayoutController;
import com.limelight.preferences.PreferenceConfiguration;
import com.limelight.ui.ExternalControllerView;

/**
 * A standalone Activity providing a full-screen touchpad controller for the secondary display.
 * It creates its own UI programmatically and hosts the GameMenu for in-game options.
 */
public class ExternalDisplayControlActivity extends AppCompatActivity implements View.OnKeyListener, KeyBoardLayoutController.ViewCallbacks {

    public static String EXTRA_LAUNCH_INTENT = "launchIntent";
    public static String EXTRA_LAUNCH_DISPLAY_ID = "launchDisplayId";

    @SuppressLint("StaticFieldLeak")
    public static ExternalDisplayControlActivity instance;

    private PreferenceConfiguration prefConfig;

    private ExternalControllerView rootLayout;
    private ImageButton zoomButton;
    private KeyBoardLayoutController keyBoardLayoutController;

    private boolean isKeyboardVisible = false;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private int failCount = 0;
    private Runnable dimScreenRunnable;
    private float originalBrightness = -1f; // -1 = use system default
    private static final int INACTIVITY_TIMEOUT_MS = 10_000;
    private static final String PREF_NOTIFICATION_REQUESTED = "notification_permission_requested";


    private static final String NOTIFICATION_CHANNEL_ID = "secondary_screen_active_channel_id";
    public static final int SECONDARY_SCREEN_NOTIFICATION_ID = 1;
    private static final int PERMISSION_REQUEST_CODE = 1001;

    private GameMenu gameMenu;

    // --- Static Methods for External Control ---

    public static void closeExternalDisplayControl() {
        if (instance != null) {
            instance.finish();
        }
    }

    public static void toggleKeyboard() {
        if (instance != null) {
            instance._toggleKeyboard();
        }
    }

    public static void toggleFullKeyboard() {
        if (instance != null) {
            instance._toggleFullKeyboard();
        }
    }

    public static void toggleGameMenu() {
        if (instance != null) {
            instance.showGameMenu();
        }
    }

    // --- Activity Lifecycle ---

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        instance = this;
        prefConfig = PreferenceConfiguration.readPreferences(this);

        if (!isGameInstanceAvailable()) {
            Intent gameIntent = getIntent().getParcelableExtra(EXTRA_LAUNCH_INTENT);
            if (gameIntent == null) {
                finish();
            } else {
                // For dual internal screen devices, Game's EXTRA_DISPLAY_ID is already set
                // to the larger display; honor it instead of using getSecondaryDisplay()
                int launchDisplayId = getIntent().getIntExtra(EXTRA_LAUNCH_DISPLAY_ID, -1);
                boolean isDualInternal = launchDisplayId != -1;

                if (isDualInternal && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    // Dual internal screen: launch Game on the display specified in EXTRA_DISPLAY_ID
                    int gameDisplayId = gameIntent.getIntExtra(Game.EXTRA_DISPLAY_ID, -1);
                    if (gameDisplayId == -1) gameDisplayId = Display.DEFAULT_DISPLAY;
                    ActivityOptions options = ActivityOptions.makeBasic();
                    options.setLaunchDisplayId(gameDisplayId);
                    DisplayManager dm = (DisplayManager) getSystemService(Context.DISPLAY_SERVICE);
                    Display targetDisplay = dm.getDisplay(gameDisplayId);
                    Toast.makeText(this,
                            getString(R.string.external_display_info,
                                    targetDisplay != null ? targetDisplay.getMode().getPhysicalWidth() : 0,
                                    targetDisplay != null ? targetDisplay.getMode().getPhysicalHeight() : 0,
                                    targetDisplay != null ? targetDisplay.getMode().getRefreshRate() : 0),
                            Toast.LENGTH_LONG).show();
                    startActivity(gameIntent, options.toBundle());
                } else {
                    Display secondaryDisplay = getSecondaryDisplay(this);
                    if (secondaryDisplay != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        ActivityOptions options = ActivityOptions.makeBasic();
                        options.setLaunchDisplayId(secondaryDisplay.getDisplayId());
                        Toast.makeText(this,
                                getString(R.string.external_display_info,
                                        secondaryDisplay.getMode().getPhysicalWidth(),
                                        secondaryDisplay.getMode().getPhysicalHeight(),
                                        secondaryDisplay.getMode().getRefreshRate()),
                                Toast.LENGTH_LONG).show();

                        startActivity(gameIntent, options.toBundle());
                    } else {
                        LimeLog.warning(getString(R.string.no_external_display));
                        startActivity(gameIntent);
                        finish();
                    }
                }
            }
        }

        initViews();
    }

    private void initViews() {
        if (Game.instance == null) {
            if (failCount > 10) {
                Toast.makeText(this, getString(R.string.no_game_instance), Toast.LENGTH_LONG).show();
                finish();
            }
            // Wait for the intent to get started
            handler.postDelayed(this::initViews, 500);
            failCount++;
            return;
        }

        WindowInsetsControllerCompat windowInsetsController = WindowCompat.getInsetsController(getWindow(), getWindow().getDecorView());

        windowInsetsController.setSystemBarsBehavior(
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        );

        windowInsetsController.hide(WindowInsetsCompat.Type.systemBars());
        windowInsetsController.hide(WindowInsetsCompat.Type.navigationBars());

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
            androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(getWindow().getDecorView(), (v, insets) -> {
                boolean imeVisible = insets.isVisible(WindowInsetsCompat.Type.ime());
                updateKeyboardVisibility(imeVisible || (keyBoardLayoutController != null && keyBoardLayoutController.isKeyboardVisible()));
                return androidx.core.view.ViewCompat.onApplyWindowInsets(v, insets);
            });
        }

        initializeComponents();
        createProgrammaticUI();
        checkNotificationPermission();
        initTouchEventHandling();
        requestFocusToGameActivity(false);
    }

    @SuppressLint("ClickableViewAccessibility")
    private void initTouchEventHandling() {
        // Intercept touch events on root layout
        rootLayout.setOnTouchListener((v, event) -> {
            handleUserActivity();
            if (Game.instance != null) {
                Game.instance.handleMotionEvent(v, event);
            }
            return true;
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (!isGameInstanceAvailable() && gameMenu != null) {
            finish();
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (!isGameInstanceAvailable()) {
            finish();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        instance = null;
    }

    @Override
    public void onKeyboardControllerVisibilityChange(boolean visible) {
        updateKeyboardVisibility(visible);
    }

    @SuppressLint("ClickableViewAccessibility")
    private void setupInactivityTimeoutForBrightness() {
        // Save the original brightness
        WindowManager.LayoutParams layout = getWindow().getAttributes();
        originalBrightness = layout.screenBrightness;

        // Runnable to dim screen
        dimScreenRunnable = () -> {
            WindowManager.LayoutParams l = getWindow().getAttributes();
            l.screenBrightness = 0.0f;
            getWindow().setAttributes(l);
        };

        // Start the timer
        resetInactivityTimer();
    }

    private void updateKeyboardVisibility(boolean visible) {
        if (isKeyboardVisible != visible) {
            isKeyboardVisible = visible;
            if (isKeyboardVisible) {
                // Keyboard is visible, so prevent screen dimming
                handler.removeCallbacks(dimScreenRunnable);
                // and restore brightness
                restoreBrightnessIfNeeded();
            } else {
                // Keyboard is hidden, so resume inactivity timer
                resetInactivityTimer();
            }
        }
    }

    private void restoreBrightnessIfNeeded() {
        WindowManager.LayoutParams l = getWindow().getAttributes();
        if (l.screenBrightness == 0.0f) {
            l.screenBrightness = originalBrightness;
            getWindow().setAttributes(l);
        }
    }

    private void handleUserActivity() {
        // Screen dimming disabled - keep screen always bright
    }

    private void resetInactivityTimer() {
        handler.removeCallbacks(dimScreenRunnable);
        if (!isKeyboardVisible) {
            handler.postDelayed(dimScreenRunnable, INACTIVITY_TIMEOUT_MS);
        }
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (isGameInstanceAvailable()) {
            Game.instance.handleFocusChange(hasFocus);
        } else {
            finish();
        }
    }

    @Override
    public void onBackPressed() {
        if (Game.instance != null && Game.instance.isKeyboardLayoutVisible()) {
            toggleFullKeyboard();
        } else if (gameMenu != null && !gameMenu.isMenuOpen() && Game.instance != null)
            Game.instance.onBackPressed();
        else {
            super.onBackPressed();
        }
    }

    // --- Initialization and UI Creation ---

    /**
     * Checks if the static Game.instance is alive. If not, finishes this Activity.
     */
    private boolean isGameInstanceAvailable() {
        return Game.instance != null;
    }

    /**
     * Initializes core components needed for this controller Activity.
     */
    private void initializeComponents() {
        this.gameMenu = new GameMenu(Game.instance, instance);
    }

    @Override
    public void onConfigurationChanged(@NonNull Configuration newConfig) {
        if (Game.instance != null) {
            Game.instance.onConfigurationChanged(newConfig);
        }
        super.onConfigurationChanged(newConfig);
    }

    @Override
    public boolean onGenericMotionEvent(MotionEvent event) {
        if (Game.instance != null) {
            requestFocusToGameActivity(false);
            return Game.instance.onGenericMotionEvent(event);
        }
        return false;
    }

    @Override
    public boolean onKey(View view, int keyCode, KeyEvent keyEvent) {
        if (Game.instance != null) {
            if (keyEvent.getDeviceId() >= 0) {
                requestFocusToGameActivity(false);
            }
            switch (keyEvent.getAction()) {
                case KeyEvent.ACTION_DOWN:
                    return Game.instance.handleKeyDown(keyEvent);
                case KeyEvent.ACTION_UP:
                    return Game.instance.handleKeyUp(keyEvent);
                case KeyEvent.ACTION_MULTIPLE:
                    return Game.instance.handleKeyMultiple(keyEvent);
                default:
                    return false;
            }
        }

        return false;
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (Game.instance != null) {
            if (event.getDeviceId() >= 0) {
                requestFocusToGameActivity(false);
            }
            return Game.instance.onKeyDown(keyCode, event);
        }
        return false;
    }

    @Override
    public boolean onKeyUp(int keyCode, KeyEvent event) {
        if (Game.instance != null) {
            if (event.getDeviceId() >= 0) {
                requestFocusToGameActivity(false);
            }
            return Game.instance.onKeyUp(keyCode, event);
        }
        return false;
    }

    @Override
    public boolean onKeyMultiple(int keyCode, int repeatCount, KeyEvent event) {
        if (Game.instance != null) {
            if (event.getDeviceId() >= 0) {
                requestFocusToGameActivity(false);
            }
            return Game.instance.onKeyMultiple(keyCode, repeatCount, event);
        }
        return false;
    }

    @SuppressLint("ClickableViewAccessibility")
    private void createProgrammaticUI() {
        rootLayout = new ExternalControllerView(this);
        rootLayout.setLayoutParams(new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
        rootLayout.setFocusable(true);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            rootLayout.setFocusedByDefault(true);
        }

        rootLayout.setInputCallbacks(Game.instance);
        rootLayout.setCommitTextEnabled(prefConfig.enableCommitText);

        setContentView(rootLayout);

        // Top-left buttons
        LinearLayout topLeftButtons = createButtonContainer(Gravity.TOP | Gravity.START);
        topLeftButtons.setFocusable(false);
//        topLeftButtons.addView(createImageButton(R.drawable.ic_focus_secondary, v -> requestFocusToGameActivity(false)));
        zoomButton = createImageButton(R.drawable.ic_zoom_toggle, v -> toggleZoomMode(true));
        if (Game.instance != null && Game.instance.isZoomModeEnabled()) {
            zoomButton.setAlpha(1.0f);
        } else {
            zoomButton.setAlpha(0.5f);
        }
        topLeftButtons.addView(zoomButton);
        rootLayout.addView(topLeftButtons);

        // Top-center buttons
//        LinearLayout topCenterButtons = createButtonContainer(Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
//        topCenterButtons.setFocusable(false);
//        rootLayout.addView(topCenterButtons);

        // Top-right buttons
        LinearLayout topRightButtons = createButtonContainer(Gravity.TOP | Gravity.END);
        topRightButtons.setFocusable(false);
        topRightButtons.addView(createImageButton(R.drawable.ic_menu_external, v -> showGameMenu()));
        topRightButtons.addView(createImageButton(R.drawable.ic_close_external, v -> finish()));
        rootLayout.addView(topRightButtons);

        // Bottom-left button: Android keyboard toggle
        LinearLayout bottomLeftButton = createButtonContainer(Gravity.BOTTOM | Gravity.START);
        bottomLeftButton.setFocusable(false);
        bottomLeftButton.addView(createImageButton(R.drawable.ic_android_keyboard, v -> _toggleKeyboard()));
        rootLayout.addView(bottomLeftButton);

        // Bottom-center buttons
//        LinearLayout bottomCenterButtons = createButtonContainer(Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
//        bottomCenterButtons.setFocusable(false);
//        rootLayout.addView(bottomCenterButtons);

        // Bottom-right button: Custom keyboard toggle
        LinearLayout bottomRightButton = createButtonContainer(Gravity.BOTTOM | Gravity.END);
        bottomRightButton.setFocusable(false);
        bottomRightButton.addView(createImageButton(R.drawable.ic_fullscreen_keyboard, v -> _toggleFullKeyboard()));
        rootLayout.addView(bottomRightButton);
    }

    /**
     * Toggles the visibility of the on-screen software keyboard.
     */
    private void _toggleKeyboard() {
        LimeLog.info("Toggling keyboard overlay on ExternalDisplayControlActivity");
        InputMethodManager inputManager = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        inputManager.toggleSoftInput(0, 0);
    }

    private void initFullKeyboard(PreferenceConfiguration prefConfig) {
        keyBoardLayoutController = new KeyBoardLayoutController(rootLayout, this, prefConfig);
        keyBoardLayoutController.setViewCallbacks(this);
        keyBoardLayoutController.refreshLayout();
        keyBoardLayoutController.show();
    }

    /**
     * Toggles the visibility of the full screen keyboard
     */
    private void _toggleFullKeyboard() {
        if (keyBoardLayoutController == null) {
            initFullKeyboard(prefConfig);
            return;
        }
        keyBoardLayoutController.toggleVisibility();
    }

    public void toggleZoomMode(boolean callGame) {
        if (Game.instance != null) {
            if (callGame) {
                Game.instance.toggleZoomMode();
            } else {
                if (Game.instance.isZoomModeEnabled()) {
                    zoomButton.setAlpha(1.0f);
                } else {
                    zoomButton.setAlpha(0.5f);
                }
            }
        }
    }

    // --- Public methods to interact with the GameMenu instance ---

    public void showGameMenu() {
        if (gameMenu != null) {
            gameMenu.showMenu(null);
        }
    }

    // --- UI Factory Methods ---

    private LinearLayout createButtonContainer(int gravity) {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.HORIZONTAL);
        layout.setGravity(gravity);
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, gravity);
        layout.setLayoutParams(params);
        return layout;
    }

    private ImageButton createImageButton(int imageResourceId, View.OnClickListener listener) {
        ImageButton button = new ImageButton(this);
        button.setImageResource(imageResourceId);
        button.setBackgroundColor(Color.TRANSPARENT);
        button.setOnClickListener(listener);
        button.setLayoutParams(new LinearLayout.LayoutParams(dpToPx(56), dpToPx(56)));
        return button;
    }

    // --- Notification Management ---

    private void checkNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                android.content.SharedPreferences prefs = getPreferences(MODE_PRIVATE);
                boolean alreadyRequested = prefs.getBoolean(PREF_NOTIFICATION_REQUESTED, false);
                // Only ask once ever. After the first request (granted or denied), never ask again.
                if (!alreadyRequested) {
                    prefs.edit().putBoolean(PREF_NOTIFICATION_REQUESTED, true).apply();
                    ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.POST_NOTIFICATIONS}, PERMISSION_REQUEST_CODE);
                }
                return;
            }
        }
        showStickyNotification();
    }

    private void showStickyNotification() {
        NotificationManager notificationManager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(NOTIFICATION_CHANNEL_ID, getString(R.string.notification_channel_name), NotificationManager.IMPORTANCE_LOW);
            channel.setShowBadge(false);
            notificationManager.createNotificationChannel(channel);
        }

        Intent broadcastIntent = new Intent(this, StartExternalDisplayControlReceiver.class);
        PendingIntent pendingIntent = PendingIntent.getBroadcast(this, 0, broadcastIntent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        NotificationCompat.Builder notificationBuilder = new NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
                .setContentTitle(getString(R.string.notification_title))
                .setContentText(getString(R.string.notification_text))
                .setSmallIcon(R.drawable.app_icon)
                .setContentIntent(pendingIntent)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setOngoing(true);

        Notification notification = notificationBuilder.build();

        notificationManager.notify(SECONDARY_SCREEN_NOTIFICATION_ID, notification);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == PERMISSION_REQUEST_CODE) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                showStickyNotification();
            } else {
                Toast.makeText(this, getString(R.string.permission_denied), Toast.LENGTH_SHORT).show();
            }
        }
    }

    // --- Utility Methods ---

    private int dpToPx(int dp) {
        return (int) (dp * getResources().getDisplayMetrics().density);
    }
}