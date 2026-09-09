# Diana OSC Suite

A fork of [Artemis](https://github.com/ClassicOldSong/moonlight-android) with comprehensive On-Screen Controller (OSC) enhancements and foldable device support.

## What Makes Diana Different?

Diana extends Artemis with two major feature sets:

### 1. Robust OSC Profile System
- **Per-Game Profiles**: Set default OSC layouts per game - automatically loads when you start each game
- **Unlimited Profiles**: Create, save, and switch between controller layouts on-the-fly
- **Paired Sizing**: Resize all buttons in a subset together (Face Buttons, Triggers, Shoulders, etc.)
- **Smart Snapping**: Buttons snap to edges, grid intervals, and other buttons for perfect alignment
- **Deposited Buttons**: Add keyboard keys (A-Z, 0-9), mouse buttons, and control keys to your layout
- **Profile Overlay**: Always know which profile you're using during configuration

### 2. Foldable Cover Screen Support
- **Dedicated Trigger Controller**: 4-button layout (ZL/LB/RB/ZR) optimized for cover screens
- **Analog Trigger Emulation**: Full 0-255 pressure sensitivity with slide buttons
- **Haptic + Audio Feedback**: Mechanical click sound and vibration at snap threshold
- **Visual Indicators**: Main-screen display showing cover button states and pressure levels

## Quick Start

### OSC Profile Management

**Set Per-Game Default Profiles**:
1. Long-press a game in your game list
2. Tap "OSC Profile"
3. Select a profile - it will auto-load when starting that game

**Create & Manage Profiles** (In-Game):
1. Open Quick Menu → "OSC Profiles"
2. Create new profiles with custom names
3. Configure buttons (move/resize/enable-disable)
4. Save changes manually (Under Manage Profiles)
5. Switch between profiles instantly

**Profile Features**:
- **Snapping** (Volume Down in Move mode): Align buttons to edges/grid/other buttons
- **Paired Sizing** (Volume Up in Resize mode): Resize button groups together
- **Deposit Buttons**: Add keyboard/mouse buttons via Quick Menu → OSC Settings → "Deposit Alternate Buttons"

### Cover Screen Triggers (Foldables)

**Setup**:
1. Unfold device (FLAT mode)
2. Triggers activate automatically (ZL/LB on left, RB/ZR on right)


**Analog Mode** (default):
- Slide outward toward edge to 'depress' trigger (ZR slides right, ZL slides left)
- Pressure increases as you slide further (0-254)
- Reaches 85%: Auto-snaps to max (255) with click + vibration
- Continuous vibration scales with pressure to emulate trigger spring vibration

**Digital Mode**:
- Toggle via Game Menu → "Toggle Cover Triggers Mode"
- Simple tap on/off behavior

**Visual Feedback**:
- Main screen shows 4-color indicator (blue/purple/red/orange)
- Brightness reflects trigger pressure
- Configurable like other OSC elements


## Technical Notes

- **Version**: 0.56-multitouch (Build 82)
- **Min SDK**: Android 5.0+ (Cover features require 7.0+)
- **Installation**: Same as Artemis - download APK and install
- **Compatibility**: All Artemis and Moonlight features preserved

## Credits

- **Moonlight Android**: Cameron Gutman and contributors
- **Artemis**: ClassicOldSong

## License

Inherits license from Moonlight Android.
