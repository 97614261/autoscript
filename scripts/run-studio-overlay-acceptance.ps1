[CmdletBinding()]
param(
    [Parameter(Mandatory)][string]$DeviceSerial,
    [string]$AdbPath = 'C:\Work\AndroidSDK\platform-tools\adb.exe',
    [switch]$ConfirmTargetRestart,
    [switch]$FinalClosure
)
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
$overlayAcceptanceAdb = $AdbPath
. (Join-Path $PSScriptRoot 'build-install-mumu.ps1')
. (Join-Path $PSScriptRoot 'device-acceptance-result.ps1')
if ($DeviceSerial -notmatch '^[A-Za-z0-9._:-]{1,128}$') { throw 'Invalid explicit device serial' }
# Android instrumentation restarts its TARGET app. Do not silently kill an existing editor draft.
$processNames = Invoke-BoundedCli $overlayAcceptanceAdb @('-s', $DeviceSerial, 'shell', 'ps', '-A', '-o', 'NAME') 15
Assert-StudioAcceptanceIdleTarget -ProcessNames $processNames -RestartConfirmed ([bool]$ConfirmTargetRestart)
# No install/grant/force-stop/private script selection. Fixture independently refuses foreign runs.
$qaArguments = @('-s', $DeviceSerial, 'shell', 'am', 'instrument', '-w', '-r')
if ($FinalClosure) { $qaArguments += @('-e', 'finalClosure', 'true') }
$qaArguments += 'com.zhigou.kaifaban.acceptance/com.autoscript.studio.StudioAcceptanceInstrumentation'
$output = Invoke-BoundedCli $overlayAcceptanceAdb $qaArguments $(if ($FinalClosure) { 420 } else { 210 })
Write-Output $output
$report = ConvertFrom-StudioAcceptanceOutput $output
Write-Output "PASS: Studio own-project overlay on $DeviceSerial ($($report.scope))"
