#!/bin/sh
# ==============================================================================
# CLIProxyAPI Termux Installer Alias
# Delegates to the primary install.sh
# ==============================================================================
set -eu

INSTALLER_URL="https://raw.githubusercontent.com/tsaQB/cliproxyapi-android/main/install.sh"
SH_BIN=$(command -v bash 2>/dev/null || command -v sh 2>/dev/null || echo "sh")

# Use the sibling install.sh only when this alias runs from a real file.
# When piped (curl ... | sh), $0 is the shell name and must not be trusted.
case "$0" in
    */install-termux.sh|install-termux.sh)
        if [ -f "$0" ]; then
            SCRIPT_DIR="$(cd "$(dirname "$0")" 2>/dev/null && pwd || true)"
            if [ -n "$SCRIPT_DIR" ] && [ -f "$SCRIPT_DIR/install.sh" ]; then
                exec "$SH_BIN" "$SCRIPT_DIR/install.sh" "$@"
            fi
        fi
        ;;
esac

if ! command -v curl >/dev/null 2>&1; then
    printf "\033[1;31m[X] curl is required. Install it with: pkg install curl\033[0m\n" >&2
    exit 1
fi

# Download first, then run: piping curl into a shell hides download failures.
TMP_SCRIPT=$(mktemp 2>/dev/null || mktemp -t cpa_termux)
trap 'rm -f "$TMP_SCRIPT"' EXIT
trap 'rm -f "$TMP_SCRIPT"; exit 130' HUP INT TERM

if ! curl -fsSL "$INSTALLER_URL" -o "$TMP_SCRIPT"; then
    printf "\033[1;31m[X] Failed to download the installer from %s\033[0m\n" "$INSTALLER_URL" >&2
    exit 1
fi

status=0
"$SH_BIN" "$TMP_SCRIPT" "$@" || status=$?
exit "$status"
