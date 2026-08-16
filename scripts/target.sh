#!/usr/bin/env bash
# Point one or more Light tool modules at the LightOS server on the phone or the emulator.
# Usage: scripts/target.sh phone|emu [module-dir ...]   (default module: tool)
#   phone -> serverPackage = "com.lightos"
#   emu   -> serverPackage = "com.thelightphone.sdk.emulator"
set -euo pipefail
cd "$(dirname "$0")/.."
target="${1:-}"; shift || true
case "$target" in
  phone) pkg="com.lightos" ;;
  emu)   pkg="com.thelightphone.sdk.emulator" ;;
  *) echo "usage: $0 phone|emu [module-dir ...]" >&2; exit 2 ;;
esac
mods=("$@"); [ ${#mods[@]} -eq 0 ] && mods=(tool)
for m in "${mods[@]}"; do
  f="$m/lighttool.toml"
  [ -f "$f" ] || { echo "no $f" >&2; exit 1; }
  # rewrite the single active (uncommented) serverPackage line
  tmp="$(mktemp)"
  awk -v pkg="$pkg" '
    /^[[:space:]]*serverPackage[[:space:]]*=/ { print "serverPackage = \"" pkg "\""; done=1; next }
    { print }
    END { if (!done) exit 3 }
  ' "$f" > "$tmp" || { echo "no active serverPackage line in $f" >&2; rm -f "$tmp"; exit 1; }
  mv "$tmp" "$f"
  echo "$f -> $pkg"
done
