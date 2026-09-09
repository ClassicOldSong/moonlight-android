package com.limelight;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.ContextThemeWrapper;
import android.view.View;
import android.view.ViewTreeObserver;
import android.view.Window;
import android.widget.ArrayAdapter;
import android.widget.Toast;

import com.limelight.binding.input.GameInputDevice;
import com.limelight.binding.input.KeyboardTranslator;
import com.limelight.preferences.PreferenceConfiguration;
import com.limelight.utils.KeyConfigHelper;
import com.limelight.utils.KeyMapper;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Provide options for ongoing Game Stream.
 * <p>
 * Shown on back action in game activity.
 */
public class GameMenu implements Game.GameMenuCallbacks {

    public static final long KEY_UP_DELAY = 25;
    private static final long TEST_GAME_FOCUS_DELAY = 10;

    public static final String PREF_NAME = "specialPrefs"; // SharedPreferences的名称

    public static final String KEY_NAME = "special_key"; // 要保存的键名称

    public static class MenuOption {
        private final String label;
        private final boolean withGameFocus;
        private final Runnable runnable;

        public MenuOption(String label, boolean withGameFocus, Runnable runnable) {
            this.label = label;
            this.withGameFocus = withGameFocus;
            this.runnable = runnable;
        }

        public MenuOption(String label, Runnable runnable) {
            this(label, false, runnable);
        }
    }

    private final Game game;
    private final Context dialogScreenContext;

    private AlertDialog currentDialog;

    public GameMenu(Game game, Context dialogScreenContext) {
        this.game = game;
        this.dialogScreenContext = dialogScreenContext;
    }

    public GameMenu(Game game) {
        this.game = game;
        this.dialogScreenContext = game;
    }

    private String getString(int id) {
        return game.getResources().getString(id);
    }


    private void sendKeys(short[] keys) {
        game.sendKeys(keys);
    }

    private void runWithGameFocus(Runnable runnable) {
        // Ensure that the Game activity is still active (not finished)
        if (game.isFinishing()) {
            return;
        }
        // Check if the game window has focus again, if not try again after delay
        if (!game.hasWindowFocus() && dialogScreenContext instanceof Game) {
            new Handler().postDelayed(() -> runWithGameFocus(runnable), TEST_GAME_FOCUS_DELAY);
            return;
        }
        // Game Activity has focus, run runnable
        runnable.run();
    }

    private void run(MenuOption option) {
        if (option.runnable == null) {
            return;
        }

        if (option.withGameFocus) {
            runWithGameFocus(option.runnable);
        } else {
            option.runnable.run();
        }
    }

    private void showMenuDialog(String title, MenuOption[] options) {
        int themeResId = game.getApplicationInfo().theme;

        Context themedContext = new ContextThemeWrapper(dialogScreenContext, themeResId);
        AlertDialog.Builder builder = new AlertDialog.Builder(themedContext);
        builder.setTitle(title);

        final ArrayAdapter<String> actions = new ArrayAdapter<>(themedContext, android.R.layout.simple_list_item_1);

        builder.setAdapter(actions, (dialog, which) -> {
            String label = actions.getItem(which);
            for (MenuOption option : options) {
                if (label != null && label.equals(option.label)) {
                    run(option);
                    break;
                }
            }
        });

        builder.setOnCancelListener(dialog -> hideMenu());

        if (currentDialog != null) {
            currentDialog.dismiss();
        }
        currentDialog = builder.show();

        Window window = currentDialog.getWindow();

        if (window != null) {
            View decorView = window.getDecorView();
            decorView.getViewTreeObserver().addOnGlobalLayoutListener(new ViewTreeObserver.OnGlobalLayoutListener() {
                @Override
                public void onGlobalLayout() {

                    decorView.getViewTreeObserver().removeOnGlobalLayoutListener(this);

                    new Handler(Looper.getMainLooper()).post(() -> {
                        for (MenuOption option : options) {
                            actions.add(option.label);
                        }
                        actions.notifyDataSetChanged();
                    });
                }
            });
        }
    }

    private void showSpecialKeysMenu() {
        List<MenuOption> options = new ArrayList<>();

        if(!PreferenceConfiguration.readPreferences(game).disableDefaultExtraKeys){
            options.add(new MenuOption(getString(R.string.game_menu_send_keys_esc),
                    () -> sendKeys(new short[]{KeyboardTranslator.VK_ESCAPE})));

            options.add(new MenuOption(getString(R.string.game_menu_send_keys_f11),
                    () -> sendKeys(new short[]{KeyboardTranslator.VK_F11})));

            options.add(new MenuOption(getString(R.string.game_menu_send_keys_alt_f4),
                    () -> sendKeys(new short[]{KeyboardTranslator.VK_LMENU, KeyboardTranslator.VK_F4})));

            options.add(new MenuOption(getString(R.string.game_menu_send_keys_alt_enter),
                    () -> sendKeys(new short[]{KeyboardTranslator.VK_LMENU, KeyboardTranslator.VK_RETURN})));

            options.add(new MenuOption(getString(R.string.game_menu_send_keys_ctrl_v),
                    () -> sendKeys(new short[]{KeyboardTranslator.VK_LCONTROL, KeyboardTranslator.VK_V})));

            options.add(new MenuOption(getString(R.string.game_menu_send_keys_win_shift_s),
                    () -> sendKeys(new short[]{KeyboardTranslator.VK_LWIN, KeyboardTranslator.VK_LSHIFT, KeyboardTranslator.VK_S})));

            options.add(new MenuOption(getString(R.string.game_menu_send_keys_win),
                    () -> sendKeys(new short[]{KeyboardTranslator.VK_LWIN})));

            options.add(new MenuOption(getString(R.string.game_menu_send_keys_win_d),
                    () -> sendKeys(new short[]{KeyboardTranslator.VK_LWIN, KeyboardTranslator.VK_D})));

            options.add(new MenuOption(getString(R.string.game_menu_send_keys_win_tab),
                    () -> sendKeys(new short[]{KeyboardTranslator.VK_LWIN, KeyboardTranslator.VK_TAB})));

            options.add(new MenuOption(getString(R.string.game_menu_send_keys_win_g),
                    () -> sendKeys(new short[]{KeyboardTranslator.VK_LWIN, KeyboardTranslator.VK_G})));

            options.add(new MenuOption(getString(R.string.game_menu_send_keys_ctrl_alt_tab),
                    () -> sendKeys(new short[]{KeyboardTranslator.VK_LCONTROL, KeyboardTranslator.VK_LMENU, KeyboardTranslator.VK_TAB})));

            options.add(new MenuOption(getString(R.string.game_menu_send_keys_shift_tab),
                    () -> sendKeys(new short[]{KeyboardTranslator.VK_LSHIFT, KeyboardTranslator.VK_TAB})));

            options.add(new MenuOption(getString(R.string.game_menu_send_keys_win_shift_left),
                    () -> sendKeys(new short[]{KeyboardTranslator.VK_LWIN, KeyboardTranslator.VK_LSHIFT, KeyboardTranslator.VK_LEFT})));

            options.add(new MenuOption(getString(R.string.game_menu_send_keys_ctrl_alt_shift_f1),
                    () -> sendKeys(new short[]{KeyboardTranslator.VK_LCONTROL,KeyboardTranslator.VK_LMENU, KeyboardTranslator.VK_LSHIFT, KeyboardTranslator.VK_F1})));

            options.add(new MenuOption(getString(R.string.game_menu_send_keys_ctrl_alt_shift_f12),
                    () -> sendKeys(new short[]{KeyboardTranslator.VK_LCONTROL,KeyboardTranslator.VK_LMENU, KeyboardTranslator.VK_LSHIFT, KeyboardTranslator.VK_F12})));

            options.add(new MenuOption(getString(R.string.game_menu_send_keys_alt_b),
                    () -> sendKeys(new short[]{KeyboardTranslator.VK_LWIN, KeyboardTranslator.VK_LMENU, KeyboardTranslator.VK_B})));
        }

        // Import custom shortcuts
        SharedPreferences preferences = game.getSharedPreferences(PREF_NAME, Activity.MODE_PRIVATE);
        String value = preferences.getString(KEY_NAME,"");

        if(!TextUtils.isEmpty(value)){
            try {
                KeyConfigHelper.ShortcutFile shortcutFile = KeyConfigHelper.parseShortcutFile(value);
                if (shortcutFile != null && shortcutFile.data != null && !shortcutFile.data.isEmpty()) {
                    List<KeyConfigHelper.Shortcut> data = shortcutFile.data;
                    for (KeyConfigHelper.Shortcut sc : data) {
                        List<String> keys = sc.keys;
                        short[] keyCodes = new short[keys.size()];

                        for (int i = 0; i < keys.size(); i++) {
                            String code = keys.get(i);
                            int keycode;

                            if (code.startsWith("0x")) {               // literal hex value
                                keycode = Integer.parseInt(code.substring(2), 16);
                            } else if (code.startsWith("VK_")) {       // symbolic constant in KeyMapper
                                Field field = KeyMapper.class.getDeclaredField(code);
                                keycode = field.getInt(null);
                            } else {                                   // unsupported
                                throw new IllegalArgumentException("Unknown key code: " + code);
                            }
                            keyCodes[i] = (short) keycode;
                        }

                        // Whatever MenuOption looks like in your project
                        MenuOption option = new MenuOption(sc.name, () -> sendKeys(keyCodes));
                        options.add(option);
                    }
                }
            } catch (Exception e) {
                e.printStackTrace();
                Toast.makeText(game,getString(R.string.wrong_import_format),Toast.LENGTH_SHORT).show();
            }
        }
        options.add(new MenuOption(getString(R.string.game_menu_cancel), null));

        showMenuDialog(getString(R.string.game_menu_send_keys), options.toArray(new MenuOption[options.size()]));
    }

    private void showAdvancedMenu(GameInputDevice device) {
        List<MenuOption> options = new ArrayList<>();
        if (game.allowChangeMouseMode) {
            options.add(new MenuOption(getString(R.string.game_menu_select_mouse_mode), true, () -> game.selectMouseMode(dialogScreenContext)));
        }
        
        options.add(new MenuOption(getString(R.string.game_menu_toggle_hud), true, game::toggleHUD));
        options.add(new MenuOption(getString(R.string.game_menu_toggle_floating_button), true, game::toggleFloatingButtonVisibility));
        options.add(new MenuOption(getString(R.string.game_menu_toggle_keyboard_model), true, game::toggleKeyboardController));
        if (!game.isOnExternalDisplay()) {
            options.add(new MenuOption(getString(R.string.game_menu_toggle_virtual_model), true, game::toggleVirtualController));
        }
        options.add(new MenuOption(getString(R.string.game_menu_toggle_virtual_keyboard_model), true, game::toggleFullKeyboard));
        options.add(new MenuOption(getString(R.string.game_menu_task_manager), true, () -> sendKeys(new short[]{KeyboardTranslator.VK_LCONTROL, KeyboardTranslator.VK_LSHIFT, KeyboardTranslator.VK_ESCAPE})));

        // **FIXED:** This is a UI navigation action, so it should not use withGameFocus.
        options.add(new MenuOption(getString(R.string.game_menu_send_keys), () -> {
            hideMenu();
            showSpecialKeysMenu();
        }));

        options.add(new MenuOption(getString(R.string.game_menu_switch_touch_sensitivity_model), true, game::switchTouchSensitivity));
        if (device != null) {
            options.addAll(device.getGameMenuOptions());
        }
        options.add(new MenuOption(getString(R.string.game_menu_cancel), null));
        showMenuDialog(getString(R.string.game_menu_advanced), options.toArray(new MenuOption[options.size()]));
    }

    private void showOscMenu() {
        List<MenuOption> options = new ArrayList<>();

        // 1. Set Configuration Mode
        options.add(new MenuOption(getString(R.string.game_menu_osc_set_config_mode), () -> {
            hideMenu();
            showOscModeMenu();
        }));

        // 2. Toggle OSC Visibility
        options.add(new MenuOption(getString(R.string.game_menu_osc_toggle_visibility), true,
                game::toggleVirtualController));

        // 3. Toggle Cover Triggers Mode
        options.add(new MenuOption(getString(R.string.game_menu_osc_toggle_cover_triggers), true,
                game::toggleCoverAnalogTriggers));

        // 4. Toggle Analog Trigger Click
        options.add(new MenuOption(getString(R.string.game_menu_osc_toggle_trigger_click), true,
                game::toggleAnalogTriggerClick));

        // 5. Clear OSC Layout
        options.add(new MenuOption(getString(R.string.game_menu_osc_clear_layout), true,
                game::resetOscLayout));

        // 6. Deposit Alternate Buttons submenu
        options.add(new MenuOption(getString(R.string.game_menu_osc_deposit_buttons), () -> {
            hideMenu();
            showOscDepositButtonsMenu();
        }));

        // 7. OSC Profiles
        options.add(new MenuOption(getString(R.string.game_menu_osc_profiles), () -> {
            hideMenu();
            showOscProfilesMenu();
        }));

        // 8. Cancel
        options.add(new MenuOption(getString(R.string.game_menu_cancel), null));

        showMenuDialog(getString(R.string.game_menu_osc_settings), options.toArray(new MenuOption[options.size()]));
    }

    private void showOscModeMenu() {
        List<MenuOption> options = new ArrayList<>();

        // OSC Configuration Modes
        options.add(new MenuOption(getString(R.string.game_menu_osc_mode_active), true,
                game::setOscModeActive));

        options.add(new MenuOption(getString(R.string.game_menu_osc_mode_move), true,
                game::setOscModeMove));

        options.add(new MenuOption(getString(R.string.game_menu_osc_mode_resize), true,
                game::setOscModeResize));

        options.add(new MenuOption(getString(R.string.game_menu_osc_mode_disable_enable), true,
                game::setOscModeDisableEnable));

        options.add(new MenuOption(getString(R.string.game_menu_cancel), null));

        showMenuDialog(getString(R.string.game_menu_osc_set_mode), options.toArray(new MenuOption[options.size()]));
    }

    private void showOscDepositButtonsMenu() {
        List<MenuOption> options = new ArrayList<>();

        // Deposit button sets
        options.add(new MenuOption(getString(R.string.game_menu_osc_deposit_alphabet), true,
                game::depositAlphabetButtons));

        options.add(new MenuOption(getString(R.string.game_menu_osc_deposit_numbers), true,
                game::depositNumberButtons));

        options.add(new MenuOption(getString(R.string.game_menu_osc_deposit_mouse), true,
                game::depositMouseButtons));

        options.add(new MenuOption(getString(R.string.game_menu_osc_deposit_control), true,
                game::depositControlButtons));

        options.add(new MenuOption(getString(R.string.game_menu_osc_deposit_function), true,
                game::depositFunctionKeys));

        options.add(new MenuOption(getString(R.string.game_menu_cancel), null));

        showMenuDialog(getString(R.string.game_menu_osc_deposit_buttons), options.toArray(new MenuOption[options.size()]));
    }

    private void showOscProfilesMenu() {
        com.limelight.LimeLog.info("GameMenu: showOscProfilesMenu() called");
        List<MenuOption> options = new ArrayList<>();

        // Add profile selector dropdown as first option
        options.add(new MenuOption(getString(R.string.game_menu_osc_profile_select), true,
                this::showProfileSelector));

        // Add profile management options
        options.add(new MenuOption(getString(R.string.game_menu_osc_profile_new),
                game::createNewOscProfile));

        options.add(new MenuOption(getString(R.string.game_menu_osc_profile_manage),
                game::openOscProfileManagement));

        options.add(new MenuOption(getString(R.string.game_menu_osc_save_config), true,
                game::saveCurrentOscConfiguration));

        options.add(new MenuOption(getString(R.string.game_menu_cancel), null));

        showMenuDialog(getString(R.string.game_menu_osc_profiles), options.toArray(new MenuOption[options.size()]));
    }

    private void showProfileSelector() {
        // Load all profiles from OscProfilesManager
        com.limelight.binding.input.virtual_controller.OscProfilesManager profilesManager =
                com.limelight.binding.input.virtual_controller.OscProfilesManager.getInstance();

        List<com.limelight.binding.input.virtual_controller.OscProfile> profiles = profilesManager.getProfiles();
        java.util.UUID activeId = profilesManager.getActiveId();

        // Create array of profile names
        String[] profileNames = new String[profiles.size()];
        int selectedIndex = 0;
        for (int i = 0; i < profiles.size(); i++) {
            com.limelight.binding.input.virtual_controller.OscProfile profile = profiles.get(i);
            profileNames[i] = profile.getName();
            if (profile.getUuid().equals(activeId)) {
                selectedIndex = i;
            }
        }

        // Track selected profile
        final int[] checkedItem = {selectedIndex};

        // Create dialog with single choice list
        int themeResId = game.getApplicationInfo().theme;
        Context themedContext = new ContextThemeWrapper(dialogScreenContext, themeResId);

        android.app.AlertDialog.Builder builder = new android.app.AlertDialog.Builder(themedContext);
        builder.setTitle(getString(R.string.game_menu_osc_profile_select))
                .setSingleChoiceItems(profileNames, selectedIndex, (dialog, which) -> {
                    checkedItem[0] = which;
                })
                .setPositiveButton(android.R.string.ok, (dialog, which) -> {
                    if (checkedItem[0] >= 0 && checkedItem[0] < profiles.size()) {
                        java.util.UUID profileId = profiles.get(checkedItem[0]).getUuid();
                        game.switchOscProfile(profileId);
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void showServerCmd(ArrayList<String> serverCmds) {
        List<MenuOption> options = new ArrayList<>();

        AtomicInteger index = new AtomicInteger(0);
        for (String str : serverCmds) {
            final int finalI = index.getAndIncrement();
            options.add(new MenuOption("> " + str, true, () -> game.sendExecServerCmd(finalI)));
        };

        options.add(new MenuOption(getString(R.string.game_menu_cancel), null));

        showMenuDialog(getString(R.string.game_menu_server_cmd), options.toArray(new MenuOption[options.size()]));
    }

    public void showMenu(GameInputDevice device) {
        List<MenuOption> options = new ArrayList<>();

        options.add(new MenuOption(getString(R.string.game_menu_disconnect), game::disconnect));

        options.add(new MenuOption(getString(R.string.game_menu_quit_session), game::quit));

        options.add(new MenuOption(getString(R.string.game_menu_upload_clipboard), true,
                () -> game.sendClipboard(true)));

        options.add(new MenuOption(getString(R.string.game_menu_fetch_clipboard), true,
                () -> game.getClipboard(0)));

        options.add(new MenuOption(getString(R.string.game_menu_server_cmd), true,
                () -> {
                    ArrayList<String> serverCmds = game.getServerCmds();
                    if (serverCmds.isEmpty()) {
                        int themeResId = game.getApplicationInfo().theme;
                        Context themedContext = new ContextThemeWrapper(dialogScreenContext, themeResId);
                        new AlertDialog.Builder(themedContext)
                                .setTitle(R.string.game_dialog_title_server_cmd_empty)
                                .setMessage(R.string.game_dialog_message_server_cmd_empty)
                                .show();
                    } else {
                        hideMenu();
                        this.showServerCmd(serverCmds);
                    }
                }));

        options.add(new MenuOption(getString(R.string.game_menu_toggle_keyboard), true,
                game::toggleKeyboard));

        options.add(new MenuOption(getString(game.isZoomModeEnabled() ? R.string.game_menu_disable_zoom_mode : R.string.game_menu_enable_zoom_mode), true,
                game::toggleZoomMode));

        if (dialogScreenContext == game) {
            options.add(new MenuOption(getString(R.string.game_menu_rotate_screen), true,
                    game::rotateScreen));
        }

        options.add(new MenuOption(getString(R.string.game_menu_advanced), true,
                () -> showAdvancedMenu(device)));

        // OSC Menu - positioned before Cancel as per fig4.png
        options.add(new MenuOption(getString(R.string.game_menu_osc), () -> {
            hideMenu();
            showOscMenu();
        }));

        options.add(new MenuOption(getString(R.string.game_menu_cancel), null));

        showMenuDialog(getString(R.string.quick_menu_title), options.toArray(new MenuOption[options.size()]));
    }

    @Override
    public void hideMenu() {
        if (currentDialog != null && currentDialog.isShowing()) {
            currentDialog.dismiss();
        }
        currentDialog = null;
    }

    @Override
    public boolean isMenuOpen() {
        return currentDialog != null && currentDialog.isShowing();
    }
}
