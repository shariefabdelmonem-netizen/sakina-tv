#!/bin/bash
# Finds Android TVs on the home Wi-Fi, from a Mac or from Git Bash on Windows.
# Prints one line per TV: "<ip> adb=open|closed <name>". adb=closed means the TV is there but its debugging
# switch is still off.
# A TV with debugging off answers nothing on 5555, but every Android/Google TV runs the remote-control service
# on 6466 (and announces it over mDNS as _androidtvremote2), so we can still find it and tell the person.
T=$(mktemp -d)
WIN=""; case "$(uname -s)" in MINGW*|MSYS*|CYGWIN*) WIN=1 ;; esac

# Is a TCP port open? nc on a Mac; Git Bash has no nc, but it has bash's /dev/tcp and timeout.
port() {
  if [ -n "$WIN" ]; then timeout 1 bash -c "exec 3<>/dev/tcp/$1/$2" 2>/dev/null
  else nc -z -G 1 "$1" "$2" 2>/dev/null; fi
}

# 1. mDNS names (Mac only: dns-sd). On Windows the TV is still found by step 2, just without its name.
if command -v dns-sd >/dev/null; then
  dns-sd -B _androidtvremote2._tcp local. > "$T/b" 2>&1 & p=$!; sleep 5; { kill $p; wait $p; } 2>/dev/null
  grep ' Add ' "$T/b" | sed -E 's/.*_androidtvremote2\._tcp\. +//' | sort -u | while read -r name; do
    dns-sd -L "$name" _androidtvremote2._tcp local. > "$T/l" 2>&1 & p=$!; sleep 3; { kill $p; wait $p; } 2>/dev/null
    host=$(grep -oE 'reached at [^ :]+' "$T/l" | head -1 | awk '{print $3}')
    [ -n "$host" ] || continue
    dns-sd -G v4 "$host" > "$T/g" 2>&1 & p=$!; sleep 3; { kill $p; wait $p; } 2>/dev/null
    ip=$(grep -oE '([0-9]+\.){3}[0-9]+' "$T/g" | grep -v '^0\.' | head -1)
    [ -n "$ip" ] && echo "$ip|$name" >> "$T/mdns"
  done
fi

# 2. Our address: the adapter that has the internet route (not a VPN or a virtual-machine adapter).
if [ -n "$WIN" ]; then
  me=$(powershell.exe -NoProfile -Command "(Get-NetIPConfiguration | Where-Object { \$_.IPv4DefaultGateway -and \$_.NetAdapter.Status -eq 'Up' } | Select-Object -First 1).IPv4Address.IPAddress" 2>/dev/null | tr -d '\r ')
else
  me=$(ipconfig getifaddr en0 2>/dev/null || ipconfig getifaddr en1 2>/dev/null)
fi
# Ping sweep of our own /24 only (a VPN can fill the ARP table with hundreds of other addresses), then every
# live neighbour is checked for adb (5555) and the TV remote service (6466).
if [ -n "$me" ]; then
  base=${me%.*}
  for i in $(seq 1 254); do
    if [ -n "$WIN" ]; then ping -n 1 -w 700 "$base.$i" >/dev/null 2>&1 &
    else ping -c1 -t1 "$base.$i" >/dev/null 2>&1 & fi
  done; wait
  # Mac prints "(192.168.1.15)", Windows prints the bare address: take every address and keep our /24.
  for ip in $(arp -a | grep -viE 'incomplete|interface' | grep -oE '([0-9]+\.){3}[0-9]+' | grep -F "$base." | grep -vE "\.255$" | grep -vxF "$me" | sort -u); do
    ( port "$ip" 5555 && echo "$ip|" >> "$T/open"
      port "$ip" 6466 && echo "$ip|" >> "$T/remote" ) &
  done; wait
fi

# 3. Merge.
cat "$T/mdns" "$T/open" "$T/remote" 2>/dev/null | cut -d'|' -f1 | sort -u | while read -r ip; do
  name=$(grep "^$ip|" "$T/mdns" 2>/dev/null | head -1 | cut -d'|' -f2)
  port "$ip" 5555 && st=open || st=closed
  echo "$ip adb=$st ${name:-}"
done > "$T/out"
[ -s "$T/out" ] && cat "$T/out" || echo "NO_TV_FOUND (this computer is on ${me:-no network}: is the TV on and on the same Wi-Fi?)"
rm -rf "$T"
