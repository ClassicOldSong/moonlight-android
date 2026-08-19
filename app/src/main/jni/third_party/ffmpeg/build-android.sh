#!/usr/bin/env bash
set -euo pipefail

readonly FFMPEG_TAG="n7.1.1"
readonly FFMPEG_COMMIT="db69d06eeeab4f46da15030a80d539efb4503ca8"
readonly ANDROID_API="21"

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
output_root="${OUTPUT_ROOT:-${script_dir}/prebuilt}"
ndk_root="${ANDROID_NDK_HOME:?Set ANDROID_NDK_HOME to Android NDK r27 or newer}"
toolchain="${ndk_root}/toolchains/llvm/prebuilt/linux-x86_64"

if [[ ! -x "${toolchain}/bin/clang" && ! -x "${toolchain}/bin/clang-18" ]]; then
    echo "Linux Android NDK toolchain not found at ${toolchain}" >&2
    exit 1
fi

work_dir="$(mktemp -d)"
trap 'rm -rf "${work_dir}"' EXIT
source_dir="${work_dir}/ffmpeg"
ln -s "${toolchain}/bin/lld" "${work_dir}/ld.lld"

if [[ -n "${FFMPEG_SOURCE_DIR:-}" ]]; then
    mkdir -p "${source_dir}"
    git -C "${FFMPEG_SOURCE_DIR}" archive "${FFMPEG_COMMIT}" | tar -x -C "${source_dir}"
else
    git clone --branch "${FFMPEG_TAG}" --depth 1 https://github.com/FFmpeg/FFmpeg.git "${source_dir}"
fi

actual_commit="$(git -C "${FFMPEG_SOURCE_DIR:-${source_dir}}" rev-parse "${FFMPEG_COMMIT}^{commit}" 2>/dev/null || true)"
if [[ -z "${FFMPEG_SOURCE_DIR:-}" ]]; then
    actual_commit="$(git -C "${source_dir}" rev-parse HEAD)"
fi
if [[ "${actual_commit}" != "${FFMPEG_COMMIT}" ]]; then
    echo "Expected FFmpeg ${FFMPEG_COMMIT}, got ${actual_commit:-unknown}" >&2
    exit 1
fi

build_abi() {
    local abi="$1"
    local arch cpu target cc cxx extra_flags=()
    case "${abi}" in
        armeabi-v7a)
            arch="arm"
            cpu="armv7-a"
            target="armv7a-linux-androideabi"
            ;;
        arm64-v8a)
            arch="aarch64"
            cpu="armv8-a"
            target="aarch64-linux-android"
            ;;
        x86)
            arch="x86"
            cpu="i686"
            target="i686-linux-android"
            extra_flags+=(--disable-x86asm)
            ;;
        x86_64)
            arch="x86_64"
            cpu="x86-64"
            target="x86_64-linux-android"
            extra_flags+=(--disable-x86asm)
            ;;
        *)
            echo "Unsupported ABI: ${abi}" >&2
            exit 1
            ;;
    esac

    cc="${toolchain}/bin/${target}${ANDROID_API}-clang"
    cxx="${toolchain}/bin/${target}${ANDROID_API}-clang++"
    if [[ ! -x "${toolchain}/bin/clang" ]]; then
        # Some archive extractors materialize the NDK's clang-18 binary but not
        # its clang/clang++ symlinks. Keep the build usable in that environment.
        cc="${toolchain}/bin/clang-18 --target=${target}${ANDROID_API}"
        cxx="${toolchain}/bin/clang-18 --target=${target}${ANDROID_API}"
    fi

    local build_dir="${work_dir}/build-${abi}"
    local prefix_dir="${work_dir}/prefix-${abi}"
    mkdir -p "${build_dir}" "${prefix_dir}"

    (
        cd "${build_dir}"
        "${source_dir}/configure" \
            --prefix="${prefix_dir}" \
            --target-os=android \
            --arch="${arch}" \
            --cpu="${cpu}" \
            --enable-cross-compile \
            --cc="${cc}" \
            --cxx="${cxx}" \
            --ar="${toolchain}/bin/llvm-ar" \
            --nm="${toolchain}/bin/llvm-nm" \
            --ranlib="${toolchain}/bin/llvm-ranlib" \
            --strip="${toolchain}/bin/llvm-strip" \
            --sysroot="${toolchain}/sysroot" \
            --disable-everything \
            --enable-avcodec \
            --enable-avutil \
            --enable-encoder=ac3_fixed \
            --enable-encoder=eac3 \
            --disable-avformat \
            --disable-avdevice \
            --disable-avfilter \
            --disable-swresample \
            --disable-swscale \
            --disable-programs \
            --disable-doc \
            --disable-network \
            --disable-autodetect \
            --disable-shared \
            --enable-static \
            --enable-small \
            --enable-pic \
            --disable-debug \
            --disable-symver \
            --enable-runtime-cpudetect \
            --extra-cflags=-Oz \
            --extra-ldflags="-fuse-ld=${work_dir}/ld.lld" \
            "${extra_flags[@]}"

        grep -q '^#define CONFIG_GPL 0$' config.h
        grep -q '^#define CONFIG_NONFREE 0$' config.h
        grep -q '^#define CONFIG_AC3_FIXED_ENCODER 1$' config_components.h
        grep -q '^#define CONFIG_EAC3_ENCODER 1$' config_components.h
        make -j"$(nproc)"
        make install
    )

    local abi_output="${output_root}/${abi}/lib"
    mkdir -p "${abi_output}"
    install -m 0644 "${prefix_dir}/lib/libavcodec.a" "${abi_output}/libavcodec.a"
    install -m 0644 "${prefix_dir}/lib/libavutil.a" "${abi_output}/libavutil.a"

    local symbols_file="${work_dir}/symbols-${abi}.txt"
    "${toolchain}/bin/llvm-nm" "${abi_output}/libavcodec.a" > "${symbols_file}"
    grep -q 'ff_ac3_fixed_encoder' "${symbols_file}"
    grep -q 'ff_eac3_encoder' "${symbols_file}"
    echo "Built ${abi}"
}

for abi in armeabi-v7a arm64-v8a x86 x86_64; do
    build_abi "${abi}"
done

required_headers=(
    libavcodec/avcodec.h
    libavcodec/codec.h
    libavcodec/codec_desc.h
    libavcodec/codec_id.h
    libavcodec/codec_par.h
    libavcodec/defs.h
    libavcodec/packet.h
    libavcodec/version.h
    libavcodec/version_major.h
    libavutil/attributes.h
    libavutil/avconfig.h
    libavutil/avutil.h
    libavutil/buffer.h
    libavutil/channel_layout.h
    libavutil/common.h
    libavutil/dict.h
    libavutil/error.h
    libavutil/frame.h
    libavutil/hwcontext.h
    libavutil/intfloat.h
    libavutil/log.h
    libavutil/macros.h
    libavutil/mathematics.h
    libavutil/mem.h
    libavutil/pixfmt.h
    libavutil/rational.h
    libavutil/samplefmt.h
    libavutil/version.h
)
for header in "${required_headers[@]}"; do
    install -D -m 0644 "${work_dir}/prefix-armeabi-v7a/include/${header}" \
        "${script_dir}/include/${header}"
done
echo "FFmpeg Android prebuilts written to ${output_root}"
