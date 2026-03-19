#!/bin/zsh
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
SOURCE_DIR="$ROOT_DIR/spectrum_database"
MAC_TARGET_DIR="$ROOT_DIR/Sources/NanodropViewerMac/Resources/ReferenceSpectra"

mkdir -p "$MAC_TARGET_DIR"
find "$MAC_TARGET_DIR" -type f -name '*.jdx' -delete
cp "$SOURCE_DIR"/*.jdx "$MAC_TARGET_DIR/"

echo "Synced reference spectra to $MAC_TARGET_DIR"
