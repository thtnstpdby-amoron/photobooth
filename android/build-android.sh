#!/usr/bin/env bash
# Builds app-debug.apk from the command line (Linux) and installs/launches via ADB.
set -e
cd "$(dirname "$0")"
export TMPDIR="$HOME/tmp"; mkdir -p "$TMPDIR"
SDK="$HOME/android-sdk"; GV=8.7

command -v java >/dev/null || { echo "Install a JDK first: sudo apt install openjdk-17-jdk"; exit 1; }

if [ ! -d "$SDK/cmdline-tools/latest" ]; then
  mkdir -p "$SDK/cmdline-tools"
  curl -fL -o "$TMPDIR/cmd.zip" https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip
  unzip -q "$TMPDIR/cmd.zip" -d "$SDK/cmdline-tools" && mv "$SDK/cmdline-tools/cmdline-tools" "$SDK/cmdline-tools/latest"
fi

export ANDROID_HOME="$SDK"
yes | "$SDK/cmdline-tools/latest/bin/sdkmanager" --licenses >/dev/null || true
"$SDK/cmdline-tools/latest/bin/sdkmanager" "platform-tools" "platforms;android-34" "build-tools;34.0.0"

if [ ! -x "$HOME/gradle-$GV/bin/gradle" ]; then
  curl -fL -o "$TMPDIR/gradle.zip" "https://services.gradle.org/distributions/gradle-$GV-bin.zip"
  unzip -q "$TMPDIR/gradle.zip" -d "$HOME"
fi

echo "sdk.dir=$SDK" > local.properties

echo "Building and installing to device..."

# Run installDebug without --no-daemon so future builds are fast
if "$HOME/gradle-$GV/bin/gradle" installDebug; then
    echo "Install successful! Launching the app on your phone..."
    # Use the adb executable downloaded by your script to launch the app
    "$SDK/platform-tools/adb" shell am start -n com.photobooth.kiosk/.MainActivity
else
    echo "Build failed. Check your Kotlin code for errors."
fi