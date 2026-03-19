param(
    [string]$Configuration = "Release",
    [string]$Runtime = "win-x64",
    [switch]$SelfContained,
    [switch]$SingleFile
)

$ErrorActionPreference = "Stop"

$localDotnet = Join-Path $env:USERPROFILE ".dotnet\dotnet.exe"
$dotnet = if (Test-Path $localDotnet) { $localDotnet } else { "dotnet" }
$project = Join-Path $PSScriptRoot "src\NanodropViewer.App\NanodropViewer.App.csproj"
$publishDir = Join-Path $PSScriptRoot "dist\$Runtime"

if (Test-Path $publishDir) {
    Remove-Item $publishDir -Recurse -Force
}

$arguments = @(
    "publish"
    $project
    "-c"
    $Configuration
    "-r"
    $Runtime
    "--output"
    $publishDir
)

if ($SelfContained) {
    $arguments += "--self-contained"
    $arguments += "true"
} else {
    $arguments += "--self-contained"
    $arguments += "false"
}

if ($SingleFile) {
    $arguments += "-p:PublishSingleFile=true"
}

if ($SelfContained -and $SingleFile) {
    $arguments += "-p:IncludeNativeLibrariesForSelfExtract=true"
}

$arguments += "-p:DebugType=None"
$arguments += "-p:DebugSymbols=false"

Write-Host "Publishing NanodropViewer.App to $publishDir"
& $dotnet @arguments

Write-Host ""
Write-Host "Build completed."
Write-Host "Output: $publishDir"
Write-Host "Executable: $(Join-Path $publishDir 'NanodropViewer.App.exe')"
