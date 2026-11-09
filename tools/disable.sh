#!/bin/bash
# Disables (never uninstalls) the given packages, logs each one for restore.sh, then checks the TV still answers.
# Usage: tools/disable.sh <tv-ip> <backup-folder> <package>...
set -e
IP="$1"; OUT="$2"; shift 2; D="$IP:5555"
for p in "$@"; do
  if adb -s "$D" shell pm disable-user --user 0 "$p" | grep -q "disabled-user"; then
    echo "$p" >> "$OUT/disabled-by-us.txt"; echo "✅ $p"
  else
    echo "⚠️ $p (not changed)"
  fi
done
# Asking adb, not ping: ping's flags differ between Mac and Windows, and adb answering is what matters here.
if [ "$(adb -s "$D" shell echo ok 2>/dev/null | tr -d '\r')" = ok ]; then echo "TV is answering"
else echo "⛔ TV stopped answering - run $OUT/restore.sh"; fi
