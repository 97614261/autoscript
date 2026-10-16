[CmdletBinding()]
param(
    [Parameter(Mandatory)][string]$DeviceSerial,
    [Parameter(Mandatory)][string]$ApkPath,
    [switch]$ConfirmTargetRestart,
    [string]$AdbPath = 'C:\Work\AndroidSDK\platform-tools\adb.exe'
)
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
$retentionAdb = $AdbPath
. (Join-Path $PSScriptRoot 'build-install-mumu.ps1')
. (Join-Path $PSScriptRoot 'device-acceptance-result.ps1')
if ($DeviceSerial -notmatch '^[A-Za-z0-9._:-]{1,128}$') { throw 'Invalid explicit device serial' }
$root = [IO.Path]::GetFullPath((Split-Path -Parent $PSScriptRoot))
$candidate = (Resolve-Path -LiteralPath $ApkPath).Path
if ($candidate -ne (Join-Path $root 'android\apps\studio-android\build\outputs\apk\debug\studio-android-debug.apk')) {
    throw 'Only the normal verified development candidate is accepted'
}
. (Join-Path $PSScriptRoot 'apk-output-recovery.ps1')
Repair-StudioApkOutput -ProjectRoot $root -Profile development -ValidateOnly
$hash = (Get-FileHash -LiteralPath $candidate -Algorithm SHA256).Hash
$names = Invoke-BoundedCli $retentionAdb @('-s', $DeviceSerial, 'shell', 'ps', '-A', '-o', 'NAME') 15
Assert-StudioAcceptanceIdleTarget -ProcessNames $names -RestartConfirmed ([bool]$ConfirmTargetRestart)
function Invoke-RetentionPhase([string]$Phase) {
    $output = Invoke-BoundedCli $retentionAdb @('-s', $DeviceSerial, 'shell', 'am', 'instrument', '-w', '-r',
        '-e', 'retentionMode', $Phase, 'com.zhigou.kaifaban.acceptance/com.autoscript.studio.StudioAcceptanceInstrumentation') 60
    Write-Output $output
    $report = ConvertFrom-StudioAcceptanceOutput $output
    if ($Phase -eq 'verify' -and -not $report.cleanup) { throw 'Retention owned-project cleanup failed' }
}
Invoke-RetentionPhase 'prepare'
if ((Get-FileHash -LiteralPath $candidate -Algorithm SHA256).Hash -ne $hash) { throw 'Candidate changed during retention test' }
$install = Invoke-BoundedCli $retentionAdb @('-s', $DeviceSerial, 'install', '--no-streaming', '-r', '-t', $candidate) 180
Write-Output $install
if ($install -notmatch '(?m)^Success\r?$') { throw 'Data-preserving package update failed; owned QA ticket is retained' }
Invoke-RetentionPhase 'verify'
Write-Output "PASS: same-version data-preserving install; exact owned project retained; APK SHA-256 $hash"
