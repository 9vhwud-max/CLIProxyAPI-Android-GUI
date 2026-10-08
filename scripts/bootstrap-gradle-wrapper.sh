#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/../android-app" && pwd)"
JAR="$ROOT/gradle/wrapper/gradle-wrapper.jar"
mkdir -p "$(dirname "$JAR")"
curl -fL --retry 3 https://raw.githubusercontent.com/gradle/gradle/v9.3.1/gradle/wrapper/gradle-wrapper.jar -o "$JAR"
echo "Gradle wrapper bootstrap complete"
