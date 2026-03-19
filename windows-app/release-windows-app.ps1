param(
    [string]$Configuration = "Release",
    [string]$Runtime = "win-x64"
)

$ErrorActionPreference = "Stop"

$buildScript = Join-Path $PSScriptRoot "build-windows-app.ps1"

& $buildScript -Configuration $Configuration -Runtime $Runtime -SelfContained -SingleFile -ZipOutput

if ($LASTEXITCODE -ne 0) {
    throw "Release packaging failed."
}
