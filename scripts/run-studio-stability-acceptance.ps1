[CmdletBinding()]
param(
    [Parameter(Mandatory)][string]$DeviceSerial,
    [ValidateRange(60, 900)][int]$DurationSeconds = 900,
    [switch]$ConfirmTargetRestart,
    [string]$AdbPath = 'C:\Work\AndroidSDK\platform-tools\adb.exe'
)
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
$stabilityAdb = $AdbPath
. (Join-Path $PSScriptRoot 'build-install-mumu.ps1')
. (Join-Path $PSScriptRoot 'device-acceptance-result.ps1')
if ($DeviceSerial -notmatch '^[A-Za-z0-9._:-]{1,128}$') { throw 'Invalid explicit device serial' }
$names = Invoke-BoundedCli $stabilityAdb @('-s', $DeviceSerial, 'shell', 'ps', '-A', '-o', 'NAME') 15
Assert-StudioAcceptanceIdleTarget -ProcessNames $names -RestartConfirmed ([bool]$ConfirmTargetRestart)
$info = [Diagnostics.ProcessStartInfo]::new()
$info.FileName = $stabilityAdb
# Values are validated, not caller-supplied shell source. No private project ID is passed.
$info.Arguments = "-s $DeviceSerial shell am instrument -w -r -e stabilityOnly true -e soakSeconds $DurationSeconds com.zhigou.kaifaban.acceptance/com.autoscript.studio.StudioAcceptanceInstrumentation"
$info.UseShellExecute = $false
$info.CreateNoWindow = $true
$info.RedirectStandardOutput = $true
$info.RedirectStandardError = $true
$process = [Diagnostics.Process]::new()
$process.StartInfo = $info
try {
    if (-not $process.Start()) { throw 'Could not start stability instrumentation' }
    $outputTask = $process.StandardOutput.ReadToEndAsync()
    $errorTask = $process.StandardError.ReadToEndAsync()
    if (-not $process.WaitForExit(($DurationSeconds + 180) * 1000)) {
        # Stop only our adb invocation and its explicitly approved target, not the VM.
        $process.Kill()
        if ($ConfirmTargetRestart) {
            Invoke-BoundedCli $stabilityAdb @('-s', $DeviceSerial, 'shell', 'am', 'force-stop', 'com.zhigou.kaifaban') 15 | Write-Output
        }
        throw 'Stability instrumentation timed out; partial run is not a pass'
    }
    $output = $outputTask.GetAwaiter().GetResult()
    $errors = $errorTask.GetAwaiter().GetResult()
    Write-Output $output
    if ($process.ExitCode -ne 0) { throw 'Stability adb invocation failed' }
    if ($errors.Length -gt 65536) { throw 'Stability stderr budget exceeded' }
    $report = ConvertFrom-StudioAcceptanceOutput $output
    if (-not $report.cleanup) { throw 'Stability fixture cleanup failed' }
    Write-Output "PASS: Studio stability for $DurationSeconds seconds on $DeviceSerial"
} finally { $process.Dispose() }
