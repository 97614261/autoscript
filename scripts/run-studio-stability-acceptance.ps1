[CmdletBinding()]
param(
    [Parameter(Mandatory)][string]$DeviceSerial,
    [ValidateRange(60, 1800)][int]$DurationSeconds = 900,
    [switch]$ConfirmTargetRestart,
    [ValidatePattern('^[0-9a-f]{64}$')][string]$ExpectedTargetApkSha256,
    [ValidatePattern('^[0-9a-f]{64}$')][string]$ExpectedTestApkSha256,
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
$digestArgument = if ($ExpectedTargetApkSha256) { " -e expectedTargetApkSha256 $ExpectedTargetApkSha256" } else { '' }
$info.Arguments = "-s $DeviceSerial shell am instrument -w -r -e stabilityOnly true -e soakSeconds $DurationSeconds$digestArgument com.zhigou.kaifaban.acceptance/com.autoscript.studio.StudioAcceptanceInstrumentation"
$info.UseShellExecute = $false
$info.CreateNoWindow = $true
$info.RedirectStandardOutput = $true
$info.RedirectStandardError = $true
$process = [Diagnostics.Process]::new()
$process.StartInfo = $info
$started = $false
try {
    if (-not $process.Start()) { throw 'Could not start stability instrumentation' }
    $started = $true
    $errorTask = $process.StandardError.ReadToEndAsync()
    $outputBuffer = [Text.StringBuilder]::new()
    $runClock = [Diagnostics.Stopwatch]::StartNew()
    $lineTask = $process.StandardOutput.ReadLineAsync()
    while ($true) {
        if ($runClock.Elapsed.TotalSeconds -gt $DurationSeconds + 180) {
            # Do not restart/kill the App to turn a failed endurance run into a pass.
            $process.Kill()
            throw 'Stability instrumentation timed out; partial run is not a pass'
        }
        if (-not $lineTask.Wait(1000)) { continue }
        $line = $lineTask.GetAwaiter().GetResult()
        if ($null -eq $line) { break }
        if ($outputBuffer.Length + $line.Length + 2 -gt 65536) { throw 'Stability output exceeded budget; not a pass' }
        [void]$outputBuffer.AppendLine($line)
        Write-Output $line
        $lineTask = $process.StandardOutput.ReadLineAsync()
    }
    $remainingMs = [int][math]::Max(1, ($DurationSeconds + 180) * 1000 - $runClock.ElapsedMilliseconds)
    if (-not $process.WaitForExit($remainingMs)) { throw 'Stability process did not exit; not a pass' }
    $output = $outputBuffer.ToString()
    $errors = $errorTask.GetAwaiter().GetResult()
    if ($process.ExitCode -ne 0) { throw 'Stability adb invocation failed' }
    if ($errors.Length -gt 65536) { throw 'Stability stderr budget exceeded' }
    $report = ConvertFrom-StudioAcceptanceOutput $output
    if (-not $report.cleanup) { throw 'Stability fixture cleanup failed' }
    if ($ExpectedTargetApkSha256 -and $report.targetApkSha256 -ne $ExpectedTargetApkSha256) { throw 'Stability target APK differs' }
    if ($ExpectedTestApkSha256 -and $report.testApkSha256 -ne $ExpectedTestApkSha256) { throw 'Stability test APK differs' }
    Write-Output "PASS: Studio stability for $DurationSeconds seconds on $DeviceSerial"
} finally {
    if ($started -and -not $process.HasExited) { $process.Kill() }
    $process.Dispose()
}
