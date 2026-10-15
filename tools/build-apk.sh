#!/usr/bin/env bash
# Full build: sync the swappable binaries into jniLibs, build the signed release
# APK offline, then statically verify it.
#
# Self-contained on purpose (no helper-script calls) so it survives being copied
# around:  tr -d '\r' < tools/build-apk.sh > /root/build-apk-mini.sh && bash /root/build-apk-mini.sh
set -eu

ROOT=/mnt/d/project/easytier-mini
ANDROID_HOME=/opt/android-sdk
export ANDROID_HOME
export ANDROID_SDK_ROOT="$ANDROID_HOME"

AAPT="$ANDROID_HOME/build-tools/34.0.0/aapt2"
APKSIGNER="$ANDROID_HOME/build-tools/34.0.0/apksigner"

SRC="$ROOT/binaries/arm64-v8a"
DST="$ROOT/android/app/src/main/jniLibs/arm64-v8a"

echo "== 1/5 sync binaries (binaries/arm64-v8a -> jniLibs as lib*.so) =="
mkdir -p "$DST"
for f in easytier-core easytier-cli; do
  if [ ! -f "$SRC/$f" ]; then
    echo "MISSING $SRC/$f" >&2
    exit 1
  fi
  cp -f "$SRC/$f" "$DST/lib$f.so"
  chmod +x "$DST/lib$f.so"
  printf '  %-14s -> lib%s.so  (%s bytes)\n' "$f" "$f" "$(stat -c%s "$SRC/$f")"
done

echo "== 2/5 sanity check the binaries =="
for f in "$DST"/*.so; do
  printf '  %s: ' "$(basename "$f")"
  file -b "$f"
done

echo "== 3/5 gradle assembleRelease (offline) =="
cd "$ROOT/android"
./gradlew --offline --no-daemon --console=plain assembleRelease

APK="$ROOT/android/app/build/outputs/apk/release/app-release.apk"
if [ ! -f "$APK" ]; then
  echo "APK NOT FOUND: $APK" >&2
  exit 1
fi

echo "== 4/5 publish to dist/ =="
mkdir -p "$ROOT/dist"
OUT="$ROOT/dist/easytier-mini-arm64-v8a.apk"
cp -f "$APK" "$OUT"

echo "== 5/5 verify APK =="
ls -la "$OUT"
"$AAPT" dump badging "$OUT" | grep -E "package:|sdkVersion:|targetSdkVersion:|native-code:|application-label:" || true

echo "-- extractNativeLibs (must be true) --"
"$AAPT" dump xmltree --file AndroidManifest.xml "$OUT" | grep -i extractNativeLibs \
  || echo "!! extractNativeLibs MISSING !!"

echo "-- entries --"
unzip -l "$OUT" | grep -E "lib/|classes.*dex" || true

echo "-- embedded binaries identical to binaries/ source? --"
TMP="$(mktemp -d)"
unzip -o -q "$OUT" -d "$TMP"
for f in easytier-core easytier-cli; do
  if cmp -s "$TMP/lib/arm64-v8a/lib$f.so" "$SRC/$f"; then
    echo "  OK   lib$f.so == binaries/arm64-v8a/$f"
  else
    echo "  FAIL lib$f.so differs from binaries/arm64-v8a/$f"
  fi
done
rm -rf "$TMP"

echo "-- signature --"
"$APKSIGNER" verify --print-certs "$OUT" | grep -E "Signer #1 certificate (DN|SHA-256)" || true

echo "-- sha256 --"
sha256sum "$OUT"
echo "APK READY: $OUT"
