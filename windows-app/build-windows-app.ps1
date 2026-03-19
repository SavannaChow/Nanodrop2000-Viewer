param(
    [string]$Configuration = "Release",
    [string]$Runtime = "win-x64",
    [switch]$SelfContained,
    [switch]$SingleFile,
    [switch]$ZipOutput
)

$ErrorActionPreference = "Stop"

$localDotnet = Join-Path $env:USERPROFILE ".dotnet\dotnet.exe"
$dotnet = if (Test-Path $localDotnet) { $localDotnet } else { "dotnet" }
$project = Join-Path $PSScriptRoot "src\NanodropViewer.App\NanodropViewer.App.csproj"
$publishDir = Join-Path $PSScriptRoot "dist\$Runtime"
$appExeName = "NanodropViewer.exe"

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

Write-Host "Restoring NanoDrop 2000 Viewer for $Runtime"
& $dotnet @restoreArguments
if ($LASTEXITCODE -ne 0) {
    throw "dotnet restore failed."
}
Write-Host "Publishing NanoDrop 2000 Viewer to $publishDir"
& $dotnet @publishArguments
if ($LASTEXITCODE -ne 0) {
    throw "dotnet publish failed."
}

if ($ZipOutput -and (Test-Path $publishDir)) {
    $zipPath = Join-Path $PSScriptRoot "dist\NanodropViewer-$Runtime.zip"
    if (Test-Path $zipPath) {
        Remove-Item $zipPath -Force
    }
    Compress-Archive -Path (Join-Path $publishDir '*') -DestinationPath $zipPath
    Write-Host "Zip: $zipPath"
}

Write-Host ""
Write-Host "Build completed."
Write-Host "Output: $publishDir"
Write-Host "Executable: $(Join-Path $publishDir $appExeName)"
