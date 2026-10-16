[CmdletBinding()]
param(
    [Parameter(Mandatory)][string]$DeviceSerial,
    [ValidateSet('vision', 'preprocessing', 'script-ui', 'sandbox', 'ocr-diagnostics', 'ocr-evaluation', 'ocr-candidate', 'providers', 'provider-soak', 'missing-components')][string]$Suite = 'vision',
    [string]$AdbPath = 'C:\Work\AndroidSDK\platform-tools\adb.exe'
)
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
$acceptanceAdb = $AdbPath
. (Join-Path $PSScriptRoot 'build-install-mumu.ps1')
. (Join-Path $PSScriptRoot 'device-acceptance-result.ps1')
# This invokes an already installed, separate synthetic test APK. Never install, grant permissions,
# select a default device, read a Studio project, capture the screen, or inject business input here.
if ($DeviceSerial -notmatch '^[A-Za-z0-9._:-]{1,128}$') { throw 'Invalid explicit device serial' }
$arguments = @('-s', $DeviceSerial, 'shell', 'am', 'instrument', '-w', '-r')
$mode = switch ($Suite) {
    'preprocessing' { 'preprocessingOnly' }
    'script-ui' { 'scriptUiOnly' }
    'sandbox' { 'sandboxOnly' }
    'ocr-diagnostics' { 'ocrDiagnostics' }
    'ocr-evaluation' { 'ocrEvaluation' }
    'ocr-candidate' { 'ocrCandidate' }
    'providers' { 'providersOnly' }
    'provider-soak' { 'providersOnly' }
    'missing-components' { 'missingComponentsOnly' }
    default { $null }
}
if ($mode) { $arguments += @('-e', $mode, 'true') }
if ($Suite -eq 'provider-soak') { $arguments += @('-e', 'providerSoak', 'true') }
$arguments += 'com.autoscript.vision.acceptance/com.autoscript.runtime.service.VisionAcceptanceInstrumentation'
$output = Invoke-BoundedCli $acceptanceAdb $arguments $(if ($Suite -eq 'provider-soak') { 480 } elseif ($Suite -eq 'ocr-candidate') { 180 } else { 120 })
# Preserve the bounded public synthetic report even when its result is FAIL.
Write-Output $output
$report = ConvertFrom-StudioAcceptanceOutput $output
Write-Output "PASS: $Suite on $DeviceSerial ($($report.scope))"
