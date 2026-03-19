# Windows App Prototype

This directory is the Windows-native rewrite track.

Structure:

- `src/NanodropViewer.Core`: C# parser and reference spectrum logic
- `src/NanodropViewer.Cli`: cross-platform CLI smoke-test entry point
- `src/NanodropViewer.App`: WPF desktop app skeleton for Windows
- `assets/reference_spectra`: bundled `.jdx` reference spectra
- `build-windows-app.ps1`: publish helper for Windows desktop builds
- `register-tbwk-file-association.ps1`: current-user `.tbwk` / `.twbk` file association helper

Notes:

- `NanodropViewer.Core` and `NanodropViewer.Cli` target .NET 8.
- `NanodropViewer.App` is a Windows-only WPF project targeting .NET 8 for Windows.
- The WPF app now supports opening a `.tbwk` file from the command line and by dragging a file onto the window.
- The app manifest enables per-monitor DPI awareness and long-path support.

Smoke-test parser on this machine:

```bash
dotnet run --project windows-app/src/NanodropViewer.Cli -- examples/nanodrop-dna-measurements-01.twbk windows-app/assets/reference_spectra
```

Build the Windows desktop app on a Windows machine with the .NET SDK installed:

```powershell
cd windows-app
.\build-windows-app.ps1
```

To publish a self-contained build:

```powershell
cd windows-app
.\build-windows-app.ps1 -SelfContained
```

To publish a self-contained single-file build:

```powershell
cd windows-app
.\build-windows-app.ps1 -SelfContained -SingleFile
```

Published files are written to:

```text
windows-app/dist/win-x64/
```

Register `.tbwk` / `.twbk` to open with the published app for the current user:

```powershell
cd windows-app
.\register-tbwk-file-association.ps1
```
