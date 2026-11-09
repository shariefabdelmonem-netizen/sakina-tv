#!/bin/bash
# Screenshot of the TV into a PNG file. Mac or Git Bash on Windows.
# Usage: shot.sh <tv-ip> <out.png>
# Not `adb exec-out screencap -p > file`: some TVs (Xiaomi/MediaTek) print driver lines such as
# "Init wrapper sys mutex successful" on stdout first, and the PNG comes out broken.
D="$1:5555"; OUT="$2"
# Git Bash would turn /sdcard/... into C:/Program Files/Git/sdcard/... on the way to adb.exe; stop that,
# and give adb a Windows path for the file on this side.
export MSYS_NO_PATHCONV=1
command -v cygpath >/dev/null && OUT=$(cygpath -m "$2")
adb -s "$D" shell screencap -p /sdcard/sakina-shot.png >/dev/null 2>&1
adb -s "$D" pull /sdcard/sakina-shot.png "$OUT" >/dev/null 2>&1
adb -s "$D" shell rm -f /sdcard/sakina-shot.png >/dev/null 2>&1
[ -s "$2" ] && echo "$2" || { echo "screenshot failed"; exit 1; }
