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

$restoreArguments = @(
    "restore"
    $project
    "-r"
    $Runtime
)

$restoreArguments += "-p:DebugType=None"
$restoreArguments += "-p:DebugSymbols=false"

$publishArguments = @(
    "publish"
    $project
    "-c"
    $Configuration
    "-r"
    $Runtime
    "--output"
    $publishDir
)

$arguments = @(
    $publishArguments
)

if ($SelfContained) {
    $publishArguments += "--self-contained"
    $publishArguments += "true"
} else {
    $publishArguments += "--self-contained"
    $publishArguments += "false"
}

if ($SingleFile) {
    $publishArguments += "-p:PublishSingleFile=true"
}

if ($SelfContained -and $SingleFile) {
    $publishArguments += "-p:IncludeNativeLibrariesForSelfExtract=true"
}

$publishArguments += "-p:DebugType=None"
$publishArguments += "-p:DebugSymbols=false"

Write-Host "Restoring NanodropViewer.App for $Runtime"
& $dotnet @restoreArguments
Write-Host "Publishing NanodropViewer.App to $publishDir"
& $dotnet @publishArguments

Write-Host ""
Write-Host "Build completed."
Write-Host "Output: $publishDir"
Write-Host "Executable: $(Join-Path $publishDir 'NanodropViewer.App.exe')"
