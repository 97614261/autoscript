[CmdletBinding()]
param([switch]$Verify)
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
$root = Split-Path -Parent $PSScriptRoot
. (Join-Path $PSScriptRoot 'apk-output-recovery.ps1')
Repair-StudioApkOutput -ProjectRoot $root -Profile arm64
$tasks = @()
if ($Verify) {
    $tasks += @(':apps:studio-android:testDebugUnitTest', ':android:script-ui:testDebugUnitTest',
        ':android:project-store:testDebugUnitTest', ':android:runtime-client:testDebugUnitTest',
        ':android:runtime-service:testDebugUnitTest', ':apps:studio-android:lintDebug')
}
$tasks += ':apps:studio-android:assembleDebug'
Push-Location -LiteralPath $root
try {
    & (Join-Path $root 'gradlew.bat') @tasks '-Pautoscript.abiProfile=arm64' '--project-cache-dir' (Join-Path $root 'build/profiles/arm64/.gradle') --console=plain
    if ($LASTEXITCODE -ne 0) { throw 'Mobile candidate build failed. No device was modified.' }
    # Incremental ZIP updates can create holes during this very build, not only before it.
    Repair-StudioApkOutput -ProjectRoot $root -Profile arm64
    $candidate = Join-Path $root 'build/profiles/arm64/apps/studio-android/outputs/apk/debug/studio-android-debug.apk'
    if (-not (Test-Path -LiteralPath $candidate -PathType Leaf)) {
        & (Join-Path $root 'gradlew.bat') ':apps:studio-android:assembleDebug' '-Pautoscript.abiProfile=arm64' '--project-cache-dir' (Join-Path $root 'build/profiles/arm64/.gradle') --console=plain
        if ($LASTEXITCODE -ne 0) { throw 'Candidate APK output rebuild failed; previous bytes remain in the verified backup.' }
    }
    Repair-StudioApkOutput -ProjectRoot $root -Profile arm64 -ValidateOnly
    & (Join-Path $PSScriptRoot 'verify-native-packaging.ps1') -Profile arm64
    if (-not $?) { throw 'Mobile candidate packaging verification failed.' }
    if ($Verify) {
        & (Join-Path $PSScriptRoot 'check-repository-layout.ps1')
        if (-not $?) { throw 'Mobile candidate repository layout verification failed.' }
    }
    Write-Host 'arm64 Debug-signed candidate only; no production signing or MuMu installation.'
} finally { Pop-Location }
