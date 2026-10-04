[CmdletBinding()]
param([switch]$Verify)
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
$root = Split-Path -Parent $PSScriptRoot
$tasks = @()
if ($Verify) {
    $tasks += @(':apps:studio-android:testDebugUnitTest', ':android:script-ui:testDebugUnitTest',
        ':android:project-store:testDebugUnitTest', ':android:runtime-client:testDebugUnitTest',
        ':android:runtime-service:testDebugUnitTest', ':apps:studio-android:lintDebug')
}
$tasks += ':apps:studio-android:assembleDebug'
Push-Location -LiteralPath $root
try {
    & (Join-Path $root 'gradlew.bat') @tasks '-Pautoscript.abiProfile=arm64' '--project-cache-dir' 'build/profiles/arm64/.gradle' --console=plain
    if ($LASTEXITCODE -ne 0) { throw 'Mobile candidate build failed. No device was modified.' }
    & (Join-Path $PSScriptRoot 'verify-native-packaging.ps1') -Profile arm64
    if (-not $?) { throw 'Mobile candidate packaging verification failed.' }
    Write-Host 'arm64 Debug-signed candidate only; no production signing or MuMu installation.'
} finally { Pop-Location }
