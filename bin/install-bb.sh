#!/usr/bin/env bash

# Improved based upon
# https://github.com/lambdaisland/open-source/blob/bdeb15d185e4f82ef9a07676076b23be11c0e0a1/bin/install_babashka

set -e

# https://gist.github.com/lukechilds/a83e1d7127b78fef38c2914c4ececc3c
gh_latest_release() {
  curl -L --silent "https://api.github.com/repos/$1/releases/latest" | # Get latest release from GitHub api
    grep '"tag_name":' |                                            # Get tag line
    sed -E 's/.*"v([^"]+)".*/\1/'                                   # Pluck JSON value
}

current_platform() {
    case "$(uname -s)" in
        Linux*)  echo linux;;
        Darwin*) echo macos;;
    esac
}

BABASHKA_VERSION=$(gh_latest_release borkdude/babashka)
PLATFORM=$(current_platform)
DOWNLOAD_URL="https://github.com/borkdude/babashka/releases/download/v${BABASHKA_VERSION}/babashka-${BABASHKA_VERSION}-${PLATFORM}-amd64.tar.gz"
INSTALL_DIR="/usr/local/bin"

mkdir -p "${INSTALL_DIR}"
curl -o "/tmp/bb.tar.gz" -sL "${DOWNLOAD_URL}"
tar xf "/tmp/bb.tar.gz" -C "${INSTALL_DIR}"
rm "/tmp/bb.tar.gz"
chmod +x "${INSTALL_DIR}/bb"
