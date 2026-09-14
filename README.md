# Artemis Android

Previously named Moonlight Noir

An open source client for [Apollo](https://github.com/ClassicOldSong/Apollo)/[Sunshine](https://github.com/LizardByte/Sunshine).

Artemis Android will allow you to stream your collection of games from your Windows PC to your Android device,
whether in your own home or over the internet.

Artemis is currently the best fork of Moonlight with loads of optimizations for office usage.

A more seamless experience with virtual display will be Artemis paired with [Apollo](https://github.com/ClassicOldSong/Apollo).

# Features

If you switch back to the main stream version, you'll be missing the following awesome features which are very unlikely to be added there:

1. Custom virtual buttons with import and export support.
2. [Custom resolutions](https://github.com/moonlight-stream/moonlight-android/pull/1349).
3. Custom bitrates.
4. [Multiple mouse mode switching](https://github.com/moonlight-stream/moonlight-android/pull/1304) (normal mouse, [multi-touch](https://github.com/moonlight-stream/moonlight-android/pull/1364), touchpad, disabled, local cursor mode).
5. Optimized virtual gamepad skins and free joystick.
6. External monitor mode.
7. Joycon D-pad support.
8. Simplified performance information display.
9. [Game back menu](https://github.com/moonlight-stream/moonlight-android/pull/1171).
10. Custom shortcut commands.
11. Easy soft keyboard switching.
12. Portrait mode.
13. Display on top mode, useful for foldable phones.
14. [Virtual touchpad space and sensitivity adjustment](https://github.com/moonlight-stream/moonlight-android/issues/1348#issuecomment-2236344729) for playing right-click view games, such as Warcraft.
15. Force use device's own vibration motor (in case your gamepad's vibration is not effective).
16. Gamepad debugging page to view gamepad vibration and gyroscope information, as well as Android kernel version information.
17. Trackpad tap/scrolling support
18. Natural track pad mode with touch screen
19. Non-QWERTY keyboard layout support
20. Quick Meta key with physical BACK button
21. Frame rate lock fix for some devices
22. Video scale mode: Fit/Fill/Stretch
23. View pan/zoom support
24. Rotate screen in-game
25. Add option to quit app directly
26. Samsung DeX scrolling support
27. Proper click/scroll/right-click for trackpad on generic Android tablet when using local cursor
28. Virtual Display integration with [Apollo](https://github.com/ClassicOldSong/Apollo)
29. Server Command integration with [Apollo](https://github.com/ClassicOldSong/Apollo)
30. Clipboard sync (requires Apollo)
31. SBS 3D for external Displays (Using AI MiDaS v2 Lite)

# Disclaimer

This is the `go away` version of Moonlight Android.

I got kicked from Moonlight and Sunshine's Discord server literally for helping people out.

This is what I got for finding a bug, opened an issue, getting no response, troubleshoot myself, fixed the issue myself, shared it by PR to the main repo hoping my efforts can help someone else during the maintainance gap.

Yes, I'm going away. Fixes and improvements on this fork are not necessarily be merged to the main repo either. I have also started [a fork of Sunshine called Apollo](https://github.com/ClassicOldSong/Apollo) and will add useful features that will never get merged by the main repo shortly. [Apollo](https://github.com/ClassicOldSong/Apollo) and [Moonlight Noir](https://github.com/ClassicOldSong/moonlight-android) will no longer be compatible with OG Sunshine and OG Moonlight eventually, but they'll work even better with much more carefully designed features.

The main repo had stayed silent for 5 months, with nobody actually responding to issues, and people are getting totally no help besides the limited FAQ in their Discord server. I tried to answer issues and questions, solve problems within my ablilty but I got kicked out just for helping others.

**PRs for feature improvements are welcomed here unlike the main repo, your ideas are more likely to be appreciated and your efforts are actually being respected. We welcome people who can and willing to share their efforts, helping yourselves and other people in need.**

**Update**: They have contacted me and apologized for this incident, but the fact it **happened** still motivated me to start my own fork.

## Downloads
* [Download APK directly](https://github.com/ClassicOldSong/moonlight-android/releases)
* [Use Obtainium](https://apps.obtainium.imranr.dev/redirect?r=obtainium://app/%7B%22id%22%3A%22com.limelight.noir%22%2C%22url%22%3A%22https%3A%2F%2Fgithub.com%2FClassicOldSong%2Fmoonlight-android%22%2C%22author%22%3A%22ClassicOldSong%22%2C%22name%22%3A%22Artemis%22%2C%22additionalSettings%22%3A%22%7B%5C%22apkFilterRegEx%5C%22%3A%5C%22nonRoot%5C%22%2C%5C%22matchGroutToUse%5C%22%3A%5C%22%241%5C%22%2C%5C%22versionExtractionRegEx%5C%22%3A%5C%22v(.%2B)%5C%22%7D%22%7D) (recommended)

## Building
* Install Android Studio and the Android NDK
* Run ‘git submodule update --init --recursive’ from within moonlight-android/
* In moonlight-android/, create a file called ‘local.properties’. Add an ‘ndk.dir=’ property to the local.properties file and set it equal to your NDK directory.
* Build the APK using Android Studio or gradle

## Troubleshooting

### Physical keyboard shortcuts (Alt+Tab, Win key, etc.) don't reach the host PC

Android intercepts certain keyboard shortcuts (Alt+Tab, Alt+Esc, Win, Ctrl+Esc, etc.) at the system level — they never reach the streaming session by default. Artemis ships with a small Accessibility service (`Artemis Physical keyboard`) whose only job is to forward those events to the host while you're streaming. You only need this if you stream with a physical keyboard.

Artemis will prompt you to enable this service the first time it detects a physical keyboard at app start. If you dismissed the prompt or want to enable it manually, there's also a button in **Settings → Input Settings → Forward system keyboard shortcuts**.

#### 1. Normal path

1. Open **Settings → Accessibility** on the device.
2. Find **Artemis Physical keyboard** in the list of installed services.
3. Toggle it on. Confirm the system warning about full device control.

#### 2. If the toggle won't stay on

On Android 13+, services in apps installed via sideload / ADB / Obtainium are blocked by **Restricted settings**. The toggle will appear to flip on, then quietly turn off again.

To unblock:

1. Open **Settings → Apps → Artemis** (the app's info page).
2. Tap the **⋮ (overflow menu)** in the top-right corner.
3. Choose **Allow restricted settings** (or **Restricted settings** → confirm).
4. Go back to **Accessibility** and toggle Artemis Physical keyboard on.

#### 3. If "Allow restricted settings" doesn't appear (some OEMs hide it)

Some custom Android skins (notably ZUI on Lenovo tablets) hide the **Allow restricted settings** entry entirely. Use ADB as a fallback:

1. Enable **Developer options → USB debugging** on the device.
2. Connect the device to a PC with `adb` installed and run:

```bash
# 1. Bypass the restricted-settings block for Artemis
adb shell appops set com.limelight.noir ACCESS_RESTRICTED_SETTINGS allow

# 2. Capture the current list of enabled accessibility services (do not lose this)
adb shell settings get secure enabled_accessibility_services

# 3. Append our service to that list (replace <existing> with the value from step 2;
#    keep the colons separating each service)
adb shell settings put secure enabled_accessibility_services "<existing>:com.limelight.noir/com.limelight.KeyboardAccessibilityService"

# 4. Toggle accessibility off/on so the service actually binds
adb shell settings put secure accessibility_enabled 0
adb shell settings put secure accessibility_enabled 1

# 5. Verify the service is bound (look for "Artemis Physical keyboard" with capabilities=9)
adb shell dumpsys accessibility | grep "label="
```

If you're using the debug build, replace `com.limelight.noir` with `com.limelight.noirdebug`.

#### Why is this needed?

Android does not provide a public API for an app to capture system-level keyboard shortcuts at the activity level. The only sanctioned way to intercept events like Alt+Tab before SystemUI consumes them is via an Accessibility service with `FLAG_REQUEST_FILTER_KEY_EVENTS`. Artemis uses this purely to forward keys to the host while a stream is active — it does not read on-screen text, monitor your input outside of streaming, or collect any data.

## Authors

* [Cameron Gutman](https://github.com/cgutman)  
* [Diego Waxemberg](https://github.com/dwaxemberg)  
* [Aaron Neyer](https://github.com/Aaronneyer)  
* [Andrew Hennessy](https://github.com/yetanothername)

Moonlight is the work of students at [Case Western](http://case.edu) and was
started as a project at [MHacks](http://mhacks.org).
