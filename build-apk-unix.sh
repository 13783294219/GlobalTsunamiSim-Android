#!/usr/bin/env sh
set -eu
if ! command -v gradle >/dev/null 2>&1; then
  echo 'Gradle not found. Open this folder in Android Studio and choose Build > Build APK(s).' >&2
  exit 1
fi
gradle assembleDebug
echo 'APK output: app/build/outputs/apk/debug/app-debug.apk'
