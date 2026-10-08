#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
CORE="$ROOT/CLIProxyAPI"
JNI="$ROOT/android-app/app/src/main/jniLibs"
SDK="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-$HOME/Android/Sdk}}"
NDK_VERSION="${NDK_VERSION:-27.2.12479018}"
NDK="${ANDROID_NDK_HOME:-$SDK/ndk/$NDK_VERSION}"
if [[ ! -d "$NDK" ]]; then
  NDK="$(find "$SDK/ndk" -mindepth 1 -maxdepth 1 -type d 2>/dev/null | sort -V | tail -n1 || true)"
fi
[[ -d "$NDK" ]] || { echo "Android NDK not found (recommended r27c / $NDK_VERSION)" >&2; exit 2; }
command -v go >/dev/null || { echo "Go 1.26+ is required" >&2; exit 2; }
[[ "$(go env GOVERSION)" =~ ^go1\.([2][6-9]|[3-9][0-9]) ]] || { echo "CLIProxyAPI requires Go 1.26+" >&2; exit 2; }

case "$(uname -s)-$(uname -m)" in
  Linux-x86_64) HOST=linux-x86_64 ;;
  Darwin-x86_64) HOST=darwin-x86_64 ;;
  Darwin-arm64) HOST=darwin-x86_64 ;; # Google NDK currently ships the macOS x86_64-hosted toolchain usable under translation.
  *) echo "Unsupported build host; set ANDROID_NDK_HOME and edit HOST if needed" >&2; exit 2 ;;
esac
TC="$NDK/toolchains/llvm/prebuilt/$HOST/bin"
DATE="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
LDFLAGS="-s -w -buildid= -checklinkname=0 -linkmode=external"
NDK_MAJOR="$(awk -F= '/^Pkg\.Revision/{gsub(/[[:space:]]/,"",$2); split($2,a,"."); print a[1]; exit}' "$NDK/source.properties" 2>/dev/null || true)"
if [[ -z "$NDK_MAJOR" ]]; then NDK_MAJOR="$(basename "$NDK" | cut -d. -f1)"; fi
if [[ "$NDK_MAJOR" =~ ^[0-9]+$ ]] && (( NDK_MAJOR <= 27 )); then
  LDFLAGS+=" -extldflags=-Wl,-z,max-page-size=16384,-z,common-page-size=16384"
fi
LDFLAGS+=" -X main.Version=android-gui -X main.Commit=bundled-source -X main.BuildDate=$DATE"

build_one() {
  local abi="$1" arch="$2" cc="$3"
  mkdir -p "$JNI/$abi"
  echo "Building $abi"
  (cd "$CORE" && CGO_ENABLED=1 GOOS=android GOARCH="$arch" CC="$TC/$cc" \
    go build -buildvcs=false -trimpath -ldflags="$LDFLAGS" -o "$JNI/$abi/libcliproxyapi.so" ./cmd/server/)
}

build_one arm64-v8a arm64 aarch64-linux-android26-clang
build_one x86_64 amd64 x86_64-linux-android26-clang
