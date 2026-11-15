#!/usr/bin/env bash
# Reproducible build of the Shadowsocks native engine for Android.
#
#   native/build-native.sh <output-jniLibs-dir> [abi ...]
#
# Produces <out>/<abi>/libzuzuss.so (shadowsocks-rust client, JNI) and
# <out>/<abi>/libhev-socks5-tunnel.so (TUN -> SOCKS5, JNI) for each ABI.
#
# Inputs are pinned in native/versions.properties. Requires:
#   * ANDROID_NDK_HOME (or ANDROID_HOME/ndk/<NDK_VERSION>) with a linux-x86_64 host toolchain
#   * rustup with the toolchain and Android targets from native/zuzuss/rust-toolchain.toml,
#     installed beforehand (this script never downloads toolchains; see the error message)
# Downloads are cached in ${ZUZU_NATIVE_CACHE:-$HOME/.cache/zuzu-native} and verified by SHA-256.
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
OUT="${1:?usage: build-native.sh <output-dir> [abi ...]}"
shift
mkdir -p "$OUT"
OUT="$(cd "$OUT" && pwd)"

prop() { sed -n "s/^$1=//p" "$HERE/versions.properties" | tr -d '\r'; }

NDK_VERSION="$(prop NDK_VERSION)"
API="$(prop ANDROID_API)"
HEV_VERSION="$(prop HEV_VERSION)"
HEV_URL="$(prop HEV_URL)"
HEV_SHA256="$(prop HEV_SHA256)"
if [[ $# -gt 0 ]]; then ABIS=("$@"); else read -r -a ABIS <<<"$(prop ABIS)"; fi

# --- Android NDK -------------------------------------------------------------------------------
NDK="${ANDROID_NDK_HOME:-}"
if [[ -z "$NDK" ]]; then
    SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/Android/Sdk}}"
    NDK="$SDK/ndk/$NDK_VERSION"
fi
[[ -x "$NDK/ndk-build" ]] || { echo "error: Android NDK $NDK_VERSION not found at $NDK" >&2; exit 1; }
grep -q "Pkg.Revision = $NDK_VERSION" "$NDK/source.properties" ||
    { echo "error: $NDK is not NDK $NDK_VERSION" >&2; exit 1; }
LLVM="$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin"
[[ -d "$LLVM" ]] || { echo "error: NDK has no linux-x86_64 toolchain (use the WSL/Linux NDK, not the Windows one)" >&2; exit 1; }

# --- Rust toolchain ----------------------------------------------------------------------------
export PATH="$HOME/.cargo/bin:$PATH"
RUST_TOOLCHAIN="$(prop RUST_TOOLCHAIN)"
# Never download toolchains implicitly from a Gradle build (rust-toolchain.toml would otherwise
# make rustup auto-install). Install explicitly with:
#   rustup toolchain install <RUST_TOOLCHAIN> --profile minimal \
#     --target aarch64-linux-android,armv7-linux-androideabi,x86_64-linux-android,i686-linux-android
export RUSTUP_AUTO_INSTALL=0
missing_rust() {
    echo "error: Rust $RUST_TOOLCHAIN with Android targets is not fully installed: $1" >&2
    echo "       rustup toolchain install $RUST_TOOLCHAIN --profile minimal --target aarch64-linux-android,armv7-linux-androideabi,x86_64-linux-android,i686-linux-android" >&2
    exit 1
}
command -v rustup >/dev/null || missing_rust "rustup not found"
rustup run "$RUST_TOOLCHAIN" rustc --version >/dev/null 2>&1 || missing_rust "toolchain unusable"
rustup run "$RUST_TOOLCHAIN" cargo --version >/dev/null 2>&1 || missing_rust "cargo missing"
INSTALLED_TARGETS="$(rustup target list --installed --toolchain "$RUST_TOOLCHAIN" 2>/dev/null)"
[[ -f "$HERE/zuzuss/Cargo.lock" ]] || { echo "error: native/zuzuss/Cargo.lock is missing (run: cargo generate-lockfile in native/zuzuss)" >&2; exit 1; }

# --- hev-socks5-tunnel source (verified tarball) -----------------------------------------------
CACHE="${ZUZU_NATIVE_CACHE:-$HOME/.cache/zuzu-native}"
mkdir -p "$CACHE"
HEV_TARBALL="$CACHE/hev-socks5-tunnel-$HEV_VERSION.tar.xz"
if [[ ! -f "$HEV_TARBALL" ]] || ! echo "$HEV_SHA256  $HEV_TARBALL" | sha256sum -c --status; then
    curl -fL --retry 3 -o "$HEV_TARBALL.tmp" "$HEV_URL"
    mv "$HEV_TARBALL.tmp" "$HEV_TARBALL"
fi
echo "$HEV_SHA256  $HEV_TARBALL" | sha256sum -c --quiet ||
    { echo "error: hev-socks5-tunnel checksum mismatch" >&2; rm -f "$HEV_TARBALL"; exit 1; }
HEV_SRC="$CACHE/hev-socks5-tunnel-$HEV_VERSION"
if [[ ! -f "$HEV_SRC/.extracted" ]]; then
    rm -rf "$HEV_SRC"
    tar -xJf "$HEV_TARBALL" -C "$CACHE"
    touch "$HEV_SRC/.extracted"
fi

# --- Build -------------------------------------------------------------------------------------
rust_target() {
    case "$1" in
        arm64-v8a) echo aarch64-linux-android ;;
        armeabi-v7a) echo armv7-linux-androideabi ;;
        x86_64) echo x86_64-linux-android ;;
        x86) echo i686-linux-android ;;
        *) echo "error: unsupported ABI $1" >&2; return 1 ;;
    esac
}
clang_prefix() {
    case "$1" in
        armv7-linux-androideabi) echo armv7a-linux-androideabi ;;
        *) echo "$1" ;;
    esac
}

WORK="${ZUZU_NATIVE_WORK:-$OUT/../native-work}"
mkdir -p "$WORK"

for ABI in "${ABIS[@]}"; do
    echo "==> $ABI"
    mkdir -p "$OUT/$ABI"

    # hev-socks5-tunnel via ndk-build
    "$NDK/ndk-build" -s -j"$(nproc)" \
        NDK_PROJECT_PATH=null \
        APP_BUILD_SCRIPT="$HEV_SRC/Android.mk" \
        NDK_APPLICATION_MK="$HERE/hev/Application.mk" \
        APP_ABI="$ABI" \
        APP_PLATFORM="android-$API" \
        REV_ID="$HEV_VERSION" \
        NDK_OUT="$WORK/hev-obj" \
        NDK_LIBS_OUT="$WORK/hev-libs" \
        hev-socks5-tunnel
    # Unstripped; AGP strips debug symbols when packaging (and keeps them for crash symbolication).
    cp "$WORK/hev-obj/local/$ABI/libhev-socks5-tunnel.so" "$OUT/$ABI/"

    # shadowsocks-rust client via cargo
    TARGET="$(rust_target "$ABI")"
    grep -qx "$TARGET" <<<"$INSTALLED_TARGETS" || missing_rust "target $TARGET not installed"
    CLANG="$LLVM/$(clang_prefix "$TARGET")$API-clang"
    TARGET_ENV="$(echo "$TARGET" | tr 'a-z-' 'A-Z_')"
    TARGET_CC_ENV="$(echo "$TARGET" | tr '-' '_')"
    (
        cd "$HERE/zuzuss"
        export "CARGO_TARGET_${TARGET_ENV}_LINKER=$CLANG"
        export "CC_${TARGET_CC_ENV}=$CLANG"
        export "AR_${TARGET_CC_ENV}=$LLVM/llvm-ar"
        # 16 KB page alignment (required for Google Play on 64-bit devices); stable build paths.
        export "CARGO_TARGET_${TARGET_ENV}_RUSTFLAGS=-C link-arg=-Wl,-z,max-page-size=16384 -C link-arg=-Wl,-soname,libzuzuss.so --remap-path-prefix=$HOME=~"
        cargo build --locked --release --target "$TARGET" --target-dir "$HERE/zuzuss/target"
    )
    cp "$HERE/zuzuss/target/$TARGET/release/libzuzuss.so" "$OUT/$ABI/"
done

echo "Native libraries written to $OUT"
