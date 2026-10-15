#!/usr/bin/env bash
# Static verification of a built APK. Usage: verify-apk.sh <apk>
set -eu

APK="${1:?usage: verify-apk.sh <path-to-apk>}"
AAPT=/opt/android-sdk/build-tools/34.0.0/aapt2
APKSIGNER=/opt/android-sdk/build-tools/34.0.0/apksigner

echo "=== file ==="
ls -la "$APK"
echo
echo "=== badging ==="
"$AAPT" dump badging "$APK" | grep -E "package:|sdkVersion:|targetSdkVersion:|native-code:|application-label:|launchable-activity:|densities:" || true
echo
echo "=== manifest: extractNativeLibs (must be true) ==="
"$AAPT" dump xmltree --file AndroidManifest.xml "$APK" | grep -i "extractNativeLibs" \
  || echo "!! extractNativeLibs NOT FOUND in manifest !!"
echo
echo "=== manifest: permissions + components ==="
"$AAPT" dump xmltree --file AndroidManifest.xml "$APK" | grep -E "uses-permission|E: (activity|service)" || true
echo
echo "=== launcher icon safety ==="
# Any consumer that rasterizes android:icon (installers, launchers, task switchers)
# calls Bitmap.createBitmap(drawable.getIntrinsicWidth(), ...). A <shape> drawable
# has NO intrinsic size (returns -1), which throws
#   IllegalArgumentException: width and height must be > 0
# so the icon must resolve to real raster PNGs (plus an adaptive icon for API 26+).
ICON_BAD=0
# Deliberately NOT using `aapt2 dump badging`'s application-icon-* lines: when an
# anydpi variant exists, anydpi wins the density axis, so every density reports the
# same XML path and a PNG-less icon would slip through. Parse the resource table.
ICON_ID=$("$AAPT" dump xmltree --file AndroidManifest.xml "$APK" \
  | sed -n 's/.*android:icon(0x01010002)=@\(0x[0-9a-fA-F]*\).*/\1/p' | tr 'A-F' 'a-f' | head -n 1)
if [ -z "$ICON_ID" ]; then
  echo "  FAIL could not resolve android:icon to a resource id"
  ICON_BAD=1
else
  echo "  android:icon -> $ICON_ID"
  VARIANTS=$("$AAPT" dump resources "$APK" | awk -v id="$ICON_ID" '
    /^    resource / { cur = $2; next }
    cur == id && /\(file\)/ { print $3, $4 }')
  if [ -z "$VARIANTS" ]; then
    echo "  FAIL $ICON_ID has no file variants in the resource table"
    ICON_BAD=1
  fi
  ICON_PNGS=0
  while read -r path type; do
    [ -n "$path" ] || continue
    case "$type" in
      type=PNG)
        ICON_PNGS=$((ICON_PNGS + 1))
        echo "  OK   $path (raster PNG, intrinsic size)"
        ;;
      type=XML)
        if "$AAPT" dump xmltree --file "$path" "$APK" | grep -q "E: shape"; then
          echo "  FAIL $path is a <shape> drawable: getIntrinsicWidth() == -1, rasterizers crash"
          ICON_BAD=1
        else
          echo "  OK   $path (XML with intrinsic size: vector / adaptive icon)"
        fi
        ;;
      *)
        echo "  ??   $path ($type)"
        ;;
    esac
  done <<< "$VARIANTS"
  if [ "$ICON_PNGS" -eq 0 ]; then
    echo "  FAIL no raster PNG variant: odd installers/launchers cannot rasterize the icon"
    ICON_BAD=1
  fi
fi
echo
echo "=== APK entries ==="
unzip -l "$APK" | grep -E "lib/|classes.*dex|resources.arsc" || true
echo
echo "=== signature ==="
"$APKSIGNER" verify --print-certs "$APK" | grep -E "Signer #1 certificate (DN|SHA-256)" || true
echo "  schemes:"
echo "    (pass --min-sdk-version 23: plain 'apksigner verify' reports"
echo "     v1=false for minSdk>=24 even when the v1 signature is present and valid,"
echo "     because it considers v1 unnecessary there and skips verifying it.)"
"$APKSIGNER" verify --verbose --min-sdk-version 23 "$APK" | grep -E "^Verified using" || true
echo
echo "=== sha256 ==="
sha256sum "$APK"

if [ "$ICON_BAD" -ne 0 ]; then
  echo
  echo "VERIFY FAILED: launcher icon is not rasterizer-safe (see above)"
  exit 1
fi
