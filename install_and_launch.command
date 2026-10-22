#!/bin/bash
cd "$(dirname "$0")"
echo "=================================================="
echo " Installing App onto Android device/emulator"
echo "=================================================="
export PATH="${ANDROID_HOME:-/opt/homebrew/share/android-commandlinetools}/platform-tools:/opt/homebrew/bin:$PATH"

echo "Waiting for Android device/emulator to be ready..."
adb wait-for-device
echo "Device detected. Installing APK..."
adb install -r app/build/outputs/apk/debug/app-debug.apk
echo "Launching App..."
adb shell am start -n com.recall.app/.ui.MainActivity
echo "=================================================="
echo " App is now running!"
echo "=================================================="
