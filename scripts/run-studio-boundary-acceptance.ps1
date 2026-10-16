[CmdletBinding()]
param(
    [Parameter(Mandatory)][string]$DeviceSerial,
    [ValidateSet('boundaries', 'experience', 'performance')][string]$Suite = 'boundaries',
    [switch]$ConfirmTargetRestart,
    [string]$AdbPath = 'C:\Work\AndroidSDK\platform-tools\adb.exe'
)
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
$boundaryAdb = $AdbPath
. (Join-Path $PSScriptRoot 'build-install-mumu.ps1')
. (Join-Path $PSScriptRoot 'device-acceptance-result.ps1')
if ($DeviceSerial -notmatch '^[A-Za-z0-9._:-]{1,128}$') { throw 'Invalid explicit device serial' }
$names = Invoke-BoundedCli $boundaryAdb @('-s', $DeviceSerial, 'shell', 'ps', '-A', '-o', 'NAME') 15
Assert-StudioAcceptanceIdleTarget -ProcessNames $names -RestartConfirmed ([bool]$ConfirmTargetRestart)
$mode = switch ($Suite) { 'experience' { 'experienceOnly' } 'performance' { 'performanceOnly' } default { 'boundariesOnly' } }
try {
    $output = Invoke-BoundedCli $boundaryAdb @('-s', $DeviceSerial, 'shell', 'am', 'instrument', '-w', '-r',
        '-e', $mode, 'true', 'com.zhigou.kaifaban.acceptance/com.autoscript.studio.StudioAcceptanceInstrumentation') 420
} catch {
    $boundaryFailure = $_
    if ($ConfirmTargetRestart -and $boundaryFailure.Exception.Message -match 'timed out after') {
        Invoke-BoundedCli $boundaryAdb @('-s', $DeviceSerial, 'shell', 'am', 'force-stop', 'com.zhigou.kaifaban') 15 | Write-Output
    }
    throw $boundaryFailure
}
Write-Output $output
$report = ConvertFrom-StudioAcceptanceOutput $output
if (-not $report.cleanup) { throw 'Owned boundary fixture cleanup failed' }
Write-Output "PASS: $Suite ($($report.cases.Count) cases) on $DeviceSerial"
