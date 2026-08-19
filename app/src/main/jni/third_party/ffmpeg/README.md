# Minimal FFmpeg dependency

This directory contains the pinned FFmpeg `n7.1.1` / `db69d06eeeab4f46da15030a80d539efb4503ca8` headers and static libraries used only by Artemis AC-3 and E-AC-3 output. Prebuilts are provided for every ABI shipped by Artemis: `armeabi-v7a`, `arm64-v8a`, `x86`, and `x86_64`.

Run the reproducible build from Linux or WSL with Android NDK r27 or newer:

```bash
ANDROID_NDK_HOME=/path/to/android-ndk-r27 \
  ./app/src/main/jni/third_party/ffmpeg/build-android.sh
```

Set `FFMPEG_SOURCE_DIR` to an existing FFmpeg checkout to build without cloning. The script verifies the immutable source commit, LGPL-only configuration, both encoder symbols, and each output library.

Configure flags:

```text
--target-os=android --arch=arm --cpu=armv7-a --enable-cross-compile
--disable-everything --enable-avcodec --enable-avutil
--enable-encoder=ac3_fixed --enable-encoder=eac3
--disable-avformat --disable-avdevice --disable-avfilter --disable-swresample
--disable-swscale --disable-programs --disable-doc --disable-network
--disable-autodetect --disable-shared --enable-static --enable-small --enable-pic
--disable-debug --disable-symver --enable-runtime-cpudetect
```

The minimum Android API is 21. The 32-bit ARM build enables runtime CPU detection so FFmpeg selects compatible ARMv6/NEON routines on the device. Every `libavcodec.a` must expose `ff_ac3_fixed_encoder` and `ff_eac3_encoder`; the build must report `License: LGPL version 2.1 or later`. Do not replace these libraries without recording the immutable source commit, full configure report, enabled components, and license result.
