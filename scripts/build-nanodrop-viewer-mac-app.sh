#!/bin/zsh
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
ARM64_BUILD_DIR="$ROOT_DIR/.build/arm64-apple-macosx/release"
X86_64_BUILD_DIR="$ROOT_DIR/.build/x86_64-apple-macosx/release"
DIST_DIR="$ROOT_DIR/dist"
APP_NAME="nanodrop 2000 viewer"
APP_PATH="$DIST_DIR/$APP_NAME.app"
EXECUTABLE_NAME="NanodropViewerMac"
RESOURCE_BUNDLE="TBWKConverter_NanodropViewerMac.bundle"
SOURCE_ICON="$ROOT_DIR/android-app/icon.png"
ICON_NAME="AppIcon"
ICONSET_DIR="$DIST_DIR/$ICON_NAME.iconset"
ICNS_PATH="$DIST_DIR/$ICON_NAME.icns"
MARKETING_VERSION="${MARKETING_VERSION:-1.0}"
BUILD_NUMBER="${BUILD_NUMBER:-1}"
BUNDLE_IDENTIFIER="${BUNDLE_IDENTIFIER:-com.savannachow.nanodrop2000viewer}"

cd "$ROOT_DIR"
"$ROOT_DIR/scripts/sync-spectrum-database.sh"
swift build --arch arm64 -c release --product "$EXECUTABLE_NAME"
swift build --arch x86_64 -c release --product "$EXECUTABLE_NAME"

mkdir -p "$DIST_DIR"
rm -rf "$APP_PATH"
rm -rf "$ICONSET_DIR" "$ICNS_PATH"

mkdir -p "$ICONSET_DIR"
sips -z 16 16 "$SOURCE_ICON" --out "$ICONSET_DIR/icon_16x16.png" >/dev/null
sips -z 32 32 "$SOURCE_ICON" --out "$ICONSET_DIR/icon_16x16@2x.png" >/dev/null
sips -z 32 32 "$SOURCE_ICON" --out "$ICONSET_DIR/icon_32x32.png" >/dev/null
sips -z 64 64 "$SOURCE_ICON" --out "$ICONSET_DIR/icon_32x32@2x.png" >/dev/null
sips -z 128 128 "$SOURCE_ICON" --out "$ICONSET_DIR/icon_128x128.png" >/dev/null
sips -z 256 256 "$SOURCE_ICON" --out "$ICONSET_DIR/icon_128x128@2x.png" >/dev/null
sips -z 256 256 "$SOURCE_ICON" --out "$ICONSET_DIR/icon_256x256.png" >/dev/null
sips -z 512 512 "$SOURCE_ICON" --out "$ICONSET_DIR/icon_256x256@2x.png" >/dev/null
sips -z 512 512 "$SOURCE_ICON" --out "$ICONSET_DIR/icon_512x512.png" >/dev/null
sips -z 1024 1024 "$SOURCE_ICON" --out "$ICONSET_DIR/icon_512x512@2x.png" >/dev/null
iconutil -c icns "$ICONSET_DIR" -o "$ICNS_PATH"

mkdir -p "$APP_PATH/Contents/MacOS" "$APP_PATH/Contents/Resources"

cat > "$APP_PATH/Contents/Info.plist" <<PLIST
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "https://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
    <key>CFBundleDevelopmentRegion</key>
    <string>en</string>
    <key>CFBundleDisplayName</key>
    <string>nanodrop 2000 viewer</string>
    <key>CFBundleExecutable</key>
    <string>NanodropViewerMac</string>
    <key>CFBundleIconFile</key>
    <string>AppIcon</string>
    <key>CFBundleIdentifier</key>
    <string>${BUNDLE_IDENTIFIER}</string>
    <key>CFBundleInfoDictionaryVersion</key>
    <string>6.0</string>
    <key>CFBundleName</key>
    <string>nanodrop 2000 viewer</string>
    <key>CFBundlePackageType</key>
    <string>APPL</string>
    <key>CFBundleDocumentTypes</key>
    <array>
        <dict>
            <key>CFBundleTypeName</key>
            <string>TBWK Worksheet</string>
            <key>CFBundleTypeRole</key>
            <string>Editor</string>
            <key>LSHandlerRank</key>
            <string>Owner</string>
            <key>LSItemContentTypes</key>
            <array>
                <string>com.savannachow.tbwk</string>
            </array>
        </dict>
    </array>
    <key>CFBundleShortVersionString</key>
    <string>${MARKETING_VERSION}</string>
    <key>CFBundleVersion</key>
    <string>${BUILD_NUMBER}</string>
    <key>LSApplicationCategoryType</key>
    <string>public.app-category.utilities</string>
    <key>LSMinimumSystemVersion</key>
    <string>13.0</string>
    <key>NSPrincipalClass</key>
    <string>NSApplication</string>
    <key>NSHighResolutionCapable</key>
    <true/>
    <key>UTExportedTypeDeclarations</key>
    <array>
        <dict>
            <key>UTTypeIdentifier</key>
            <string>com.savannachow.tbwk</string>
            <key>UTTypeDescription</key>
            <string>TBWK Worksheet</string>
            <key>UTTypeConformsTo</key>
            <array>
                <string>public.data</string>
            </array>
            <key>UTTypeTagSpecification</key>
            <dict>
                <key>public.filename-extension</key>
                <array>
                    <string>tbwk</string>
                    <string>twbk</string>
                </array>
                <key>public.mime-type</key>
                <string>application/x-tbwk</string>
            </dict>
        </dict>
    </array>
</dict>
</plist>
PLIST

if [[ ! -f "$ARM64_BUILD_DIR/$EXECUTABLE_NAME" ]]; then
  echo "Missing arm64 build output: $ARM64_BUILD_DIR/$EXECUTABLE_NAME" >&2
  exit 1
fi

if [[ ! -f "$X86_64_BUILD_DIR/$EXECUTABLE_NAME" ]]; then
  echo "Missing x86_64 build output: $X86_64_BUILD_DIR/$EXECUTABLE_NAME" >&2
  exit 1
fi

lipo -create \
  "$ARM64_BUILD_DIR/$EXECUTABLE_NAME" \
  "$X86_64_BUILD_DIR/$EXECUTABLE_NAME" \
  -output "$APP_PATH/Contents/MacOS/$EXECUTABLE_NAME"

chmod +x "$APP_PATH/Contents/MacOS/$EXECUTABLE_NAME"
cp "$ICNS_PATH" "$APP_PATH/Contents/Resources/$ICON_NAME.icns"

if [[ -d "$ARM64_BUILD_DIR/$RESOURCE_BUNDLE" ]]; then
  cp -R "$ARM64_BUILD_DIR/$RESOURCE_BUNDLE" "$APP_PATH/Contents/Resources/$RESOURCE_BUNDLE"
fi

if [[ -n "${CODESIGN_IDENTITY:-}" ]]; then
  codesign --force --deep --sign "$CODESIGN_IDENTITY" "$APP_PATH"
fi

rm -rf "$ICONSET_DIR"

echo "Built app at: $APP_PATH"
