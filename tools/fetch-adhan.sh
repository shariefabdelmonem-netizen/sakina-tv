#!/bin/bash
# Downloads the adhan you chose (a YouTube link, best from the muezzin's own channel) into the Kahf app,
# trimmed and at a gentle loudness. Without it the app still shows the prayer banner, just silently.
# Usage: tools/fetch-adhan.sh <youtube-url>
set -e
[ -n "$1" ] || { echo "usage: $0 <youtube-url>"; exit 1; }
cd "$(dirname "$0")/.."
TMP=$(mktemp -d)
yt-dlp -q -x --audio-format mp3 -o "$TMP/a.%(ext)s" "$1"
mkdir -p sakina-app/app/src/kahf/res/raw
ffmpeg -v error -y -i "$TMP/a.mp3" -af "silenceremove=start_periods=1:start_threshold=-45dB,loudnorm=I=-18:TP=-2" \
  -ar 44100 -ac 1 -b:a 96k sakina-app/app/src/kahf/res/raw/adhan.mp3
rm -rf "$TMP"
echo "adhan: $(ffprobe -v error -show_entries format=duration -of csv=p=0 sakina-app/app/src/kahf/res/raw/adhan.mp3 | cut -d. -f1) s"
