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
  - `nanodrop2000viewer-macos-vX.Y.Z.zip`
- Android:
  - `nanodrop2000viewer-android-vX.Y.Z.apk`
- Windows:
  - `nanodrop2000viewer-windows-vX.Y.Z.zip`
  - or `nanodrop2000viewer-windows-vX.Y.Z-installer.exe`

If architecture matters, append it explicitly:

- `nanodrop2000viewer-windows-vX.Y.Z-x64.zip`
- `nanodrop2000viewer-macos-vX.Y.Z-arm64.zip`

## What goes into GitHub Releases

Recommended release assets:

- signed/notarized macOS app bundle packaged as `.zip`
- Android `apk`
- Windows portable `.zip` or installer `.exe`

Do not commit generated release binaries into `main`.
Keep release artifacts attached to GitHub Releases instead.

## Build notes

- macOS build and packaging scripts live under `scripts/`
- Android builds from `android-app/`
- Windows builds from `windows-app/`

## Suggested release notes structure

- Version summary
- Platform assets
- New features
- Fixes
- Known limitations
