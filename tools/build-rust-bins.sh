#!/usr/bin/env bash
# Cross-compile the two EasyTier CLI binaries (easytier-core, easytier-cli)
# as standalone aarch64-linux-android EXECUTABLES (not cdylibs).
#
# WHY: easytier-core on Android uses the `mobile` runtime host, which never
# self-creates /dev/net/tun -- so it only works in no_tun mode. no_tun needs
# no TUN fd and no VpnService, which is exactly what the mini APK relies on.
#
# NOTE: the binaries live in the `easytier` crate (NOT the `easytier-core`
# crate, which is now a control-plane library).
set -u

LOG=/root/build-rust-bins.log
exec >"$LOG" 2>&1
echo "==== RUST BINS BUILD START $(date -Is) ===="

export RUSTUP_HOME=/root/.rustup
export CARGO_HOME=/root/.cargo
export ANDROID_HOME=/opt/android-sdk
export ANDROID_SDK_ROOT=/opt/android-sdk
export NDK_TC=/opt/android-sdk/ndk/26.0.10792818/toolchains/llvm/prebuilt/linux-x86_64
export PATH=/root/.cargo/bin:/opt/protoc/bin:$NDK_TC/bin:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin

export LIBCLANG_PATH=$NDK_TC/lib
export KCP_SYS_EXTRA_HEADER_PATH=$NDK_TC/lib/clang/17/include
export ZSTD_SYS_STATIC=1
export BINDGEN_EXTRA_CLANG_ARGS="--sysroot=$NDK_TC/sysroot -I$NDK_TC/lib/clang/17/include"
export PROTOC=/opt/protoc/bin/protoc

CC=$NDK_TC/bin/aarch64-linux-android24-clang
CXX=$NDK_TC/bin/aarch64-linux-android24-clang++
AR=$NDK_TC/bin/llvm-ar

export CARGO_TARGET_AARCH64_LINUX_ANDROID_LINKER="$CC"
export CARGO_TARGET_AARCH64_LINUX_ANDROID_AR="$AR"

cd /root/easytier-build/EasyTier || exit 1

echo "--- workspace profiles ---"
grep -n '^\[profile' Cargo.toml || echo "(none found)"

if grep -q '^\[profile\.mini' Cargo.toml; then
  PROF=mini
else
  PROF=release
fi
echo "--- using profile: $PROF ---"

echo "--- cargo build start $(date -Is) ---"
env \
  "CC_aarch64-linux-android=$CC" \
  "CXX_aarch64-linux-android=$CXX" \
  "AR_aarch64-linux-android=$AR" \
  "CC_aarch64_linux_android=$CC" \
  "CXX_aarch64_linux_android=$CXX" \
  "AR_aarch64_linux_android=$AR" \
  cargo build --profile "$PROF" --target aarch64-linux-android \
    -p easytier --features management,cli \
    --bin easytier-core --bin easytier-cli
RC=$?
echo "--- cargo build exit code: $RC ($(date -Is)) ---"

OUTDIR=/root/easytier-build/EasyTier/target/aarch64-linux-android/$PROF
echo "--- raw outputs ---"
ls -la "$OUTDIR/easytier-core" "$OUTDIR/easytier-cli" 2>&1

if [ "$RC" -eq 0 ]; then
  STRIPDIR=/root/easytier-build/rust-bins-aarch64
  rm -rf "$STRIPDIR"; mkdir -p "$STRIPDIR"
  cp "$OUTDIR/easytier-core" "$STRIPDIR/easytier-core"
  cp "$OUTDIR/easytier-cli"  "$STRIPDIR/easytier-cli"
  chmod +x "$STRIPDIR"/*
  echo "--- sizes BEFORE strip ---"
  ls -la "$STRIPDIR"
  "$NDK_TC/bin/llvm-strip" --strip-all "$STRIPDIR/easytier-core" "$STRIPDIR/easytier-cli"
  echo "--- sizes AFTER strip ---"
  ls -la "$STRIPDIR"
  echo "--- file type ---"
  file "$STRIPDIR/easytier-core" "$STRIPDIR/easytier-cli"
  echo "--- NEEDED libs (should be bionic only: libc.so, libdl.so, libm.so, liblog.so) ---"
  "$NDK_TC/bin/llvm-readelf" -d "$STRIPDIR/easytier-core" | grep NEEDED
  "$NDK_TC/bin/llvm-readelf" -d "$STRIPDIR/easytier-cli"  | grep NEEDED
  echo "--- sha256 ---"
  sha256sum "$STRIPDIR/easytier-core" "$STRIPDIR/easytier-cli"
fi

echo "==== RUST BINS BUILD END $(date -Is) rc=$RC ===="
