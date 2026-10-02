#!/usr/bin/env bash
# Builds the debug APK using the self-contained toolchain under ~/android-sdk.
set -euo pipefail

export JAVA_HOME="$HOME/android-sdk/jdk"
export ANDROID_HOME="$HOME/android-sdk"
export ANDROID_SDK_ROOT="$HOME/android-sdk"
export PATH="$JAVA_HOME/bin:$ANDROID_HOME/platform-tools:$PATH"

cd "$(dirname "$0")"
./gradlew :app:assembleDebug "$@"

echo
echo "APK: $(pwd)/app/build/outputs/apk/debug/app-debug.apk"
