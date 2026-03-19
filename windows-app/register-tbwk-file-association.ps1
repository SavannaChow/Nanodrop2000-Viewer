param(
    [string]$AppExePath = "",
    [switch]$Remove
)

$ErrorActionPreference = "Stop"

if ([string]::IsNullOrWhiteSpace($AppExePath)) {
    $AppExePath = Join-Path $PSScriptRoot "dist\win-x64\NanodropViewer.exe"
}

$appExe = [System.IO.Path]::GetFullPath($AppExePath)
$progId = "NanodropViewer.TBWK"
$iconValue = "`"$appExe`",0"
$commandValue = "`"$appExe`" `"%1`""

if ($Remove) {
    Remove-Item "HKCU:\Software\Classes\.tbwk" -Recurse -Force -ErrorAction SilentlyContinue
    Remove-Item "HKCU:\Software\Classes\.twbk" -Recurse -Force -ErrorAction SilentlyContinue
    Remove-Item "HKCU:\Software\Classes\$progId" -Recurse -Force -ErrorAction SilentlyContinue
    Write-Host "Removed TBWK file association from the current user profile."
    exit 0
}

if (-not (Test-Path $appExe)) {
    throw "App executable not found: $appExe"
}

New-Item "HKCU:\Software\Classes\.tbwk" -Force | Out-Null
Set-Item "HKCU:\Software\Classes\.tbwk" -Value $progId

New-Item "HKCU:\Software\Classes\.twbk" -Force | Out-Null
Set-Item "HKCU:\Software\Classes\.twbk" -Value $progId

New-Item "HKCU:\Software\Classes\$progId" -Force | Out-Null
Set-Item "HKCU:\Software\Classes\$progId" -Value "Nanodrop Viewer TBWK File"

New-Item "HKCU:\Software\Classes\$progId\DefaultIcon" -Force | Out-Null
Set-Item "HKCU:\Software\Classes\$progId\DefaultIcon" -Value $iconValue

New-Item "HKCU:\Software\Classes\$progId\shell\open\command" -Force | Out-Null
Set-Item "HKCU:\Software\Classes\$progId\shell\open\command" -Value $commandValue

Write-Host "Registered TBWK file association for:"
Write-Host $appExe
