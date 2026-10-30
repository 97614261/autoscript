[CmdletBinding()]
param(
    [Parameter(Mandatory)][string]$DeviceSerial,
    [Parameter(Mandatory)][ValidateSet('variables', 'touch', 'conditions', 'loops', 'flow', 'output', 'ai', 'aiAssistant', 'libraryUI', 'libraryPure', 'libraryFiles', 'libraryDevice', 'libraryHost', 'libraryVision', 'libraryOcr', 'libraryInput', 'libraryRecording', 'imageLibrary', 'imageSelection', 'imageFilter', 'imageEnhancement', 'glyphMaker', 'glyphPalette', 'templateCapture', 'pluginFiles', 'promptReuse', 'promptLayers', 'pureUtility', 'capabilityExpansion')][string]$Stage,
    [string]$AdbPath = 'C:/Work/AndroidSDK/platform-tools/adb.exe',
    [switch]$ConfirmTargetRestart
)
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
$usageAdbPath = $AdbPath
. (Join-Path $PSScriptRoot 'build-install-mumu.ps1')
. (Join-Path $PSScriptRoot 'device-acceptance-result.ps1')
if ($DeviceSerial -notmatch '^[A-Za-z0-9._:-]{1,128}$') { throw 'Invalid device serial' }
$names = Invoke-BoundedCli $usageAdbPath @('-s', $DeviceSerial, 'shell', 'ps', '-A', '-o', 'NAME') 12
Assert-StudioAcceptanceIdleTarget -ProcessNames $names -RestartConfirmed ([bool]$ConfirmTargetRestart)
$flag = switch ($Stage) {
    'variables' { 'variableUsageOnly' }
    'touch' { 'touchUsageOnly' }
    'conditions' { 'controlUsageStage' }
    'loops' { 'controlUsageStage' }
    'flow' { 'controlUsageStage' }
    'output' { 'controlUsageStage' }
    'ai' { 'controlUsageStage' }
    'aiAssistant' { 'controlUsageStage' }
    'libraryUI' { 'controlUsageStage' }
    'libraryPure' { 'controlUsageStage' }
    'libraryFiles' { 'controlUsageStage' }
    'libraryDevice' { 'controlUsageStage' }
    'libraryHost' { 'controlUsageStage' }
    'libraryVision' { 'controlUsageStage' }
    'libraryOcr' { 'controlUsageStage' }
    'libraryInput' { 'controlUsageStage' }
    'libraryRecording' { 'controlUsageStage' }
    default { $Stage + 'Only' }
}
$value = if ($flag -eq 'controlUsageStage') { $Stage } else { 'true' }
$arguments = @('-s', $DeviceSerial, 'shell', 'am', 'instrument', '-w', '-r', '-e', $flag, $value,
    'com.zhigou.kaifaban.acceptance/com.autoscript.studio.StudioAcceptanceInstrumentation')
$directory = Join-Path (Split-Path $PSScriptRoot -Parent) 'build'
[IO.Directory]::CreateDirectory($directory) | Out-Null
$log = Join-Path $directory ("function-usage-$Stage-" + (Get-Date -Format 'yyyyMMddTHHmmss') + '.log')
$info = [Diagnostics.ProcessStartInfo]::new()
$info.FileName = $usageAdbPath
$info.UseShellExecute = $false
$info.CreateNoWindow = $true
$info.RedirectStandardOutput = $true
$info.RedirectStandardError = $true
foreach ($argument in $arguments) { $info.ArgumentList.Add($argument) }
$process = [Diagnostics.Process]::new()
$process.StartInfo = $info
$writer = [IO.StreamWriter]::new($log, $false, [Text.UTF8Encoding]::new($false))
$capture = [Text.StringBuilder]::new()
$clock = [Diagnostics.Stopwatch]::StartNew()
try {
    if (-not $process.Start()) { throw 'Could not start test client' }
    $errors = $process.StandardError.ReadToEndAsync()
    $line = $process.StandardOutput.ReadLineAsync()
    # Persist progress before a blocked Android service can exhaust the deadline.
    while ($true) {
        if ($clock.Elapsed.TotalSeconds -gt 600) { throw 'QA_TIMEOUT: incomplete test; not a pass' }
        if (-not $line.Wait(100)) { continue }
        if ($null -eq $line.Result) { break }
        if ($capture.Length + $line.Result.Length -gt 262144) { throw 'QA_OUTPUT_LIMIT' }
        [void]$capture.AppendLine($line.Result)
        $writer.WriteLine($line.Result)
        $writer.Flush()
        Write-Output $line.Result
        $line = $process.StandardOutput.ReadLineAsync()
    }
    if (-not $process.WaitForExit(5000) -or -not $errors.Wait(1000)) { throw 'QA_CLIENT_DID_NOT_EXIT' }
    if ($errors.Result) { $writer.WriteLine($errors.Result); $writer.Flush() }
    if ($process.ExitCode -ne 0) { throw 'QA_CLIENT_FAILED: see saved log' }
    $completion = $capture.ToString() -replace '(?m)^INSTRUMENTATION_STATUS[^\r\n]*\r?\n', ''
    $report = ConvertFrom-StudioAcceptanceOutput $completion
    if (-not $report.cleanup) { throw 'QA_CLEANUP_INCOMPLETE' }
    Write-Output "PASS: $Stage ($($report.cases.Count) cases); log=$log"
} finally {
    if ($process.Id -and -not $process.HasExited) { $process.Kill(); [void]$process.WaitForExit(1000) }
    $writer.Dispose()
    $process.Dispose()
}
