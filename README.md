# TBWK Converter

A standalone Swift/macOS converter for NanoDrop `.tbwk` files.

It converts each input file into:

- `*_summary.csv`
- `*_spectrum.csv`
- `*_spectra.pdf`

The macOS app is a drag-and-drop droplet: drop one or more files onto the app and it exports the CSV and PDF files next to the source file.

## Repo layout

- `Sources/TBWKCore`: native TBWK parser, CSV export, PDF rendering
- `Sources/tbwk-convert`: command-line entry point
- `app/TBWKConverterDroplet.applescript`: drag-and-drop macOS app wrapper
- `scripts/build-mac-app.sh`: builds the `.app` bundle

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

## Command-line usage

```bash
./.build/debug/tbwk-convert /path/to/file.tbwk
```

You can also pass multiple files:

```bash
./.build/debug/tbwk-convert file1.tbwk file2.tbwk
```
