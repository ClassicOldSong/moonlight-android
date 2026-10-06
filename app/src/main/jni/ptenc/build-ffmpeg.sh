#!/usr/bin/env bash
#
# Builds a minimal static libavcodec + libavutil containing only FFmpeg's
# AC-3, DTS and TrueHD encoders, for every ABI the APK ships. Output goes to
#   app/src/main/jni/ptenc/ffmpeg/<abi>/{lib,include}
# which Android.mk in this directory picks up automatically.
#
# Requirements: bash, make, git, and the Android NDK (the version pinned in
# app/build.gradle). Point at the NDK with ANDROID_NDK_HOME / ANDROID_NDK_ROOT,
# or have it installed under $ANDROID_HOME/ndk/<version>.
# On Windows, run it from WSL with the Linux NDK.
#
# Optional environment:
#   FFMPEG_SRC   existing FFmpeg checkout to use instead of cloning
#   FFMPEG_TAG   tag to clone (default n7.1)
#   ABIS         space separated subset of ABIs to build
#   JOBS         parallel make jobs
#
# FFmpeg is built under the LGPL (no --enable-gpl / --enable-nonfree needed).

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
OUT_ROOT="$SCRIPT_DIR/ffmpeg"
API=21
FFMPEG_TAG="${FFMPEG_TAG:-n7.1}"
ABIS="${ABIS:-armeabi-v7a arm64-v8a x86 x86_64}"
JOBS="${JOBS:-$( (nproc || sysctl -n hw.ncpu) 2>/dev/null || echo 4)}"

# Locate the NDK
NDK_VERSION="$(sed -n 's/.*ndkVersion *"\([^"]*\)".*/\1/p' "$SCRIPT_DIR/../../../../build.gradle" | head -n1)"
NDK="${ANDROID_NDK_HOME:-${ANDROID_NDK_ROOT:-}}"
if [ -z "$NDK" ] && [ -n "${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}" ]; then
    SDK="${ANDROID_HOME:-$ANDROID_SDK_ROOT}"
    if [ -n "$NDK_VERSION" ] && [ -d "$SDK/ndk/$NDK_VERSION" ]; then
        NDK="$SDK/ndk/$NDK_VERSION"
    fi
fi
if [ -z "$NDK" ] || [ ! -d "$NDK" ]; then
    echo "Android NDK not found. Set ANDROID_NDK_HOME (expected version: ${NDK_VERSION:-see app/build.gradle})." >&2
    exit 1
fi

case "$(uname -s)" in
    Linux*)  HOST_TAG=linux-x86_64 ;;
    Darwin*) HOST_TAG=darwin-x86_64 ;;
    *) echo "Unsupported host OS: $(uname -s)" >&2; exit 1 ;;
esac
TOOLCHAIN="$NDK/toolchains/llvm/prebuilt/$HOST_TAG"
if [ ! -d "$TOOLCHAIN" ]; then
    echo "NDK toolchain not found at $TOOLCHAIN" >&2
    exit 1
fi
echo "Using NDK: $NDK"

# Get FFmpeg sources
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT
if [ -z "${FFMPEG_SRC:-}" ]; then
    FFMPEG_SRC="$WORK/ffmpeg"
    echo "Cloning FFmpeg $FFMPEG_TAG..."
    git clone --quiet --depth 1 --branch "$FFMPEG_TAG" https://github.com/FFmpeg/FFmpeg.git "$FFMPEG_SRC"
fi

build_abi() {
    local abi="$1" arch cpu triple extra=()
    case "$abi" in
        armeabi-v7a) arch=arm;    cpu=armv7-a; triple=armv7a-linux-androideabi; extra=(--enable-neon) ;;
        arm64-v8a)   arch=aarch64; cpu=armv8-a; triple=aarch64-linux-android;   extra=(--enable-neon) ;;
        # No hand-written asm on x86: avoids needing nasm and text relocations on 32-bit x86
        x86)         arch=x86;    cpu=i686;    triple=i686-linux-android;      extra=(--disable-asm) ;;
        x86_64)      arch=x86_64; cpu=x86-64;  triple=x86_64-linux-android;    extra=(--disable-asm) ;;
        *) echo "Unknown ABI $abi" >&2; return 1 ;;
    esac

    local build="$WORK/build-$abi" prefix="$OUT_ROOT/$abi"
    rm -rf "$build" "$prefix"
    mkdir -p "$build"
    echo "=== Building FFmpeg passthrough encoders for $abi ==="

    (
        cd "$build"
        "$FFMPEG_SRC/configure" \
            --prefix="$prefix" \
            --target-os=android \
            --arch="$arch" \
            --cpu="$cpu" \
            --enable-cross-compile \
            --sysroot="$TOOLCHAIN/sysroot" \
            --cc="$TOOLCHAIN/bin/${triple}${API}-clang" \
            --cxx="$TOOLCHAIN/bin/${triple}${API}-clang++" \
            --ar="$TOOLCHAIN/bin/llvm-ar" \
            --ranlib="$TOOLCHAIN/bin/llvm-ranlib" \
            --nm="$TOOLCHAIN/bin/llvm-nm" \
            --strip="$TOOLCHAIN/bin/llvm-strip" \
            --pkg-config=false \
            --enable-static --disable-shared --enable-pic \
            --disable-debug --disable-doc --disable-programs \
            --disable-everything --disable-autodetect --disable-network \
            --disable-avdevice --disable-avformat --disable-avfilter \
            --disable-swscale --disable-swresample --disable-postproc \
            --enable-avcodec --enable-avutil --enable-encoder=ac3,dca,truehd \
            --extra-cflags="-O2 -fPIC -ffunction-sections -fdata-sections" \
            "${extra[@]}" > configure.log 2>&1 || { tail -n 40 configure.log ffbuild/config.log 2>/dev/null; exit 1; }
        make -j"$JOBS" > make.log 2>&1 || { tail -n 40 make.log; exit 1; }
        make install > /dev/null
    )

    # Only the static libs and headers are needed
    rm -rf "$prefix/share" "$prefix/lib/pkgconfig"
    echo "    -> $prefix"
}

for abi in $ABIS; do
    build_abi "$abi"
done

echo "Done. Rebuild the app to include libmoonlight-ptenc.so."
