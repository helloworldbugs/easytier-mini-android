#!/usr/bin/env bash
# Copy the swappable binaries from binaries/arm64-v8a/ into the APK's jniLibs
# as lib*.so.
#
# Why the rename: Android only extracts entries named lib*.so into
# nativeLibraryDir -- the one location a modern app may execve() from (execve on
# the app data dir is blocked since Android 10). So the user keeps replacing the
# plain-named files in binaries/arm64-v8a/ and this script does the renaming.
set -eu

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
SRC="$ROOT/binaries/arm64-v8a"
DST="$ROOT/android/app/src/main/jniLibs/arm64-v8a"

mkdir -p "$DST"

rc=0
for f in easytier-core easytier-cli; do
  if [ ! -f "$SRC/$f" ]; then
    echo "MISSING: $SRC/$f" >&2
    rc=1
    continue
  fi
  cp -f "$SRC/$f" "$DST/lib$f.so"
  chmod +x "$DST/lib$f.so"
  printf 'synced %-14s -> lib%s.so  (%s bytes)\n' "$f" "$f" "$(stat -c%s "$SRC/$f")"
done

exit "$rc"
