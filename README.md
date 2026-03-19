# nanodrop 2000 viewer

Cross-platform NanoDrop `.tbwk` tooling with three platform targets in one repository:

- macOS app and Swift core
- Android app
- Windows-native rewrite

`main` is the integrated release branch.

## Branch model

Primary platform branches:

- `macos`
- `android`
- `windows`

Release branch:

- `main`

Work can happen on the platform branches first, then be integrated into `main` for tagged releases such as:

- `v1.0.0`
- `v1.1.0`

## Repo layout

- `Sources/`
  Swift sources for the macOS app, Swift core, and CLI.
- `android-app/`
  Android app that parses `.tbwk` directly on-device.
- `windows-app/`
  Windows-native rewrite in C# and WPF.
- `spectrum_database/`
  Shared reference spectra source files used by all platforms.
- `scripts/`
  Build, packaging, and sync scripts.
- `examples/`
  Sample `.tbwk` files for testing.
- `tests/`
  Swift test target and remaining test fixtures.
- `RELEASING.md`
  Release flow and artifact naming rules.

## Platform status

### macOS

The macOS version includes:

- direct `.tbwk` parsing
- sample browsing
- spectrum plotting
- reference spectrum overlay
- CSV/PDF export
- file association support for `.tbwk` / `.twbk`

Build:

```bash
./scripts/sync-spectrum-database.sh
swift build
./scripts/build-nanodrop-viewer-mac-app.sh
```

### Android

The Android version includes:

- direct `.tbwk` parsing in Kotlin
- sample browsing
- portrait and landscape layouts
- spectrum plotting
- reference spectrum overlay
- CSV/PDF export
- Android file association support for `.tbwk` / `.twbk`

Build:

```bash
cd android-app
./gradlew assembleDebug
```

The Android build syncs `../spectrum_database/*.jdx` into generated assets automatically.

### Windows

The Windows version is a Windows-native rewrite:

- C# `tbwk` parser
- C# `jdx` parser and normalization logic
- WPF desktop app structure
- Windows-specific packaging and file-association scripts

On non-Windows machines, the cross-platform parser can be smoke-tested with:

```bash
dotnet build windows-app/src/NanodropViewer.Cli/NanodropViewer.Cli.csproj
dotnet windows-app/src/NanodropViewer.Cli/bin/Debug/net8.0/NanodropViewer.Cli.dll examples/nanodrop-dna-measurements-01.twbk spectrum_database
```

The WPF app itself should be built on Windows.

## Release/build policy

Use `main` for all formal releases.

Do not commit generated binaries into `main`.
Attach built apps/installers to GitHub Releases instead.

See [RELEASING.md](/Users/savannachow/Github/tbwk-opener/RELEASING.md) for:

- release flow
- tag strategy
- artifact naming rules
- release asset guidance
