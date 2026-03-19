# TBWK Converter

A standalone Swift/macOS converter for NanoDrop `.tbwk` files.

It converts each input file into:

- `*_summary.csv`
- `*_spectrum.csv`
- `*_spectra.pdf`

The macOS app is a drag-and-drop droplet: drop one or more files onto the app and it exports the CSV and PDF files next to the source file.

This repo also includes an Android viewer prototype under `android-app/`. It parses `.tbwk` directly on-device and shows the sample list, summary values, and absorption spectrum plot in a landscape layout.

This repo also includes a Windows-native rewrite track under `windows-app/`. It rewrites the parser and reference spectrum logic in C# and provides a WPF desktop app skeleton intended for Windows builds.

## Repo layout

- `Sources/TBWKCore`: native TBWK parser, CSV export, PDF rendering
- `Sources/tbwk-convert`: command-line entry point
- `app/TBWKConverterDroplet.applescript`: drag-and-drop macOS app wrapper
- `scripts/build-mac-app.sh`: builds the `.app` bundle
- `android-app/`: Android app project for on-device viewing and plotting
- `windows-app/`: Windows-native rewrite in C# (`Core`, `Cli`, `App`)

## Build

```bash
swift build
```

## Test

```bash
swift test
```

## Build the macOS app

```bash
./scripts/build-mac-app.sh
```

After building, the app bundle will be here:

```text
dist/TBWK Converter.app
```

To sign during build, provide a signing identity:

```bash
CODESIGN_IDENTITY="Developer ID Application: Your Name (TEAMID)" ./scripts/build-mac-app.sh
```

## Notarize the app

If you have Apple Developer credentials, you can notarize the built app:

```bash
chmod +x scripts/notarize-mac-app.sh
APPLE_ID_TEAM_ID="TEAMID" \
APPLE_ID_USERNAME="you@example.com" \
APPLE_APP_PASSWORD="app-specific-password" \
./scripts/notarize-mac-app.sh
```

## Command-line usage

```bash
./.build/debug/tbwk-convert /path/to/file.tbwk
```

You can also pass multiple files:

```bash
./.build/debug/tbwk-convert file1.tbwk file2.tbwk
```

## Android app

The Android project lives in:

```text
android-app/
```

Features implemented in the project:

- open a file with the system picker
- parse `.tbwk` directly on Android in Kotlin
- force landscape layout
- left panel sample list with `Up` and `Down` buttons
- right panel summary values and live spectrum plot

To build the APK, open `android-app/` in Android Studio on a machine with the Android SDK installed, then run the standard `Build APK` flow. This machine did not have Android SDK / Gradle tooling available, so the Android project was written but not compiled into an APK here.

## Windows app

The Windows-native rewrite lives in:

```text
windows-app/
```

On this machine, the cross-platform parts can be smoke-tested with:

```bash
dotnet run --project windows-app/src/NanodropViewer.Cli -- examples/nanodrop-dna-measurements-01.twbk windows-app/assets/reference_spectra
```

The WPF app project is authored in `windows-app/src/NanodropViewer.App`, but it should be built on Windows rather than macOS.
