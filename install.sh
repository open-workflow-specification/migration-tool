#!/usr/bin/env bash
# Installs swf-migrate to /usr/local/bin (or ~/bin if not writable).
# Usage: curl -fsSL https://raw.githubusercontent.com/skavgou/spec-convert/main/install.sh | bash
set -euo pipefail

REPO="skavgou/spec-convert"
INSTALL_DIR="/usr/local/bin"

# Detect platform
OS="$(uname -s)"
case "$OS" in
  Linux*)  ASSET="swf-migrate-linux" ;;
  Darwin*) ASSET="swf-migrate-macos" ;;
  *)       echo "Unsupported OS: $OS" >&2; exit 1 ;;
esac

# Resolve latest release tag
TAG=$(curl -fsSL "https://api.github.com/repos/${REPO}/releases/latest" \
  | grep '"tag_name"' | head -1 | sed 's/.*"tag_name": *"\(.*\)".*/\1/')

if [[ -z "$TAG" ]]; then
  echo "Could not determine latest release tag." >&2
  exit 1
fi

URL="https://github.com/${REPO}/releases/download/${TAG}/${ASSET}"
TMP=$(mktemp)

echo "Downloading swf-migrate ${TAG} for ${OS}..."
curl -fsSL "$URL" -o "$TMP"
chmod +x "$TMP"

# Fall back to ~/bin if /usr/local/bin is not writable
if [[ ! -w "$INSTALL_DIR" ]]; then
  INSTALL_DIR="$HOME/bin"
  mkdir -p "$INSTALL_DIR"
  echo "Note: /usr/local/bin is not writable, installing to $INSTALL_DIR"
  echo "Make sure $INSTALL_DIR is on your PATH."
fi

mv "$TMP" "${INSTALL_DIR}/swf-migrate"
echo "Installed to ${INSTALL_DIR}/swf-migrate"
echo "Run: swf-migrate --help"
