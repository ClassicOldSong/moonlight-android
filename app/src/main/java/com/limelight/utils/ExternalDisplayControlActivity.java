package com.limelight.utils;

import static com.limelight.StartExternalDisplayControlReceiver.requestFocusToGameActivity;
import static com.limelight.StartExternalDisplayControlReceiver.requestFocusToExternalDisplayControl;
import static com.limelight.utils.ServerHelper.getSecondaryDisplay;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.Intent;
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
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;

import com.limelight.BuildConfig;
import com.limelight.Game;
import com.limelight.GameMenu;
import com.limelight.LimeLog;
import com.limelight.R;
import com.limelight.binding.input.virtual_controller.keyboard.KeyBoardLayoutController;
import com.limelight.preferences.PreferenceConfiguration;
import com.limelight.ui.ExternalControllerView;

/**
 * A standalone Activity providing a full-screen touchpad controller for the secondary display.
 * It creates its own UI programmatically and hosts the GameMenu for in-game options.
 */
public class ExternalDisplayControlActivity extends AppCompatActivity implements View.OnKeyListener, KeyBoardLayoutController.ViewCallbacks {

    public static String EXTRA_LAUNCH_INTENT = "launchIntent";

    @SuppressLint("StaticFieldLeak")
    public static ExternalDisplayControlActivity instance;

    public enum PresentationState {
        IDLE,
        LAUNCHING_GAME,
        WAITING_FOR_CONTROLLER_FOCUS,
        CONTROLLER_ACTIVE,
        ENDING
    }

    public enum PresentationEndReason {
        USER_CLOSE,
        USER_LEFT,
        CONTROLLER_DESTROYED,
        DISPLAY_REMOVED,
        FOCUS_TIMEOUT,
        CONNECTION_ERROR
    }

    private PreferenceConfiguration prefConfig;

    private ExternalControllerView rootLayout;
    private ImageButton zoomButton;
    private KeyBoardLayoutController keyBoardLayoutController;

    private boolean isKeyboardVisible = false;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private int failCount = 0;
    private boolean pendingGameLaunch;
    private boolean userLeaving;
    private boolean finishingFromGame;
    private PresentationState presentationState = PresentationState.IDLE;
    private Runnable dimScreenRunnable;
    private float originalBrightness = -1f; // -1 = use system default
    private static final int INACTIVITY_TIMEOUT_MS = 10_000;

    private GameMenu gameMenu;

    // --- Static Methods for External Control ---

    public static void closeExternalDisplayControl() {
        if (instance != null) {
            Game game = Game.instance;
            if (game != null && game.isInPresentationMode()) {
                instance.requestPresentationEnd(PresentationEndReason.USER_CLOSE);
            }
            else {
                instance.finish();
            }
        }
    }

    public static boolean isPresentationStateAlive(PresentationState state) {
        return state == PresentationState.LAUNCHING_GAME
                || state == PresentationState.WAITING_FOR_CONTROLLER_FOCUS
                || state == PresentationState.CONTROLLER_ACTIVE;
    }

    public static boolean shouldKeepPresentationAlive(PresentationState state,
                                                      boolean controllerAvailable) {
        return controllerAvailable && isPresentationStateAlive(state);
    }

    public static boolean shouldEndPresentationOnControllerStop(PresentationState state,
                                                                 boolean userLeaving,
                                                                 boolean changingConfigurations) {
        return state == PresentationState.CONTROLLER_ACTIVE
                && userLeaving
                && !changingConfigurations;
    }

    public static boolean shouldEndPresentationOnControllerDestroy(PresentationState state,
                                                                    boolean changingConfigurations,
                                                                    boolean finishingFromGame) {
        return isPresentationStateAlive(state)
                && !changingConfigurations
                && !finishingFromGame;
    }

    public static boolean shouldKeepPresentationAlive() {
        return instance != null
                && shouldKeepPresentationAlive(instance.presentationState, true);
    }

    public static boolean isPresentationControllerActive() {
        return instance != null
                && instance.presentationState == PresentationState.CONTROLLER_ACTIVE
                && instance.hasWindowFocus();
    }

    public static boolean requestPresentationControllerTakeover(Game game) {
        return instance != null && instance.requestControllerTakeover(game);
    }

    public static void finishForPresentationEnd(PresentationEndReason reason) {
        if (instance != null) {
            instance.finishFromGame(reason);
        }
    }

    public static boolean shouldRequestGameFocusForControllerInput(boolean gameAvailable,
                                                                   boolean inPresentationMode,
                                                                   int deviceId) {
        return gameAvailable && !inPresentationMode && deviceId >= 0;
    }

    public static boolean forwardControllerKeyEvent(Game game, KeyEvent event) {
        if (game == null || event == null) {
            return false;
        }

        switch (event.getAction()) {
            case KeyEvent.ACTION_DOWN:
                return game.handleKeyDown(event);
            case KeyEvent.ACTION_UP:
                return game.handleKeyUp(event);
            case KeyEvent.ACTION_MULTIPLE:
                return game.handleKeyMultiple(event);
            default:
                return false;
        }
    }

    public static boolean forwardControllerTouchEvent(Game game, View view, MotionEvent event) {
        return game != null && event != null && game.handleMotionEvent(view, event);
    }

    public static boolean shouldFinishWhenGameUnavailable(boolean pendingGameLaunch,
                                                          boolean gameAvailable) {
        return !pendingGameLaunch && !gameAvailable;
    }

    public static boolean shouldActivatePresentationController(PresentationState state,
                                                               boolean gameAvailable,
                                                               boolean inPresentationMode,
                                                               boolean rootAttached,
                                                               boolean windowFocused) {
        return (state == PresentationState.WAITING_FOR_CONTROLLER_FOCUS
                || state == PresentationState.CONTROLLER_ACTIVE)
                && gameAvailable
                && inPresentationMode
                && rootAttached
                && windowFocused;
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
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        if (!handleLaunchIntent(getIntent()) && !isGameInstanceAvailable()) {
            finish();
            return;
        }

        initViews();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleLaunchIntent(intent);
        activatePresentationControllerIfReady();
    }

    private boolean handleLaunchIntent(Intent intent) {
        if (isGameInstanceAvailable()) {
            if (Game.instance.isInPresentationMode() && presentationState == PresentationState.IDLE) {
                presentationState = PresentationState.WAITING_FOR_CONTROLLER_FOCUS;
            }
            return false;
        }

        Intent gameIntent = intent.getParcelableExtra(EXTRA_LAUNCH_INTENT);
        if (gameIntent == null) {
            return false;
        }

        pendingGameLaunch = true;
        userLeaving = false;
        finishingFromGame = false;
        presentationState = PresentationState.LAUNCHING_GAME;
        Display secondaryDisplay = getSecondaryDisplay(this);
        if (secondaryDisplay != null) {
            Toast.makeText(this,
                    getString(R.string.external_display_info,
                            secondaryDisplay.getMode().getPhysicalWidth(),
                            secondaryDisplay.getMode().getPhysicalHeight(),
                            secondaryDisplay.getMode().getRefreshRate()),
                    Toast.LENGTH_LONG).show();

            boolean launched = startPresentationFallback(gameIntent, secondaryDisplay);
            if (!launched) {
                presentationState = PresentationState.ENDING;
                LimeLog.warning(getString(R.string.no_external_display));
                try {
                    startActivity(gameIntent);
                } catch (RuntimeException e) {
                    LimeLog.warning("Starting Game on default display failed: " + e);
                }
                finish();
            }
        } else {
            presentationState = PresentationState.ENDING;
            LimeLog.warning(getString(R.string.no_external_display));
            startActivity(gameIntent);
            finish();
        }

        return true;
    }

    private boolean startPresentationFallback(Intent gameIntent, Display secondaryDisplay) {
        gameIntent.putExtra(Game.EXTRA_DISPLAY_ID, Display.DEFAULT_DISPLAY);
        gameIntent.putExtra(Game.EXTRA_PRESENTATION_DISPLAY_ID, secondaryDisplay.getDisplayId());
        try {
            startActivity(gameIntent);
            return true;
        } catch (RuntimeException e) {
            LimeLog.warning("Fallback startActivity (Presentation path) failed: " + e);
            return false;
        }
    }

    private void initViews() {
        if (Game.instance == null) {
            if (failCount > 10) {
                pendingGameLaunch = false;
                presentationState = PresentationState.ENDING;
                Toast.makeText(this, getString(R.string.no_game_instance), Toast.LENGTH_LONG).show();
                finish();
                return;
            }
            // Wait for the intent to get started
            handler.postDelayed(this::initViews, 500);
            failCount++;
            return;
        }

        pendingGameLaunch = false;
        failCount = 0;

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
        initTouchEventHandling();
        setupInactivityTimeoutForBrightness();
    }

    @SuppressLint("ClickableViewAccessibility")
    private void initTouchEventHandling() {
        // Intercept touch events on root layout
        rootLayout.setOnTouchListener((v, event) -> {
            handleUserActivity();
            forwardControllerTouchEvent(Game.instance, v, event);
            return true;
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        userLeaving = false;
        activatePresentationControllerIfReady();
        if (!pendingGameLaunch && !isGameInstanceAvailable() && gameMenu != null) {
            finish();
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (!pendingGameLaunch && !isGameInstanceAvailable()) {
            finish();
        }
    }

    @Override
    protected void onStop() {
        super.onStop();
        if (shouldEndPresentationOnControllerStop(
                presentationState,
                userLeaving,
                isChangingConfigurations())) {
            requestPresentationEnd(PresentationEndReason.USER_LEFT);
        }
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        if (shouldEndPresentationOnControllerDestroy(
                presentationState,
                isChangingConfigurations(),
                finishingFromGame)) {
            requestPresentationEnd(PresentationEndReason.CONTROLLER_DESTROYED);
        }
        if (instance == this) {
            instance = null;
        }
        super.onDestroy();
    }

    @Override
    protected void onUserLeaveHint() {
        super.onUserLeaveHint();
        if (presentationState == PresentationState.CONTROLLER_ACTIVE) {
            userLeaving = true;
            LimeLog.info("Presentation controller user leave requested");
        }
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
        Game game = Game.instance;
        if (game != null) {
            if (game.isInPresentationMode()) {
                if (hasFocus) {
                    activatePresentationControllerIfReady();
                }
                else if (presentationState == PresentationState.CONTROLLER_ACTIVE) {
                    game.onPresentationControllerWindowFocusChanged(false);
                    game.setMetaKeyCaptureState(getComponentName(), false);
                }
            } else {
                game.handleFocusChange(hasFocus);
            }
        } else if (shouldFinishWhenGameUnavailable(pendingGameLaunch, false)) {
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
        return Game.instance != null && !Game.instance.isFinishing() && !Game.instance.isDestroyed();
    }

    /**
     * Initializes core components needed for this controller Activity.
     */
    private void initializeComponents() {
        this.gameMenu = new GameMenu(Game.instance, instance);
    }

    private void logGenericMotionForwarding(String boundary, MotionEvent event) {
        if (!BuildConfig.DEBUG || event == null) {
            return;
        }

        android.util.Log.i("MoonlightInput", boundary
                + " action=" + MotionEvent.actionToString(event.getActionMasked())
                + " source=0x" + Integer.toHexString(event.getSource()));
    }

    private void logKeyForwarding(String boundary, KeyEvent event, Game game, boolean consumed) {
        if (!BuildConfig.DEBUG || event == null) {
            return;
        }

        android.util.Log.i("MoonlightInput", boundary
                + " action=" + keyActionToString(event.getAction())
                + " keyCode=" + KeyEvent.keyCodeToString(event.getKeyCode())
                + " scanCode=" + event.getScanCode()
                + " source=0x" + Integer.toHexString(event.getSource())
                + " deviceId=" + event.getDeviceId()
                + " grabbed=" + (game != null && game.isInputGrabbed())
                + " consumed=" + consumed);
    }

    private static String keyActionToString(int action) {
        switch (action) {
            case KeyEvent.ACTION_DOWN:
                return "ACTION_DOWN";
            case KeyEvent.ACTION_UP:
                return "ACTION_UP";
            case KeyEvent.ACTION_MULTIPLE:
                return "ACTION_MULTIPLE";
            default:
                return Integer.toString(action);
        }
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
        logGenericMotionForwarding("ExternalDisplayControlActivity.onGenericMotionEvent", event);
        handleUserActivity();
        Game game = Game.instance;
        if (game != null) {
            if (shouldRequestGameFocusForControllerInput(true, game.isInPresentationMode(), event.getDeviceId())) {
                requestFocusToGameActivity(false);
            }
            return game.onGenericMotionEvent(event);
        }
        return false;
    }

    @Override
    public boolean onKey(View view, int keyCode, KeyEvent keyEvent) {
        Game game = Game.instance;
        if (game != null) {
            if (shouldRequestGameFocusForControllerInput(true, game.isInPresentationMode(), keyEvent.getDeviceId())) {
                requestFocusToGameActivity(false);
            }
            boolean consumed = forwardControllerKeyEvent(game, keyEvent);
            logKeyForwarding("ExternalDisplayControlActivity.onKey", keyEvent, game, consumed);
            return consumed;
        }

        logKeyForwarding("ExternalDisplayControlActivity.onKey", keyEvent, null, false);
        return false;
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        Game game = Game.instance;
        if (game != null) {
            if (shouldRequestGameFocusForControllerInput(true, game.isInPresentationMode(), event.getDeviceId())) {
                requestFocusToGameActivity(false);
            }
            boolean consumed = forwardControllerKeyEvent(game, event);
            logKeyForwarding("ExternalDisplayControlActivity.onKeyDown", event, game, consumed);
            return consumed;
        }
        logKeyForwarding("ExternalDisplayControlActivity.onKeyDown", event, null, false);
        return false;
    }

    @Override
    public boolean onKeyUp(int keyCode, KeyEvent event) {
        Game game = Game.instance;
        if (game != null) {
            if (shouldRequestGameFocusForControllerInput(true, game.isInPresentationMode(), event.getDeviceId())) {
                requestFocusToGameActivity(false);
            }
            boolean consumed = forwardControllerKeyEvent(game, event);
            logKeyForwarding("ExternalDisplayControlActivity.onKeyUp", event, game, consumed);
            return consumed;
        }
        logKeyForwarding("ExternalDisplayControlActivity.onKeyUp", event, null, false);
        return false;
    }

    @Override
    public boolean onKeyMultiple(int keyCode, int repeatCount, KeyEvent event) {
        Game game = Game.instance;
        if (game != null) {
            if (shouldRequestGameFocusForControllerInput(true, game.isInPresentationMode(), event.getDeviceId())) {
                requestFocusToGameActivity(false);
            }
            boolean consumed = forwardControllerKeyEvent(game, event);
            logKeyForwarding("ExternalDisplayControlActivity.onKeyMultiple", event, game, consumed);
            return consumed;
        }
        logKeyForwarding("ExternalDisplayControlActivity.onKeyMultiple", event, null, false);
        return false;
    }

    @SuppressLint("ClickableViewAccessibility")
    private void createProgrammaticUI() {
        rootLayout = new ExternalControllerView(this);
        rootLayout.setLayoutParams(new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
        rootLayout.setFocusable(true);
        rootLayout.setFocusableInTouchMode(true);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            rootLayout.setFocusedByDefault(true);
        }

        rootLayout.setInputCallbacks(Game.instance);
        rootLayout.setUserActivityCallback(this::handleUserActivity);
        rootLayout.setCommitTextEnabled(prefConfig.enableCommitText);

        setContentView(rootLayout);
        rootLayout.requestFocus();
        activatePresentationControllerIfReady();

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
        topRightButtons.addView(createImageButton(
                R.drawable.ic_close_external,
                v -> requestPresentationEnd(PresentationEndReason.USER_CLOSE)));
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

    private void retargetPresentationCaptureToController(Game game) {
        if (game != null && game.isInPresentationMode() && rootLayout != null) {
            game.replaceInputCaptureTarget(rootLayout);
        }
    }

    private boolean requestControllerTakeover(Game game) {
        if (game == null || !game.isInPresentationMode()
                || !isPresentationStateAlive(presentationState)) {
            return false;
        }

        if (presentationState == PresentationState.LAUNCHING_GAME) {
            presentationState = PresentationState.WAITING_FOR_CONTROLLER_FOCUS;
        }

        LimeLog.info("Presentation controller state=" + presentationState
                + " requesting default-display focus");
        boolean focusRequested = requestFocusToExternalDisplayControl(game);
        activatePresentationControllerIfReady();
        return focusRequested;
    }

    private void activatePresentationControllerIfReady() {
        Game game = Game.instance;
        if (!shouldActivatePresentationController(
                presentationState,
                game != null,
                game != null && game.isInPresentationMode(),
                rootLayout != null && rootLayout.isAttachedToWindow(),
                hasWindowFocus())) {
            return;
        }

        boolean firstActivation = presentationState == PresentationState.WAITING_FOR_CONTROLLER_FOCUS;
        presentationState = PresentationState.CONTROLLER_ACTIVE;
        userLeaving = false;
        rootLayout.requestFocus();
        retargetPresentationCaptureToController(game);
        game.onPresentationControllerWindowFocusChanged(true);
        game.setMetaKeyCaptureState(getComponentName(), true);
        game.onPresentationControllerActivated();
        LimeLog.info("Presentation controller state=" + presentationState
                + " firstActivation=" + firstActivation
                + " windowFocus=" + hasWindowFocus()
                + " rootAttached=" + rootLayout.isAttachedToWindow());
    }

    private void requestPresentationEnd(PresentationEndReason reason) {
        if (!isPresentationStateAlive(presentationState)) {
            if (!isFinishing()) {
                finish();
            }
            return;
        }

        presentationState = PresentationState.ENDING;
        LimeLog.info("Presentation controller ending reason=" + reason);
        Game game = Game.instance;
        if (game != null && game.isInPresentationMode()) {
            game.setMetaKeyCaptureState(getComponentName(), false);
            game.endExternalPresentationSession(reason);
        }
        else if (!isFinishing()) {
            finish();
        }
    }

    private void finishFromGame(PresentationEndReason reason) {
        presentationState = PresentationState.ENDING;
        finishingFromGame = true;
        userLeaving = false;
        Game game = Game.instance;
        if (game != null) {
            game.setMetaKeyCaptureState(getComponentName(), false);
        }
        LimeLog.info("Presentation controller finish from Game reason=" + reason);
        if (!isFinishing()) {
            finish();
        }
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
        button.setFocusable(false);
        button.setFocusableInTouchMode(false);
        button.setOnClickListener(listener);
        button.setLayoutParams(new LinearLayout.LayoutParams(dpToPx(56), dpToPx(56)));
        return button;
    }

    // --- Utility Methods ---

    private int dpToPx(int dp) {
        return (int) (dp * getResources().getDisplayMetrics().density);
    }
}
