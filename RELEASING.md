# Releasing

This repository uses `main` as the release branch.

Platform development branches:

- `macos`
- `android`
- `windows`

Those branches are platform-focused working branches.
The `main` branch is the integrated release branch that contains:

- macOS source and packaging scripts
- `android-app/`
- `windows-app/`
- shared reference spectra and documentation

## Release flow

1. Finish and test platform-specific work on `macos`, `android`, or `windows`.
2. Merge or restore the validated platform changes into `main`.
3. Build release artifacts from `main`.
4. Tag `main` with a version:
   - `v1.0.0`
   - `v1.1.0`
5. Create a GitHub Release from that tag.
6. Upload the platform binaries/installers as release assets.

## Artifact naming rules

Use a consistent versioned naming scheme for release assets:

- macOS:
  - `nanodrop2000viewer-macos-vX.X.X.dmg`
- Android:
  - `nanodrop2000viewer-android-vX.X.X.apk`
- Windows:
  - `nanodrop2000viewer-windows-vX.X.X-x64.zip`
  - or `nanodrop2000viewer-windows-vX.X.X-installer.exe`

If architecture matters, append it explicitly:

- `nanodrop2000viewer-windows-vX.X.X-x64.zip`

## What goes into GitHub Releases

Recommended release assets:

- signed/notarized macOS app packaged as `.dmg`
- Android `apk`
- Windows portable `.zip` or installer `.exe`

Do not commit generated release binaries into `main`.
Keep release artifacts attached to GitHub Releases instead.

## Build notes

- macOS build and packaging scripts live under `scripts/`
- Android builds from `android-app/`
- Windows builds from `windows-app/`
- Shared `.jdx` files live only under `spectrum_database/`
- Platform builds should sync or embed from `spectrum_database/`, not maintain hand-edited copies

## Unified release script

This repository includes a top-level helper script:

```bash
./scripts/build-release-assets.sh
```

Behavior:

- On macOS:
  - builds the macOS app and DMG
  - builds the Android debug APK
  - copies the release-named assets into `dist/`
- On Windows:
  - runs the Windows publish script
  - creates the Windows zip
  - copies the release-named asset into `dist/`

The script validates that macOS, Android, and Windows version numbers match before building.

Expected outputs in `dist/`:

- `nanodrop2000viewer-macos-vX.X.X.dmg`
- `nanodrop2000viewer-android-vX.X.X.apk`
- `nanodrop2000viewer-windows-vX.X.X-x64.zip`

### Android build and rename

Build the debug APK from `main`:

```bash
cd android-app
./gradlew assembleDebug
```

Default output:

```text
android-app/app/build/outputs/apk/debug/app-debug.apk
```

Recommended release asset rename:

```bash
cp "android-app/app/build/outputs/apk/debug/app-debug.apk" \
   "android-app/app/build/outputs/apk/debug/nanodrop2000viewer-android-vX.X.X.apk"
```

If you later produce a release build, keep the same versioned naming pattern and avoid uploading the raw `app-debug.apk` filename to GitHub Releases.

### Windows build and rename

Build on a Windows machine from `main`:

```powershell
git checkout main
git pull
cd windows-app
Set-ExecutionPolicy -Scope Process -ExecutionPolicy Bypass
.\build-windows-app.ps1
```

If PowerShell blocks script execution, use the temporary process-scoped policy above.
It only affects the current shell session and is the recommended low-risk option.

Default output:

```text
windows-app\dist\win-x64\
```

Recommended variants:

```powershell
.\build-windows-app.ps1 -SelfContained
.\build-windows-app.ps1 -SelfContained -SingleFile
.\build-windows-app.ps1 -SelfContained -SingleFile -ZipOutput
```

If `-ZipOutput` is used, the script produces:

```text
windows-app\dist\NanodropViewer-win-x64.zip
```

Recommended release asset rename:

```powershell
Copy-Item "windows-app\dist\NanodropViewer-win-x64.zip" `
          "windows-app\dist\nanodrop2000viewer-windows-vX.X.X-x64.zip"
```

If you publish an installer instead of a zip, use:

```text
nanodrop2000viewer-windows-vX.X.X-installer.exe
```

## Suggested release notes structure

- Version summary
- Platform assets
- New features
- Fixes
- Known limitations
