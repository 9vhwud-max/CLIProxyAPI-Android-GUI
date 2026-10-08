#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")" && pwd)"
"$ROOT/scripts/fetch-webui.sh"
"$ROOT/scripts/build-go.sh"
[[ -f "$ROOT/android-app/gradle/wrapper/gradle-wrapper.jar" ]] || "$ROOT/scripts/bootstrap-gradle-wrapper.sh"
(cd "$ROOT/android-app" && ./gradlew assembleDebug)
mkdir -p "$ROOT/dist"
cp "$ROOT/android-app/app/build/outputs/apk/debug/app-debug.apk" "$ROOT/dist/CLIProxyAPI-Android-debug.apk"
echo "APK: $ROOT/dist/CLIProxyAPI-Android-debug.apk"
