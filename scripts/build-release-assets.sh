#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
DIST_DIR="$ROOT_DIR/dist"
ANDROID_APK_SOURCE="$ROOT_DIR/android-app/app/build/outputs/apk/debug/app-debug.apk"
MACOS_DMG_SOURCE="$ROOT_DIR/dist/nanodrop 2000 viewer.dmg"
WINDOWS_ZIP_SOURCE="$ROOT_DIR/windows-app/dist/NanodropViewer-win-x64.zip"

read_android_version() {
  sed -n 's/.*versionName = "\([^"]*\)".*/\1/p' "$ROOT_DIR/android-app/app/build.gradle.kts" | head -n 1
}

read_macos_version() {
  sed -n 's/^MARKETING_VERSION="\${MARKETING_VERSION:-\([^}]*\)}"$/\1/p' "$ROOT_DIR/scripts/build-nanodrop-viewer-mac-app.sh" | head -n 1
}

read_windows_version() {
  sed -n 's/.*<Version>\([^<]*\)<\/Version>.*/\1/p' "$ROOT_DIR/windows-app/src/NanodropViewer.App/NanodropViewer.App.csproj" | head -n 1
}

ensure_versions_match() {
  local android_version macos_version windows_version
  android_version="$(read_android_version)"
  macos_version="$(read_macos_version)"
  windows_version="$(read_windows_version)"

  if [[ -z "$android_version" || -z "$macos_version" || -z "$windows_version" ]]; then
    echo "Unable to detect version from one or more platform build files." >&2
    exit 1
  fi

  if [[ "$android_version" != "$macos_version" || "$android_version" != "$windows_version" ]]; then
    echo "Platform versions do not match:" >&2
    echo "  Android: $android_version" >&2
    echo "  macOS:   $macos_version" >&2
    echo "  Windows: $windows_version" >&2
    exit 1
  fi

  RELEASE_VERSION="$android_version"
}

copy_macos_release_asset() {
  local target="$DIST_DIR/nanodrop2000viewer-macos-v$RELEASE_VERSION.dmg"
  cp "$MACOS_DMG_SOURCE" "$target"
  echo "macOS release asset: $target"
}

copy_android_release_asset() {
  local target="$DIST_DIR/nanodrop2000viewer-android-v$RELEASE_VERSION.apk"
  cp "$ANDROID_APK_SOURCE" "$target"
  echo "Android release asset: $target"
}

copy_windows_release_asset() {
  local target="$DIST_DIR/nanodrop2000viewer-windows-v$RELEASE_VERSION-x64.zip"
  cp "$WINDOWS_ZIP_SOURCE" "$target"
  echo "Windows release asset: $target"
}

build_macos_and_android() {
  mkdir -p "$DIST_DIR"
  "$ROOT_DIR/scripts/build-nanodrop-viewer-mac-app.sh"
  if [[ ! -f "$MACOS_DMG_SOURCE" ]]; then
    echo "Expected macOS DMG not found: $MACOS_DMG_SOURCE" >&2
    exit 1
  fi
  copy_macos_release_asset

  (
    cd "$ROOT_DIR/android-app"
    ./gradlew assembleDebug
  )
  if [[ ! -f "$ANDROID_APK_SOURCE" ]]; then
    echo "Expected Android APK not found: $ANDROID_APK_SOURCE" >&2
    exit 1
  fi
  copy_android_release_asset
}

find_windows_powershell() {
  if command -v pwsh.exe >/dev/null 2>&1; then
    echo "pwsh.exe"
    return
  fi
  if command -v powershell.exe >/dev/null 2>&1; then
    echo "powershell.exe"
    return
  fi
  if command -v pwsh >/dev/null 2>&1; then
    echo "pwsh"
    return
  fi
  if command -v powershell >/dev/null 2>&1; then
    echo "powershell"
    return
  fi
  echo ""
}

windows_repo_root() {
  if command -v cygpath >/dev/null 2>&1; then
    cygpath -w "$ROOT_DIR"
  elif pwd -W >/dev/null 2>&1; then
    pwd -W
  else
    echo "$ROOT_DIR"
  fi
}

build_windows() {
  mkdir -p "$DIST_DIR"
  local ps
  ps="$(find_windows_powershell)"
  if [[ -z "$ps" ]]; then
    echo "PowerShell is required to build the Windows app." >&2
    exit 1
  fi

  local windows_root
  windows_root="$(windows_repo_root)"
  local script_path="$windows_root\\windows-app\\build-windows-app.ps1"

  "$ps" -ExecutionPolicy Bypass -File "$script_path" -SelfContained -SingleFile -ZipOutput

  if [[ ! -f "$WINDOWS_ZIP_SOURCE" ]]; then
    echo "Expected Windows ZIP not found: $WINDOWS_ZIP_SOURCE" >&2
    exit 1
  fi
  copy_windows_release_asset
}

main() {
  ensure_versions_match
  echo "Release version: v$RELEASE_VERSION"

  case "$(uname -s)" in
    Darwin)
      build_macos_and_android
      ;;
    MINGW*|MSYS*|CYGWIN*)
      build_windows
      ;;
    *)
      echo "Unsupported host OS: $(uname -s)" >&2
      echo "Run this script on macOS to build macOS + Android, or on Windows to build Windows." >&2
      exit 1
      ;;
  esac
}

main "$@"
