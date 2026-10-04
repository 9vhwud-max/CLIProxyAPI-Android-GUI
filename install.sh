#!/bin/sh
# ==============================================================================
# CLIProxyAPI for Android (Termux Native Non-Root)
# High-Performance AI Gateway · Distribution by tsaQB/cliproxyapi-android
# ==============================================================================
set -eu

REPO="tsaQB/cliproxyapi-android"
INSTALLER_URL="https://raw.githubusercontent.com/${REPO}/main/install.sh"
TAR_URL="https://github.com/${REPO}/releases/latest/download/cliproxyapi-android-arm64.tar.gz"
LATEST_URL="https://github.com/${REPO}/releases/latest"
DASHBOARD_REPO="router-for-me/Cli-Proxy-API-Management-Center"
DEFAULT_PORT="8317"
DEFAULT_SECRET="admin123"

# Color palette
C_RESET="\033[0m"
C_BOLD="\033[1m"
C_DIM="\033[2m"
C_CYAN="\033[1;36m"
C_GREEN="\033[1;32m"
C_YELLOW="\033[1;33m"
C_RED="\033[1;31m"
C_PURPLE="\033[1;35m"
C_WHITE="\033[1;37m"

PREFIX_DIR="${PREFIX:-/data/data/com.termux/files/usr}"
BASE_DIR="${HOME}/.cliproxyapi"
BIN_DIR="${BASE_DIR}/bin"
AUTH_DIR="${BASE_DIR}/auths"
LOG_DIR="${BASE_DIR}/logs"
STATIC_DIR="${BASE_DIR}/static"
CONFIG_FILE="${BASE_DIR}/config.yaml"
BIN_PATH="${BIN_DIR}/cli-proxy-api"
LOG_FILE="${LOG_DIR}/service.log"
WRAPPER_PATH="${PREFIX_DIR}/bin/cliproxyapi"
DASHBOARD_FILE="${STATIC_DIR}/management.html"

step() { printf "%b[%s/6]%b %b%s%b\n" "${C_CYAN}" "$1" "${C_RESET}" "${C_BOLD}" "$2" "${C_RESET}"; }
ok()   { printf "      %b✔ %s%b\n" "${C_GREEN}" "$1" "${C_RESET}"; }
info() { printf "      %b• %s%b\n" "${C_DIM}" "$1" "${C_RESET}"; }
warn() { printf "      %b⚠️  %s%b\n" "${C_YELLOW}" "$1" "${C_RESET}"; }
die()  { printf "      %b❌ %s%b\n\n" "${C_RED}" "$1" "${C_RESET}" >&2; exit 1; }

# List PIDs whose command line starts with the given executable path.
# Reads /proc directly so it works without procps (pgrep/pkill) installed.
find_pids() {
    for d in /proc/[0-9]*; do
        [ -r "$d/cmdline" ] || continue
        cmd=$(tr '\000' ' ' < "$d/cmdline" 2>/dev/null) || continue
        case "$cmd" in
            "$1"|"$1 "*) printf "%s\n" "${d#/proc/}" ;;
        esac
    done
}

stop_pids() {
    pids=$(find_pids "$1")
    [ -n "$pids" ] || return 0
    # shellcheck disable=SC2086
    kill $pids 2>/dev/null || true
    i=0
    while [ $i -lt 10 ] && [ -n "$(find_pids "$1")" ]; do
        sleep 0.5 2>/dev/null || sleep 1
        i=$((i + 1))
    done
    pids=$(find_pids "$1")
    # shellcheck disable=SC2086
    [ -z "$pids" ] || kill -9 $pids 2>/dev/null || true
}

# Config readers (support both the legacy and the v8 config layout)
strip_yaml_value() {
    sed -e 's/[[:space:]]#.*$//' -e 's/^[[:space:]]*//' -e 's/[[:space:]]*$//' \
        -e 's/^"\(.*\)"$/\1/' -e "s/^'\(.*\)'\$/\1/"
}

read_secret() {
    grep -E '^[[:space:]]*secret-key:' "$1" 2>/dev/null | head -n 1 | sed 's/^[^:]*://' | strip_yaml_value
}

read_port() {
    p=$(grep -E '^[[:space:]]*port:[[:space:]]*[0-9]+' "$1" 2>/dev/null | head -n 1 | sed 's/^[^:]*://' | strip_yaml_value)
    printf "%s" "${p:-$DEFAULT_PORT}"
}

read_api_key() {
    awk '
        /^[[:space:]]*api-keys:[[:space:]]*$/ { inlist = 1; next }
        inlist && /^[[:space:]]*-/ { sub(/^[[:space:]]*-[[:space:]]*/, ""); print; exit }
        inlist && /^[[:space:]]*[A-Za-z0-9_-]+:/ { inlist = 0 }
    ' "$1" 2>/dev/null | strip_yaml_value
}

describe_secret() {
    # shellcheck disable=SC2016 # literal bcrypt prefixes, not expansions
    case "$1" in
        "") printf "(empty - Management API disabled)" ;;
        '$2a$'*|'$2b$'*|'$2y$'*) printf "(hashed - use the secret you set earlier)" ;;
        *) printf "%s" "$1" ;;
    esac
}

clear 2>/dev/null || true

printf "%b" "${C_CYAN}"
cat << 'EOF'
  ____ _     ___ ____                      _    ____ ___
 / ___| |   |_ _|  _ \ _ __ _____  ___   _/ \  |  _ \_ _|
| |   | |    | || |_) | '__/ _ \ \/ / | | / _ \ | |_) | |
| |___| |___ | ||  __/| | | (_) >  <| |_| / ___ \|  __/| |
 \____|_____|___|_|   |_|  \___/_/\_\\__, /_/   \_\_|  |___|
                                     |___/
EOF
printf "%b" "${C_RESET}"
printf "%b         Android & Termux Native Distribution%b\n" "${C_DIM}" "${C_RESET}"
printf "%b                  Maintained by tsaQB%b\n\n" "${C_PURPLE}" "${C_RESET}"

# 1. Platform & Architecture Check
step 1 "🔍 Checking platform and CPU architecture..."
ARCH=$(uname -m)
case "$ARCH" in
    aarch64|arm64) ;;
    *) die "Unsupported architecture: $ARCH (ARM64 required)" ;;
esac
[ -d "${PREFIX_DIR}/bin" ] || die "Termux not detected (${PREFIX_DIR}/bin is missing). Run this installer inside Termux."
ok "Compatible CPU: $ARCH (ARM64)"
echo

# 2. Dependency Check
step 2 "📦 Verifying system packages..."
need_pkg=""
for c in curl tar gzip; do
    command -v "$c" >/dev/null 2>&1 || need_pkg="$need_pkg $c"
done

if [ -n "$need_pkg" ]; then
    printf "      %b⚡ Installing missing dependencies:%s...%b\n" "${C_YELLOW}" "$need_pkg" "${C_RESET}"
    pkg update -y >/dev/null 2>&1 || true
    # shellcheck disable=SC2086
    pkg install -y $need_pkg || die "Could not install:$need_pkg. Run 'pkg install$need_pkg' manually."
fi
ok "Required tools ready (curl, tar, gzip)"
echo

# 3. Directory Setup
step 3 "📁 Preparing workspace directories..."
mkdir -p "${BIN_DIR}" "${AUTH_DIR}" "${LOG_DIR}" "${STATIC_DIR}"
chmod 700 "${AUTH_DIR}" 2>/dev/null || true

WAS_RUNNING=0
if [ -n "$(find_pids "${BIN_PATH}")" ]; then
    WAS_RUNNING=1
    printf "      %b🛑 Stopping active daemon before upgrade...%b\n" "${C_YELLOW}" "${C_RESET}"
    stop_pids "${BIN_PATH}"
fi
ok "Workspace ready at ~/.cliproxyapi"
echo

# 4. Fetch Latest Release
step 4 "🌐 Fetching release from GitHub..."

TMP_ROOT="${TMPDIR:-${PREFIX_DIR}/tmp}"
mkdir -p "$TMP_ROOT"
TMP_DIR=$(mktemp -d "${TMP_ROOT}/cpa_android.XXXXXX") || die "Could not create a temporary directory in ${TMP_ROOT}"
cleanup() { rm -rf "$TMP_DIR"; }
trap cleanup EXIT
trap 'cleanup; exit 130' HUP INT TERM

# Resolve the release tag from the redirect (no jq, no API rate limit).
# The tag is informational only: the download below always uses "latest".
RELEASE_TAG=$(curl -fsSL -o /dev/null -w '%{url_effective}' "$LATEST_URL" 2>/dev/null | sed -n 's#.*/tag/##p' || true)
if [ -n "$RELEASE_TAG" ]; then
    printf "      %b• Release version: %b%s%b\n" "${C_DIM}" "${C_WHITE}" "$RELEASE_TAG" "${C_RESET}"
else
    info "Release version: latest"
fi

info "Downloading Android Bionic bundle..."
curl -fsSL "$TAR_URL" -o "$TMP_DIR/cpa-android-arm64.tar.gz" \
    || die "Failed to download release package from ${TAR_URL}. Please check your network connection."

mkdir -p "$TMP_DIR/extract"
tar -xzf "$TMP_DIR/cpa-android-arm64.tar.gz" -C "$TMP_DIR/extract" || die "Downloaded archive is corrupted"
[ -f "$TMP_DIR/extract/cli-proxy-api" ] || die "Binary 'cli-proxy-api' not found inside the release archive"
chmod 755 "$TMP_DIR/extract/cli-proxy-api"
mv -f "$TMP_DIR/extract/cli-proxy-api" "${BIN_PATH}"

if [ -s "$TMP_DIR/extract/management.html" ]; then
    mv -f "$TMP_DIR/extract/management.html" "${DASHBOARD_FILE}"
elif [ -s "${DASHBOARD_FILE}" ]; then
    warn "Dashboard missing from the archive. Keeping the existing dashboard."
else
    warn "Dashboard missing from the archive. Creating fallback placeholder..."
    cat << 'HTML' > "${DASHBOARD_FILE}"
<!DOCTYPE html><html><head><meta charset="utf-8"><title>CLIProxyAPI</title></head><body><h1>CLIProxyAPI Dashboard</h1><p>Please update your dashboard from <a href="https://github.com/router-for-me/Cli-Proxy-API-Management-Center">Management Center</a>.</p></body></html>
HTML
fi
chmod 644 "${DASHBOARD_FILE}"
ok "Android NDK binary & WebUI deployed"
echo

# 5. Configuration Setup
step 5 "⚙️  Configuring service profile..."
if [ -f "${CONFIG_FILE}" ]; then
    ok "Existing configuration preserved: ~/.cliproxyapi/config.yaml"
else
    NEW_API_KEY=$(dd if=/dev/urandom bs=16 count=1 2>/dev/null | od -An -tx1 | tr -d ' \n')
    cat << EOF > "${CONFIG_FILE}"
# CLIProxyAPI Configuration for Android / Termux
host: "0.0.0.0"
port: ${DEFAULT_PORT}

api-keys:
  - "${NEW_API_KEY}"

remote-management:
  allow-remote: true
  secret-key: "${DEFAULT_SECRET}"
  disable-control-panel: false
  panel-github-repository: "https://github.com/${DASHBOARD_REPO}"

auth-dir: "${AUTH_DIR}"
log-level: "info"
logging-to-file: true
logs-max-total-size-mb: 25
usage-statistics-enabled: true

routing:
  strategy: "round-robin"

# Prevent false 429 rate limit triggers from Google Antigravity sensor
antigravity:
  sensitive-words:
    - Nous
    - Research

# Model alias mappings for Hermes tool calling compatibility
oauth-model-alias:
  antigravity:
    - name: "gemini-3.8-flash-high"
      alias: "gemini-3.8-flash"
      fork: true
    - name: "gemini-3.8-flash-high"
      alias: "gemini-3.8-flash-customtools"
      fork: true
EOF
    ok "Config created: ~/.cliproxyapi/config.yaml"
fi
chmod 600 "${CONFIG_FILE}"

ADMIN_KEY=$(read_secret "${CONFIG_FILE}")
API_KEY=$(read_api_key "${CONFIG_FILE}")
PORT=$(read_port "${CONFIG_FILE}")
echo

# 6. Wrapper Installation: a small generated header plus a static body
step 6 "🔗 Installing CLI command launcher..."
cat << EOF > "${WRAPPER_PATH}.tmp"
#!${PREFIX_DIR}/bin/sh
# CLIProxyAPI management wrapper (generated by cliproxyapi-android)
BIN="${BIN_PATH}"
CONFIG="${CONFIG_FILE}"
LOG_FILE="${LOG_FILE}"
STATIC_DIR="${STATIC_DIR}"
INSTALLER_URL="${INSTALLER_URL}"
EOF
cat << 'EOF' >> "${WRAPPER_PATH}.tmp"
export MANAGEMENT_STATIC_PATH="$STATIC_DIR"
# Android has no /etc/resolv.conf: force the cgo resolver (bionic getaddrinfo)
export GODEBUG=netdns=cgo

find_pids() {
  for d in /proc/[0-9]*; do
    [ -r "$d/cmdline" ] || continue
    cmd=$(tr '\000' ' ' < "$d/cmdline" 2>/dev/null) || continue
    case "$cmd" in
      "$BIN"|"$BIN "*) printf "%s\n" "${d#/proc/}" ;;
    esac
  done
}

first_pid() { find_pids | head -n 1; }

get_port() {
  p=$(grep -E '^[[:space:]]*port:[[:space:]]*[0-9]+' "$CONFIG" 2>/dev/null | head -n 1 | sed 's/^[^:]*:[[:space:]]*//; s/[^0-9].*$//')
  printf "%s" "${p:-8317}"
}

print_endpoints() {
  port=$(get_port)
  echo "🔗 Server URL : http://127.0.0.1:${port}"
  echo "🌐 Dashboard  : http://127.0.0.1:${port}/management.html"
}

start_proc() {
  if [ -n "$(first_pid)" ]; then
    echo "⚠️  CLIProxyAPI is already running (PID: $(first_pid))."
    return 0
  fi
  echo "🚀 Starting CLIProxyAPI background daemon..."
  mkdir -p "$(dirname "$LOG_FILE")"
  if command -v setsid >/dev/null 2>&1; then
    setsid "$BIN" -config "$CONFIG" < /dev/null >> "$LOG_FILE" 2>&1 &
  else
    nohup "$BIN" -config "$CONFIG" < /dev/null >> "$LOG_FILE" 2>&1 &
  fi
  sleep 1
  if [ -n "$(first_pid)" ]; then
    echo "✅ CLIProxyAPI is running (PID: $(first_pid))"
    print_endpoints
  else
    echo "❌ Failed to start. Check logs: $LOG_FILE"
    return 1
  fi
}

stop_proc() {
  pids=$(find_pids)
  if [ -z "$pids" ]; then
    echo "ℹ️  CLIProxyAPI is not running."
    return 0
  fi
  # shellcheck disable=SC2086
  kill $pids 2>/dev/null || true
  i=0
  while [ $i -lt 10 ] && [ -n "$(first_pid)" ]; do
    sleep 0.5 2>/dev/null || sleep 1
    i=$((i + 1))
  done
  pids=$(find_pids)
  # shellcheck disable=SC2086
  [ -z "$pids" ] || kill -9 $pids 2>/dev/null || true
  if [ -n "$(first_pid)" ]; then
    echo "❌ Could not stop CLIProxyAPI (PID: $(first_pid))."
    return 1
  fi
  echo "🛑 CLIProxyAPI daemon stopped."
}

status_proc() {
  pid=$(first_pid)
  if [ -n "$pid" ]; then
    echo "🟢 CLIProxyAPI is running (PID: $pid)"
    print_endpoints
    return 0
  fi
  echo "🔴 CLIProxyAPI is not running."
  return 1
}

logs_proc() {
  if [ -f "$LOG_FILE" ]; then
    tail -n 50 -f "$LOG_FILE"
  else
    echo "ℹ️  No logs found yet at $LOG_FILE"
  fi
}

update_proc() {
  echo "🌐 Updating CLIProxyAPI via official installer..."
  tmp=$(mktemp 2>/dev/null || mktemp -t cpa_update) || return 1
  if ! curl -fsSL "$INSTALLER_URL" -o "$tmp"; then
    rm -f "$tmp"
    echo "❌ Failed to download the installer from $INSTALLER_URL"
    return 1
  fi
  status=0
  sh "$tmp" || status=$?
  rm -f "$tmp"
  return "$status"
}

usage() {
  echo "CLIProxyAPI Management Commands:"
  echo "  cliproxyapi start      - Start service in background"
  echo "  cliproxyapi stop       - Stop background service"
  echo "  cliproxyapi restart    - Restart service"
  echo "  cliproxyapi status     - View service status, PID, and endpoints"
  echo "  cliproxyapi logs       - Stream real-time service logs"
  echo "  cliproxyapi update     - Upgrade binary and WebUI to latest release"
  echo "  cliproxyapi run        - Run in foreground console"
  echo "  cliproxyapi <options>  - Pass flags directly (e.g. -antigravity-login)"
}

case "${1:-}" in
  start)          start_proc ;;
  stop)           stop_proc ;;
  restart)        stop_proc; sleep 1; start_proc ;;
  status)         status_proc ;;
  logs|log)       logs_proc ;;
  update|upgrade) update_proc ;;
  run)            shift; exec "$BIN" -config "$CONFIG" "$@" ;;
  help)           usage ;;
  "")             usage ;;
  *)              exec "$BIN" -config "$CONFIG" "$@" ;;
esac
EOF
chmod 755 "${WRAPPER_PATH}.tmp"
mv -f "${WRAPPER_PATH}.tmp" "${WRAPPER_PATH}"
ok "Command 'cliproxyapi' registered at ${WRAPPER_PATH}"
echo

# Restart daemon if it was running before upgrade
if [ "$WAS_RUNNING" -eq 1 ]; then
    printf "      %b🔄 Resuming CLIProxyAPI background daemon...%b\n" "${C_CYAN}" "${C_RESET}"
    export MANAGEMENT_STATIC_PATH="${STATIC_DIR}"
    export GODEBUG=netdns=cgo
    (
        cd "${BASE_DIR}" || exit 0
        if command -v setsid >/dev/null 2>&1; then
            setsid "${BIN_PATH}" -config "${CONFIG_FILE}" < /dev/null >> "${LOG_FILE}" 2>&1 &
        else
            nohup "${BIN_PATH}" -config "${CONFIG_FILE}" < /dev/null >> "${LOG_FILE}" 2>&1 &
        fi
    )
    sleep 1
    DAEMON_PID=$(find_pids "${BIN_PATH}" | head -n 1)
    if [ -n "$DAEMON_PID" ]; then
        ok "Service daemon resumed (PID: ${DAEMON_PID})"
    else
        warn "Could not resume the daemon. Check logs: ${LOG_FILE}"
    fi
    echo
fi

# Final Summary Card
printf "%b────────────────────────────────────────────────────%b\n" "${C_GREEN}" "${C_RESET}"
printf "  %b🎉 Installation Complete!%b\n" "${C_BOLD}" "${C_RESET}"
printf "%b────────────────────────────────────────────────────%b\n\n" "${C_GREEN}" "${C_RESET}"

printf "  %b• WebUI Dashboard%b : %bhttp://127.0.0.1:%s/management.html%b\n" "${C_BOLD}" "${C_RESET}" "${C_CYAN}" "${PORT}" "${C_RESET}"
printf "  %b• Secret Key%b      : %b%s%b\n" "${C_BOLD}" "${C_RESET}" "${C_YELLOW}" "$(describe_secret "${ADMIN_KEY}")" "${C_RESET}"
printf "  %b• Client API Key%b  : %b%s%b\n" "${C_BOLD}" "${C_RESET}" "${C_WHITE}" "${API_KEY:-(none configured)}" "${C_RESET}"
printf "  %b• Configuration%b   : %b~/.cliproxyapi/config.yaml%b\n\n" "${C_BOLD}" "${C_RESET}" "${C_DIM}" "${C_RESET}"

printf "  %bQuick Start Commands:%b\n" "${C_BOLD}" "${C_RESET}"
printf "    %b$ cliproxyapi start%b   Start service in background\n" "${C_CYAN}" "${C_RESET}"
printf "    %b$ cliproxyapi status%b  Check service status\n" "${C_CYAN}" "${C_RESET}"
printf "    %b$ cliproxyapi logs%b    Stream live logs\n" "${C_CYAN}" "${C_RESET}"
printf "    %b$ cliproxyapi update%b  Upgrade to latest release\n" "${C_CYAN}" "${C_RESET}"
printf "    %b$ cliproxyapi stop%b    Stop background daemon\n\n" "${C_CYAN}" "${C_RESET}"
printf "%b────────────────────────────────────────────────────%b\n\n" "${C_GREEN}" "${C_RESET}"
