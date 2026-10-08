#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
OUT="$ROOT/android-app/app/src/main/assets/cpa/management.html"
TAG="${1:-}"
if [[ -n "$TAG" ]]; then API="https://api.github.com/repos/router-for-me/Cli-Proxy-API-Management-Center/releases/tags/$TAG"; else API="https://api.github.com/repos/router-for-me/Cli-Proxy-API-Management-Center/releases/latest"; fi
json="$(curl -fsSL -H 'User-Agent: CLIProxyAPI-Android-GUI' "$API")"
url="$(python3 -c 'import json,sys; d=json.load(sys.stdin); print(next(x["browser_download_url"] for x in d["assets"] if x["name"]=="management.html"))' <<<"$json")"
tag="$(python3 -c 'import json,sys; print(json.load(sys.stdin)["tag_name"])' <<<"$json")"
curl -fL --retry 3 "$url" -o "$OUT"
sha256sum "$OUT"
echo "Bundled Management Center $tag"
