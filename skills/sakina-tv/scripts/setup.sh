#!/bin/bash
# Gets this computer ready, Mac or Windows: adb, Java 17, Android SDK 34, Python, yt-dlp, ffmpeg, and the two
# source repos. Safe to run twice. Writes ~/sakina-tv/env.sh, which every later step sources first.
# On Windows this runs in Git Bash (the shell Claude Code uses there) and installs with winget.
set -e
W=~/sakina-tv; mkdir -p "$W"
REPO="${SAKINA_REPO:-https://github.com/shariefabdelmonem-netizen/sakina-tv}"

case "$(uname -s)" in
  Darwin) OS=mac ;;
  MINGW*|MSYS*|CYGWIN*) OS=win ;;
  *) echo "UNSUPPORTED_OS $(uname -s)"; exit 1 ;;
esac

if [ $OS = mac ]; then
  command -v brew >/dev/null || { echo "NEED_BREW: install Homebrew first from https://brew.sh"; exit 1; }
  need=""
  command -v adb >/dev/null || need="$need android-platform-tools"
  [ -d "$(brew --prefix)/opt/openjdk@17" ] || need="$need openjdk@17"
  command -v sdkmanager >/dev/null || need="$need android-commandlinetools"
  command -v yt-dlp >/dev/null || need="$need yt-dlp"
  command -v ffmpeg >/dev/null || need="$need ffmpeg"
  [ -n "$need" ] && brew install $need
  BREW=$(brew --prefix)
  cat > "$W/env.sh" <<E
export JAVA_HOME=$BREW/opt/openjdk@17
export ANDROID_HOME=$BREW/share/android-commandlinetools
export PATH=\$JAVA_HOME/bin:\$PATH
export PY=python3
export SAKINA_OS=mac
E
  . "$W/env.sh"
  [ -d "$ANDROID_HOME/platforms/android-34" ] && [ -d "$ANDROID_HOME/build-tools/34.0.0" ] || \
    yes | sdkmanager --sdk_root="$ANDROID_HOME" "platforms;android-34" "build-tools;34.0.0" >/dev/null
else
  command -v winget >/dev/null || { echo "NEED_WINGET: install 'App Installer' from the Microsoft Store"; exit 1; }
  LAD=$(cygpath -u "$LOCALAPPDATA")
  WG="winget install -e --silent --accept-source-agreements --accept-package-agreements --id"
  # Each check looks where the installer puts things, not on PATH: this shell does not see a PATH that
  # changed after it started.
  jdk() { ls -d "/c/Program Files/Eclipse Adoptium/jdk-17"* 2>/dev/null | head -1; }
  pyexe() { ls -d "$LAD"/Programs/Python/Python3*/python.exe "/c/Program Files/Python3"*/python.exe 2>/dev/null | head -1; }
  [ -n "$(jdk)" ] || $WG EclipseAdoptium.Temurin.17.JDK || true   # asks "allow changes?" once: the person clicks Yes
  [ -n "$(pyexe)" ] || $WG Python.Python.3.12 --scope user || true
  [ -x "$LAD/Microsoft/WinGet/Links/yt-dlp.exe" ] || $WG yt-dlp.yt-dlp || true
  [ -x "$LAD/Microsoft/WinGet/Links/ffmpeg.exe" ] || $WG Gyan.FFmpeg || true
  [ -n "$(jdk)" ] || { echo "FAILED: Java 17 did not install (was the Yes prompt declined?)"; exit 1; }
  [ -n "$(pyexe)" ] || { echo "FAILED: Python did not install"; exit 1; }
  # Android Studio's SDK folder if there is one, so nothing is downloaded twice.
  SDK="$LAD/Android/Sdk"; mkdir -p "$SDK"
  if [ ! -f "$SDK/cmdline-tools/latest/bin/sdkmanager.bat" ]; then
    T=$(mktemp -d)
    curl -fsSL -o "$T/t.zip" https://dl.google.com/android/repository/commandlinetools-win-13114758_latest.zip
    (cd "$T" && unzip -q t.zip)
    mkdir -p "$SDK/cmdline-tools"; rm -rf "$SDK/cmdline-tools/latest"
    mv "$T/cmdline-tools" "$SDK/cmdline-tools/latest"; rm -rf "$T"
  fi
  PYDIR=$(dirname "$(pyexe)")
  cat > "$W/env.sh" <<E
export JAVA_HOME="$(cygpath -m "$(jdk)")"
export ANDROID_HOME="$(cygpath -m "$SDK")"
export PATH="$(jdk)/bin:$SDK/platform-tools:$SDK/cmdline-tools/latest/bin:$LAD/Microsoft/WinGet/Links:$PYDIR:\$PATH"
export PY="$PYDIR/python.exe"
export SAKINA_OS=win
E
  . "$W/env.sh"
  if [ ! -d "$SDK/platforms/android-34" ] || [ ! -d "$SDK/build-tools/34.0.0" ] || [ ! -x "$SDK/platform-tools/adb.exe" ]; then
    yes | sdkmanager.bat --sdk_root="$ANDROID_HOME" --licenses >/dev/null 2>&1 || true
    sdkmanager.bat --sdk_root="$ANDROID_HOME" "platform-tools" "platforms;android-34" "build-tools;34.0.0" >/dev/null
  fi
fi

[ -d "$W/app" ] || git clone -q "$REPO" "$W/app"
[ -d "$W/cleanyourtv" ] || git clone -q https://github.com/Gathin23/cleanyourtv "$W/cleanyourtv"
# Forward slashes even on Windows: local.properties is a Java properties file, where a backslash is an escape.
for d in "$W/app/sakina-app" "$W/cleanyourtv/launcher" "$W/cleanyourtv/addons/prayer-times"; do
  [ -f "$d/local.properties" ] || echo "sdk.dir=$ANDROID_HOME" > "$d/local.properties"
done
V="$W/cleanyourtv/tools/venv"
if [ ! -x "$V/bin/python" ] && [ ! -x "$V/Scripts/python.exe" ]; then
  "$PY" -m venv "$V"
  if [ -x "$V/bin/pip" ]; then "$V/bin/pip" install -q -r "$W/cleanyourtv/tools/requirements.txt"
  else "$V/Scripts/python.exe" -m pip install -q -r "$W/cleanyourtv/tools/requirements.txt"; fi
fi
adb version >/dev/null && java -version >/dev/null 2>&1 && "$PY" --version >/dev/null && echo "READY $OS $W"
