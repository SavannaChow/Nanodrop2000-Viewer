# Windows App Prototype

This directory is the Windows-native rewrite track.

Structure:

- `src/NanodropViewer.Core`: C# parser and reference spectrum logic
- `src/NanodropViewer.Cli`: cross-platform CLI smoke-test entry point
- `src/NanodropViewer.App`: WPF desktop app skeleton for Windows
- `assets/reference_spectra`: bundled `.jdx` reference spectra

Notes:

- `NanodropViewer.Core` and `NanodropViewer.Cli` can be built on macOS/Linux with .NET 7.
- `NanodropViewer.App` is a Windows-only WPF project. It is authored here but should be compiled on Windows.

Smoke-test parser on this machine:

```bash
dotnet run --project windows-app/src/NanodropViewer.Cli -- examples/nanodrop-dna-measurements-01.twbk windows-app/assets/reference_spectra
```
