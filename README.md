# Diana OSC Suite

A feature-rich fork of [Artemis](https://github.com/ClassicOldSong/moonlight-android) (which itself is a fork of [Moonlight Android](https://github.com/moonlight-stream/moonlight-android)) with extensive On-Screen Controller (OSC) enhancements and foldable device support.

## What is Diana?

Diana is an Android game streaming client that connects to NVIDIA GameStream servers or [Sunshine](https://github.com/LizardByte/Sunshine) to stream PC games to your Android device. Diana focuses on providing the best possible on-screen controller experience with advanced customization, intelligent layout assistance, and special support for foldable devices.

## Key Features

### 🎮 Advanced On-Screen Controller

#### Smart Snapping System
Intelligent button positioning assistance that helps you align controls perfectly:
- **Edge Snapping**: Buttons snap to screen edges (left, right, top, bottom)
- **Grid Snapping**: Buttons snap to 25%, 30%, 50%, 70%, 75% intervals on both axes
- **Button-to-Button Snapping**: Buttons snap to other buttons in the same subset
  - Snaps to edges, centers, and adjacent positions
  - Organized by button type (Face Buttons, Shoulder Buttons, Triggers, D-Pad, Sticks, etc.)
  - 40-pixel snap threshold for smooth positioning

**How to use**:
1. Enter Move mode (tap settings button during gameplay)
2. Press **Volume Down** to toggle snapping on/off
3. Drag buttons to move them - they'll snap to alignment points
4. Toast notification confirms snapping state

#### Paired Sizing System
Keep related buttons uniform in size automatically:
- When resizing any button in a subset, all buttons in that subset match the new size
- Maintains aspect ratio and natural resize feel
- Works with Face Buttons (A/B/X/Y), Triggers (LT/RT), Shoulder Buttons (LB/RB), and more

**How to use**:
1. Enter Resize mode (cycle settings button to Resize)
2. Press **Volume Up** to toggle paired sizing on/off
3. Resize any button - all related buttons resize together
4. Toast notification confirms paired sizing state

#### OSC Profile Management
Save and switch between multiple controller layouts:
- Create unlimited profiles with custom names
- Each profile stores independent button positions, sizes, and enabled states
- Switch profiles during gameplay
- Manual save system - you control when configurations are saved
- Profiles persist across app restarts

**How to use**:
1. Open Game Menu → "OSC Profiles"
2. Tap "Add New Profile" to create a new layout
3. Configure your buttons (move, resize, enable/disable)
4. Tap "Save Current Config" to save changes
5. Switch between profiles by tapping a profile name → "Switch to This Profile"

#### Deposited Button Sets
Add virtual keyboard and mouse buttons to your controller:
- **Alphabet Keys**: A-Z
- **Number Keys**: 0-9
- **Mouse Buttons**: Left, Right, Middle, X1, X2
- **Control Keys**: Esc, Tab, Backspace, Enter, Shift, Ctrl, Alt, Space, Arrow Keys

**How to use**:
1. Open Game Menu → "OSC Configuration" → "Deposit Alternate Buttons"
2. Select which button set to add (e.g., "Deposit Control Keys")
3. Buttons appear on screen and can be moved/resized like any other button
4. Saved with profiles

#### Profile Name Overlay
Always know which profile you're using:
- Profile name displays in bottom-left corner during configuration modes
- Automatically shows when entering Move, Resize, or Disable/Enable modes
- Hides in Active mode to avoid clutter

### 📱 Foldable Device Support (Cover Screen)

#### Cover Screen Virtual Controller
Use your foldable's cover screen as a dedicated controller surface:
- **Automatic Detection**: Detects Samsung Galaxy Z Flip and other foldables automatically
- **4-Button Layout**: ZL (Left Trigger), LB (Left Bumper), RB (Right Bumper), ZR (Right Trigger)
- **Multi-Touch Support**: Each finger independently controls buttons
- **Slide-to-Press**: Slide from anywhere on the cover screen onto buttons to activate
- **Mode Synchronization**: Configuration modes sync between main screen and cover screen

**How to use**:
1. Fold your device to cover screen mode
2. Cover screen automatically activates with trigger buttons
3. Rest multiple fingers on the cover and slide onto buttons when needed
4. Configure layout from main screen - changes sync automatically

#### Analog Trigger Emulation
Cover screen triggers support full analog input (0-255):
- **Slide Mechanics**: Slide inward (both triggers slide toward center)
  - ZL slides RIGHT, ZR slides LEFT
- **0-85% Range**: Maps to analog values 0-254
- **85% Snap Threshold**: Auto-snaps to max (255) with feedback
  - Custom mechanical click sound (10% volume)
  - Haptic feedback: 50ms pause + 150ms strong buzz
  - One-time effect per slide
- **Continuous Vibration**: Intensity proportional to pressure (0-254)
- **Visual Feedback**: Brightness dims to bright (10% → 100%) as you press
- **Real-time Value Display**: Shows current analog value on button

**Toggle between Analog/Digital**:
- Open Game Menu → "Toggle Cover Triggers Mode"
- Analog mode: Slide for variable pressure
- Digital mode: Tap for on/off

#### Visual Feedback Indicator
See your cover screen inputs on the main display:
- **4-Color Display**: Left to right: ZL (blue), LB (purple), RB (red), ZR (orange)
- **Analog Reactivity**: Trigger brightness tracks pressure (10% → 100%)
- **Digital States**: Bumpers show 30% (unpressed) or 100% (pressed)
- **OSC Integration**: Movable, resizable, can be disabled like other elements
- Automatically enabled when cover screen activates

### ⚙️ Configuration Tools

#### Configuration Modes
Four modes accessible via the settings button (top-left during gameplay):
1. **Active Mode**: Normal gameplay, all buttons functional
2. **Disable/Enable Mode**: Tap buttons to toggle visibility (green = enabled, gray = disabled)
3. **Move Mode**: Drag buttons to reposition (red borders)
4. **Resize Mode**: Drag from corners to resize (magenta borders)

Cycle through modes by repeatedly tapping the settings button.

## Installation

1. Download the latest APK from [Releases](../../releases)
2. Install on your Android device (Android 5.0+, foldable features require Android 7.0+)
3. Grant necessary permissions
4. Configure your PC connection
5. Start streaming!

## Usage Tips

### First Time Setup
1. Configure your Sunshine/GameStream server
2. Pair your Android device with your PC
3. Create your first OSC profile with a descriptive name
4. Position buttons where comfortable
5. Save your configuration

### For Foldable Users
1. Start streaming on the main screen
2. Fold device to cover screen mode
3. Cover screen triggers activate automatically
4. Use main screen for game view, cover screen for controls
5. Rest fingers on cover screen, slide to activate buttons as needed

### Optimizing Your Layout
1. Use Smart Snapping to align buttons perfectly
2. Enable Paired Sizing to keep related buttons uniform
3. Save multiple profiles for different game types
4. Deposit keyboard keys for games that need them

## Build Information

- **Current Version**: v0.55-multitouch (Build 81)
- **Branch**: diana-oscsuite-debug
- **Min SDK**: 21 (Android 5.0)
- **Target SDK**: 34 (Android 14)
- **Compile SDK**: 36 (Android 15)

## Credits

- **Original Project**: [Moonlight Android](https://github.com/moonlight-stream/moonlight-android) by Cameron Gutman and contributors
- **Upstream Fork**: [Artemis](https://github.com/ClassicOldSong/moonlight-android) by ClassicOldSong
- **Diana OSC Suite**: Extended OSC features and foldable support

## License

Diana OSC Suite inherits the license from Moonlight Android. See the original project for license details.

## Contributing

This is a personal fork focused on OSC enhancements. If you encounter bugs or have feature suggestions related to on-screen controls or foldable device support, feel free to open an issue.

---

**Note**: This is a fork of Artemis with a focus on On-Screen Controller features. All original Artemis and Moonlight functionality is preserved.
