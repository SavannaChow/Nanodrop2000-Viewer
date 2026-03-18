#!/bin/zsh
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
APP_PATH="${1:-$ROOT_DIR/dist/TBWK Converter.app}"
ZIP_PATH="$ROOT_DIR/dist/TBWK Converter.zip"

if [[ ! -d "$APP_PATH" ]]; then
  echo "App not found: $APP_PATH" >&2
  exit 1
fi

if [[ -z "${APPLE_ID_TEAM_ID:-}" || -z "${APPLE_ID_USERNAME:-}" || -z "${APPLE_APP_PASSWORD:-}" ]]; then
  echo "Set APPLE_ID_TEAM_ID, APPLE_ID_USERNAME, and APPLE_APP_PASSWORD first." >&2
  exit 1
fi

ditto -c -k --keepParent "$APP_PATH" "$ZIP_PATH"

xcrun notarytool submit "$ZIP_PATH" \
  --apple-id "$APPLE_ID_USERNAME" \
  --password "$APPLE_APP_PASSWORD" \
  --team-id "$APPLE_ID_TEAM_ID" \
  --wait

xcrun stapler staple "$APP_PATH"
echo "Notarized app: $APP_PATH"
