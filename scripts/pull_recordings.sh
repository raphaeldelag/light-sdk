#!/usr/bin/env bash
# Pull Recorder memos off the connected Light Phone (or emulator) into a folder on this Mac.
# Works on debug builds (run-as needs a debuggable app). Skips files already pulled.
#
# Usage: scripts/pull_recordings.sh [dest-dir] [--delete]
#   dest-dir  default ~/LightPhoneRecordings
#   --delete  remove each file from the phone after a verified pull
set -euo pipefail
PKG="io.github.raphaeldelag.recorder"
DEST="${1:-$HOME/LightPhoneRecordings}"
DELETE=0
for a in "$@"; do [ "$a" = "--delete" ] && DELETE=1; done
[ "$DEST" = "--delete" ] && DEST="$HOME/LightPhoneRecordings"
mkdir -p "$DEST"

if ! adb get-state >/dev/null 2>&1; then echo "no device connected (adb devices)"; exit 1; fi
if ! adb shell run-as "$PKG" true 2>/dev/null; then
  echo "run-as failed: is the debug Recorder installed on this device? (adb shell pm path $PKG)"; exit 1
fi

names=$(adb shell run-as "$PKG" ls files/shared/recordings 2>/dev/null | tr -d '\r' | grep -E '\.m4a$' || true)
if [ -z "$names" ]; then echo "no recordings on device"; exit 0; fi

pulled=0
while IFS= read -r name; do
  [ -z "$name" ] && continue
  if [ -s "$DEST/$name" ]; then echo "skip   $name (already here)"; continue; fi
  size=$(adb shell run-as "$PKG" stat -c %s "files/shared/recordings/$name" | tr -d '\r')
  adb exec-out run-as "$PKG" cat "files/shared/recordings/$name" > "$DEST/$name.part"
  got=$(stat -f %z "$DEST/$name.part")
  if [ "$got" != "$size" ]; then echo "FAILED $name (got $got of $size bytes)"; rm -f "$DEST/$name.part"; continue; fi
  mv "$DEST/$name.part" "$DEST/$name"
  echo "pulled $name ($size bytes)"
  pulled=$((pulled+1))
  if [ $DELETE -eq 1 ]; then adb shell run-as "$PKG" rm "files/shared/recordings/$name" && echo "       removed from device"; fi
done <<< "$names"
echo "$pulled new file(s) in $DEST"
