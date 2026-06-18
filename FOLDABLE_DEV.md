# Artemis Foldable Fork — Dev Handoff

## Project
Fork of [ClassicOldSong/moonlight-android](https://github.com/ClassicOldSong/moonlight-android) (Artemis / Moonlight Noir) adding Z Fold foldable display support.

- **Branch:** `artemis-foldable` (tracking `origin/moonlight-noir`)
- **Package:** `com.limelight`
- **Build IDs:** debug = `com.limelight.noirdebug`, release = `com.limelight.noir`

---

## What's been implemented

### New files
| File | Purpose |
|------|---------|
| `app/src/main/java/com/limelight/ui/FoldableTriggerView.java` | Custom View — 2×2 grid of L1/L2/R1/R2 buttons, multi-touch, haptic, fires InputListener callback |
| `app/src/main/java/com/limelight/ui/FoldStateManager.java` | Wraps Jetpack `WindowInfoTracker`, exposes `FoldState.isBookMode()` / `isTableTopMode()` |
| `app/src/main/res/layout/foldable_trigger_panel.xml` | Layout stub (view is created programmatically) |

### Modified files
| File | Change |
|------|--------|
| `app/build.gradle` | Added `androidx.window:window:1.3.0` + `lifecycle-runtime-ktx:2.8.7` |
| `app/src/main/java/com/limelight/Game.java` | Integrated fold detection, trigger panel injection, input merging |

### How it works
1. On `Game.onCreate`, `FoldableTriggerView` is added to `rootView` (hidden).
2. `FoldStateManager` observes `WindowInfoTracker` — lifecycle-aware, stops on activity stop.
3. When Z Fold enters **book mode** (hinge vertical): trigger panel slides into right 50% of screen.
4. L1/R1 send `ControllerPacket.LB_FLAG` / `RB_FLAG`. L2/R2 send analog `0xFF` trigger values.
5. `sendFoldableTriggerState()` merges foldable flags with existing OSC `ControllerInputContext` and calls `controllerHandler.reportOscState(...)`.
6. On unfold (flat mode): `releaseAll()` clears any held buttons, panel hides.

---

## Key source locations

```
app/src/main/java/com/limelight/
├── Game.java                                      # Main stream activity — foldable wired here
├── ui/
│   ├── FoldableTriggerView.java                   # NEW — L1/L2/R1/R2 touch panel
│   ├── FoldStateManager.java                      # NEW — fold posture observer
│   ├── StreamContainer.java                       # Stream surface host
│   └── ExternalControllerView.java                # Key/IME forwarding view
└── binding/input/
    ├── ControllerHandler.java                     # reportOscState() — input sink
    └── virtual_controller/
        ├── VirtualController.java                 # OSC controller (getControllerInputContext)
        ├── VirtualControllerElement.java          # EID_LB=4, EID_RB=5, EID_LT=2, EID_RT=3
        └── VirtualControllerConfigurationLoader.java  # Button layout/positioning
```

---

## ControllerPacket flags (relevant)
```java
ControllerPacket.LB_FLAG   // L1 bumper
ControllerPacket.RB_FLAG   // R1 bumper
// L2 / R2 are analog — pass byte value (0x00–0xFF) via leftTrigger / rightTrigger
```

---

## What's next / TODO

- [ ] **Table-top mode**: when hinge is horizontal (device flat on table, bent), show trigger strip along the bottom half — L1/L2 on left, R1/R2 on right, mirroring a physical controller grip
- [ ] **Button layout customisation**: allow user to reposition L1/L2/R1/R2 within the panel (same drag-to-move system as existing OSC uses `ControllerMode.MoveButtons`)
- [ ] **Add more buttons to foldable panel**: D-pad, face buttons (A/B/X/Y) — `FoldableTriggerView` is easy to extend, just add more `RectF` slots and labels
- [ ] **Hinge angle awareness**: `FoldingFeature.getBounds()` gives the hinge rect — use it to avoid rendering buttons under the physical hinge
- [ ] **Stream viewport adjustment**: when in book mode, constrain the stream `SurfaceView` to the left half only (currently the stream still renders full-width behind the panel)
- [ ] **Persist panel preference**: remember if user dismissed panel via a preference key in `PreferenceConfiguration`
- [ ] **Test on emulator**: Android Studio has a foldable emulator target — use it to test without physical hardware

---

## Build & run

```bash
# From project root
./gradlew assembleNonRoot_gameDebug        # fastest debug build
./gradlew installNonRoot_gameDebug         # build + install to connected device
```

Flavors: `root` (rooted, API ≤25) and `nonRoot_game` (standard). Use `nonRoot_game` for development.

---

## Reference
- [Android foldable display modes guide](https://developer.android.com/develop/adaptive-apps/guides/foldables/support-foldable-display-modes)
- [Jetpack WindowManager `WindowInfoTracker`](https://developer.android.com/reference/androidx/window/layout/WindowInfoTracker)
- [FoldingFeature API](https://developer.android.com/reference/androidx/window/layout/FoldingFeature)
