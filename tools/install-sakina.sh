#!/bin/bash
# Builds the six shortcut apps (Kahf, Baqarah, morning and evening adhkar, ruqyah, Quran radio) and installs them on the TV.
# The Kahf app also carries the reminders, the adhan and the phone message page, so it gets the overlay
# and usage-access permissions and is started once.
# Usage: tools/install-sakina.sh <tv-ip>
set -e
D="$1:5555"; [ -n "$1" ] || { echo "usage: $0 <tv-ip>"; exit 1; }
cd "$(dirname "$0")/../sakina-app"
./gradlew -q assembleRelease
for f in kahf baqara sabah masa ruqya radio; do
  adb -s "$D" install -r "app/build/outputs/apk/$f/release/app-$f-release.apk" >/dev/null && echo "✅ $f"
done
adb -s "$D" shell appops set app.sakinatv.kahf SYSTEM_ALERT_WINDOW allow
adb -s "$D" shell appops set app.sakinatv.kahf GET_USAGE_STATS allow
sleep 8 # Android restarts the app right after an update, so start it once that has settled
adb -s "$D" shell am broadcast -n app.sakinatv.kahf/app.sakinatv.Reminder >/dev/null
echo "Phone page: http://$1:8080"
