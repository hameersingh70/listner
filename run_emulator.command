#!/bin/bash
cd "$(dirname "$0")"
echo "=================================================="
echo " Starting Call Recorder Android Emulator..."
echo "=================================================="
export ANDROID_HOME="${ANDROID_HOME:-/opt/homebrew/share/android-commandlinetools}"
export PATH="$ANDROID_HOME/emulator:$ANDROID_HOME/platform-tools:$PATH"

emulator -avd RecallNexus
