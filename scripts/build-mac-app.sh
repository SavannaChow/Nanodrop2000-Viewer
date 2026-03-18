#!/bin/zsh
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
BUILD_DIR="$ROOT_DIR/.build/release"
DIST_DIR="$ROOT_DIR/dist"
APP_NAME="TBWK Converter"
APP_PATH="$DIST_DIR/$APP_NAME.app"

cd "$ROOT_DIR"
swift build -c release

mkdir -p "$DIST_DIR"
rm -rf "$APP_PATH"

osacompile -o "$APP_PATH" "$ROOT_DIR/app/TBWKConverterDroplet.applescript"

mkdir -p "$APP_PATH/Contents/Resources"
cp "$BUILD_DIR/tbwk-convert" "$APP_PATH/Contents/Resources/tbwk-convert"
chmod +x "$APP_PATH/Contents/Resources/tbwk-convert"

/usr/libexec/PlistBuddy -c "Set :CFBundleDisplayName $APP_NAME" "$APP_PATH/Contents/Info.plist" || true
/usr/libexec/PlistBuddy -c "Set :CFBundleName $APP_NAME" "$APP_PATH/Contents/Info.plist" || true
/usr/libexec/PlistBuddy -c "Add :LSUIElement bool false" "$APP_PATH/Contents/Info.plist" || true

echo "Built app at: $APP_PATH"
