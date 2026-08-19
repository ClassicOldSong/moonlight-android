# Artemis Dolby 5.1 output

## User contract

`Settings > Audio Settings > Audio output format` offers:

- `PCM (default)` - the unchanged Artemis renderer and stored channel setting.
- `AC-3 5.1 (automatic, experimental)` - uses AC-3 only when Android reports a compatible HDMI/eARC route.
- `AC-3 5.1 (force, experimental)` - attempts AC-3 when Android route reporting is incomplete.
- `Dolby Digital Plus / E-AC-3 5.1 (automatic, experimental)` - uses E-AC-3 only when Android reports it on the active HDMI/eARC route.
- `Dolby Digital Plus / E-AC-3 5.1 (force, experimental)` - attempts E-AC-3 when Android route reporting is incomplete.

Selecting any encoded mode negotiates 5.1 audio for that stream without modifying the user's stored PCM channel preference. AC-3 and E-AC-3 are limited to six-channel, 48 kHz input. Any initialization, route, encoding, sustained queue, or write failure causes a one-way fallback to PCM for the rest of the stream.

## Streaming path

Moonlight's decoded S16 interleaved audio remains the input boundary. The callback only copies samples into a preallocated accumulator. Complete 1,536-sample-per-channel frames are consumed by an audio-priority worker. AC-3 converts them to S32 planar and encodes with FFmpeg `ac3_fixed` at 448 kbps into 1,792-byte access units for an `ENCODING_AC3` `AudioTrack`. E-AC-3 converts them to float planar and encodes with FFmpeg `eac3` at 640 kbps into 2,560-byte access units for an `ENCODING_E_AC3` `AudioTrack`.

The queue holds at most three complete frames and drops the oldest on overflow. No Sunshine, GameStream protocol, moonlight-common-c, or callback ABI was changed. Channel order is identity-mapped:

`FL, FR, FC, LFE, BL, BR -> FL, FR, FC, LFE, BL, BR`

Android audio effects are not attached to the encoded track. PCM fallback retains the existing Artemis renderer behavior.

## Native encoder provenance

- FFmpeg tag: `n7.1.1`
- Commit: `db69d06eeeab4f46da15030a80d539efb4503ca8`
- Enabled encoders: `ac3_fixed` and `eac3`
- Linked libraries: static `libavcodec` and `libavutil` inside the separate `libartemis-ac3.so`
- License configuration: LGPL 2.1 or later; no GPL or nonfree components
- Packaged ABIs: `armeabi-v7a`, `arm64-v8a`, `x86`, and `x86_64`

The minimal build disables every component by default, then enables only avcodec, avutil, `ac3_fixed`, and `eac3`. It also disables programs, documentation, networking, library autodetection, and shared libraries. The checked-in script builds every Artemis ABI on Linux or WSL with the Android NDK. Runtime CPU detection selects compatible optimized routines where available.

The callback queue remains bounded at three complete frames. Isolated drops expire from overload accounting after ten seconds; twelve dropped frames inside one window are treated as sustained overload and trigger the stream's one-way PCM fallback. Metrics report both average timing and five-second/session encode and write maxima.

The corresponding LGPL text is checked in at `app/src/main/jni/third_party/ffmpeg/COPYING.LGPLv2.1`. Headers and the target static libraries are under the same directory.

## Verification

A standalone development probe passed the AC-3 transport gate on the Sony A9G: all 375 raw AC-3 access units were accepted at 1,792 bytes per write. The probe and its sample payload are intentionally excluded from production sources. The application build additionally verifies native compilation, packaging, setting parsing, frame accumulation, and queue bounds. AC-3 was stable in target testing. E-AC-3 produced 5.1 output on the same setup but showed occasional brief audio dips; no dip-time trace was captured, so its cause remains unverified. Physical speaker mapping, receiver format indication, and long-session performance remain target-device checks; successful Android writes alone do not prove them.

Useful runtime filter:

```powershell
adb logcat -s Artemis:* AudioTrack:* AudioFlinger:*
```

Logs include requested/effective mode, capability evidence, encoder identity, route, queue depth, timing aggregates, dropped-frame counts, and stable fallback reasons. Audio payloads are never logged.

## Locked baseline

- Base: `https://github.com/ClassicOldSong/moonlight-android`, branch `moonlight-noir`
- Artemis commit: `3397ec7750969466ad8983364ee1a33182bbffa1`
- moonlight-common-c: `c999436858471dfefa7617af3b7dc03ec1644ce4`
- NDK: `27.0.12077973`; minimum Android API: 21

The baseline unit suite had five pre-existing failures in layout/theme, startup context, and profile navigation before this feature.
