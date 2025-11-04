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
import android.content.res.Configuration;
import android.graphics.Color;
import android.hardware.display.DisplayManager;
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
    public static String EXTRA_TOUCHPAD_DISPLAY_ID = "TOUCHPAD_DISPLAY_ID";
    public static String EXTRA_IS_TOUCHPAD_MODE = "IS_TOUCHPAD_MODE";

    @SuppressLint("StaticFieldLeak")
    public static ExternalDisplayControlActivity instance;

    private PreferenceConfiguration prefConfig;
    private boolean isTouchpadMode = false;
    private int touchpadDisplayId = -1;

    private ExternalControllerView rootLayout;
    private ImageButton zoomButton;
    private KeyBoardLayoutController keyBoardLayoutController;

    private boolean isKeyboardVisible = false;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private int failCount = 0;
    private Runnable dimScreenRunnable;
    private float originalBrightness = -1f; // -1 = use system default
    private static final int INACTIVITY_TIMEOUT_MS = 10_000;


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

        // Check if we're in touchpad mode
        isTouchpadMode = getIntent().getBooleanExtra(EXTRA_IS_TOUCHPAD_MODE, false);
        touchpadDisplayId = getIntent().getIntExtra(EXTRA_TOUCHPAD_DISPLAY_ID, -1);

        LimeLog.info("ExternalDisplayControlActivity onCreate - isTouchpadMode: " + isTouchpadMode + ", touchpadDisplayId: " + touchpadDisplayId);

        if (!isGameInstanceAvailable()) {
            Intent gameIntent = getIntent().getParcelableExtra(EXTRA_LAUNCH_INTENT);
            LimeLog.info("ExternalDisplayControlActivity - gameIntent null: " + (gameIntent == null) + ", Game.instance null: " + (Game.instance == null));
            if (gameIntent == null) {
                LimeLog.warning("ExternalDisplayControlActivity - No game intent, finishing");
                finish();
            } else {
                if (isTouchpadMode && touchpadDisplayId != -1) {
                    LimeLog.info("ExternalDisplayControlActivity - Touchpad mode active, touchpadDisplayId: " + touchpadDisplayId);
                    // In touchpad mode, launch game on the specified display
                    DisplayManager displayManager = (DisplayManager) getSystemService(Context.DISPLAY_SERVICE);
                    Display targetDisplay = displayManager.getDisplay(touchpadDisplayId);
                    LimeLog.info("ExternalDisplayControlActivity - targetDisplay: " + (targetDisplay != null ? targetDisplay.getDisplayId() : "null"));

                    if (targetDisplay != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        // Get the streaming display to show info
                        Display streamDisplay = null;
                        for (Display display : displayManager.getDisplays()) {
                            if (display.getDisplayId() != touchpadDisplayId) {
                                streamDisplay = display;
                                break;
                            }
                        }

                        LimeLog.info("ExternalDisplayControlActivity - Found stream display: " + (streamDisplay != null ? streamDisplay.getDisplayId() : "null"));

                        if (streamDisplay != null) {
                            // Launch Game on the stream display explicitly
                            ActivityOptions options = ActivityOptions.makeBasic();
                            options.setLaunchDisplayId(streamDisplay.getDisplayId());
                            LimeLog.info("ExternalDisplayControlActivity - Launching Game on display: " + streamDisplay.getDisplayId());

                            Toast.makeText(this,
                                    "Touchpad mode: Stream on Display " + streamDisplay.getDisplayId() +
                                    " (" + streamDisplay.getMode().getPhysicalWidth() + "x" +
                                    streamDisplay.getMode().getPhysicalHeight() + ")",
                                    Toast.LENGTH_LONG).show();

                            startActivity(gameIntent, options.toBundle());
                        } else {
                            LimeLog.warning("ExternalDisplayControlActivity - No stream display found, launching on default");
                            startActivity(gameIntent);
                        }
                    } else {
                        LimeLog.warning(getString(R.string.no_external_display));
                        startActivity(gameIntent);
                        finish();
                    }
                } else {
                    // Original behavior for full external display mode
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
        LimeLog.info("ExternalDisplayControlActivity - initViews called, failCount: " + failCount + ", Game.instance: " + (Game.instance != null));
        if (Game.instance == null) {
            if (failCount > 10) {
                LimeLog.warning("ExternalDisplayControlActivity - Failed to find Game.instance after 10 attempts, finishing");
                Toast.makeText(this, getString(R.string.no_game_instance), Toast.LENGTH_LONG).show();
                finish();
            }
            // Wait for the intent to get started
            LimeLog.info("ExternalDisplayControlActivity - Waiting for Game.instance, retrying in 500ms");
            handler.postDelayed(this::initViews, 500);
            failCount++;
            return;
        }

        LimeLog.info("ExternalDisplayControlActivity - Game.instance found, creating UI");

        try {
            LimeLog.info("ExternalDisplayControlActivity - Setting up window insets");
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

            LimeLog.info("ExternalDisplayControlActivity - Initializing components");
            initializeComponents();

            LimeLog.info("ExternalDisplayControlActivity - Creating programmatic UI");
            createProgrammaticUI();

            LimeLog.info("ExternalDisplayControlActivity - Checking notification permission");
            checkNotificationPermission();

            LimeLog.info("ExternalDisplayControlActivity - Initializing touch event handling");
            initTouchEventHandling();

            LimeLog.info("ExternalDisplayControlActivity - Setting up inactivity timeout");
            setupInactivityTimeoutForBrightness();

            LimeLog.info("ExternalDisplayControlActivity - Requesting focus to game");
            requestFocusToGameActivity(false);

            LimeLog.info("ExternalDisplayControlActivity - UI initialization complete!");
        } catch (Exception e) {
            LimeLog.severe("ExternalDisplayControlActivity - Error during UI creation: " + e.getMessage());
            e.printStackTrace();
            finish();
        }
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
        LimeLog.info("ExternalDisplayControlActivity - onResume, isTouchpadMode: " + isTouchpadMode + ", gameMenu: " + (gameMenu != null) + ", Game.instance: " + (Game.instance != null));
        // In touchpad mode, we should stay open even if Game isn't available
        if (!isTouchpadMode && !isGameInstanceAvailable() && gameMenu != null) {
            LimeLog.info("ExternalDisplayControlActivity - Finishing due to no Game instance (not touchpad mode)");
            finish();
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        LimeLog.info("ExternalDisplayControlActivity - onPause, isTouchpadMode: " + isTouchpadMode + ", Game.instance: " + (Game.instance != null));
        // In touchpad mode, we should stay open even if Game pauses
        if (!isTouchpadMode && !isGameInstanceAvailable()) {
            LimeLog.info("ExternalDisplayControlActivity - Finishing due to no Game instance (not touchpad mode)");
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
        // Restore brightness if dimmed
        restoreBrightnessIfNeeded();
        resetInactivityTimer();
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
                ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.POST_NOTIFICATIONS}, PERMISSION_REQUEST_CODE);
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