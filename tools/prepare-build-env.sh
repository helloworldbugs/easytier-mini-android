#!/usr/bin/env bash
# One-time build environment preparation for the mini APK project.
# Reuses the Gradle wrapper + cached Gradle distribution from the EasyTier fork build.
set -eu

ROOT=/mnt/d/project/easytier-mini
TAURI=/root/easytier-build/EasyTier/easytier-gui/src-tauri/gen/android

# Remove the stray /android tree created by an earlier shell-quoting accident.
rm -rf /android

mkdir -p "$ROOT/android/keystore" "$ROOT/android/gradle/wrapper"

KS="$ROOT/android/keystore/mini-release.jks"
PROPS="$ROOT/android/keystore.properties"
ALIAS=easytiermini

# The keystore password must NEVER appear in this script: it belongs only in
# keystore.properties, which is git-ignored. Pass your own via MINI_KS_PASS, or a
# random one is generated on first run and written to that file.
if [ -f "$KS" ] && [ -f "$PROPS" ]; then
  KS_PASS=$(sed -n 's/^storePassword=//p' "$PROPS")
  echo "KEYSTORE_EXISTS $KS"
else
  if [ -n "${MINI_KS_PASS:-}" ]; then
    KS_PASS="$MINI_KS_PASS"
  elif command -v openssl >/dev/null 2>&1; then
    KS_PASS=$(openssl rand -hex 16)
  else
    KS_PASS=$(head -c 18 /dev/urandom | base64 | tr -dc 'A-Za-z0-9')
  fi
  keytool -genkeypair \
    -keystore "$KS" -alias "$ALIAS" \
    -keyalg RSA -keysize 2048 -validity 10000 \
    -storepass "$KS_PASS" -keypass "$KS_PASS" \
    -dname "CN=EasyTier Mini,OU=Fork,O=kkrainbow,L=Beijing,ST=Beijing,C=CN"
  printf 'storeFile=keystore/mini-release.jks\nstorePassword=%s\nkeyAlias=%s\nkeyPassword=%s\n' \
    "$KS_PASS" "$ALIAS" "$KS_PASS" > "$PROPS"
  chmod 600 "$PROPS"
  echo "KEYSTORE_CREATED $KS"
  echo "WROTE $PROPS (git-ignored)"
fi
keytool -list -keystore "$KS" -storepass "$KS_PASS" | grep -E "easytiermini|SHA256" || true

tr -d '\r' < "$TAURI/gradlew" > "$ROOT/android/gradlew"
chmod +x "$ROOT/android/gradlew"
cp -f "$TAURI/gradle/wrapper/gradle-wrapper.jar" "$ROOT/android/gradle/wrapper/gradle-wrapper.jar"

echo "--- wrapper ---"
ls -la "$ROOT/android/gradlew" "$ROOT/android/gradle/wrapper/"
echo "DONE"
