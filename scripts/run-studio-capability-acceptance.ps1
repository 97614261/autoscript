[CmdletBinding()]
param(
    [Parameter(Mandatory)][string]$DeviceSerial,
    [switch]$ConfirmProductionImeSwitch,
    [switch]$ConfirmTargetRestart,
    [string]$AdbPath = 'C:\Work\AndroidSDK\platform-tools\adb.exe'
)
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
$capabilityAdb = $AdbPath
. (Join-Path $PSScriptRoot 'build-install-mumu.ps1')
. (Join-Path $PSScriptRoot 'device-acceptance-result.ps1')
if ($DeviceSerial -notmatch '^[A-Za-z0-9._:-]{1,128}$') { throw 'Invalid explicit device serial' }
if (-not $ConfirmProductionImeSwitch) { throw 'Explicit production IME consent required' }
$productionIme = 'com.zhigou.kaifaban/com.autoscript.runtime.service.RuntimeInputMethodService'
function Read-CapabilityIme {
    Invoke-BoundedCli $capabilityAdb @('-s', $DeviceSerial, 'shell', 'settings', 'get', 'secure', 'default_input_method') 10
}
$originalIme = Read-CapabilityIme
if ($originalIme -notmatch '^[A-Za-z0-9_.$]+/[A-Za-z0-9_.$]+$' -or $originalIme -eq $productionIme) {
    throw 'Original non-production input method could not be established; no settings changed'
}
$names = Invoke-BoundedCli $capabilityAdb @('-s', $DeviceSerial, 'shell', 'ps', '-A', '-o', 'NAME') 15
Assert-StudioAcceptanceIdleTarget -ProcessNames $names -RestartConfirmed ([bool]$ConfirmTargetRestart)
$info = [Diagnostics.ProcessStartInfo]::new()
$info.FileName = $capabilityAdb
$info.Arguments = "-s $DeviceSerial shell am instrument -w -r -e capabilityExpansionOnly true -e capabilityProductionIme true com.zhigou.kaifaban.acceptance/com.autoscript.studio.StudioAcceptanceInstrumentation"
$info.UseShellExecute = $false
$info.CreateNoWindow = $true
$info.RedirectStandardOutput = $true
$info.RedirectStandardError = $true
$process = [Diagnostics.Process]::new()
$process.StartInfo = $info
$started = $false
try {
    $started = $process.Start()
    if (-not $started) { throw 'Could not start capability instrumentation' }
    $stderr = $process.StandardError.ReadToEndAsync()
    $output = [Text.StringBuilder]::new()
    $clock = [Diagnostics.Stopwatch]::StartNew()
    $switched = $false
    $nextLine = $process.StandardOutput.ReadLineAsync()
    while ($true) {
        if ($clock.Elapsed.TotalSeconds -gt 420) { throw 'Capability instrumentation timed out; partial run is not a pass' }
        if (-not $nextLine.IsCompleted) { Start-Sleep -Milliseconds 50; continue }
        $line = $nextLine.GetAwaiter().GetResult()
        if ($null -eq $line) { break }
        if ($output.Length + $line.Length + 2 -gt 65536) { throw 'Capability output budget exceeded' }
        [void]$output.AppendLine($line)
        Write-Output $line
        if ($line.Trim() -eq 'INSTRUMENTATION_STATUS: stream=QA_IME_READY') {
            if ($switched -or (Read-CapabilityIme) -ne $originalIme) { throw 'Unexpected IME selection before owned input became ready' }
            # Select only after the fixture has focused its own public QA EditText.
            Invoke-BoundedCli $capabilityAdb @('-s', $DeviceSerial, 'shell', 'ime', 'enable', $productionIme) 15 | Write-Output
            Invoke-BoundedCli $capabilityAdb @('-s', $DeviceSerial, 'shell', 'ime', 'set', $productionIme) 15 | Write-Output
            if ((Read-CapabilityIme) -ne $productionIme) { throw 'Production IME selection not confirmed' }
            $switched = $true
        }
        $nextLine = $process.StandardOutput.ReadLineAsync()
    }
    if (-not $process.WaitForExit(10000) -or $process.ExitCode -ne 0) { throw 'Capability adb invocation failed' }
    if ($stderr.GetAwaiter().GetResult().Length -gt 65536) { throw 'Capability stderr budget exceeded' }
    $report = ConvertFrom-StudioAcceptanceOutput $output.ToString()
    if (-not $switched -or -not $report.productionImeRequested -or -not $report.cleanup) {
        throw 'Production IME execution or owned-project cleanup not confirmed'
    }
    Write-Output "PASS: public ARM expansion and production IME on $DeviceSerial"
} finally {
    try {
        if ($started -and -not $process.HasExited) {
            $process.Kill()
            if ($ConfirmTargetRestart) {
                Invoke-BoundedCli $capabilityAdb @('-s', $DeviceSerial, 'shell', 'am', 'force-stop', 'com.zhigou.kaifaban') 15 | Write-Output
            }
        }
    } finally {
        $process.Dispose()
        try {
            $currentIme = Read-CapabilityIme
            if ($currentIme -eq $productionIme) {
                Invoke-BoundedCli $capabilityAdb @('-s', $DeviceSerial, 'shell', 'ime', 'set', $originalIme) 15 | Write-Output
            } elseif ($currentIme -ne $originalIme) { throw 'Input method changed externally; original restoration not confirmed' }
        } finally {
            Invoke-BoundedCli $capabilityAdb @('-s', $DeviceSerial, 'shell', 'ime', 'disable', $productionIme) 15 | Write-Output
        }
        if ((Read-CapabilityIme) -ne $originalIme) { throw 'Original input method restoration not confirmed' }
        $enabled = Invoke-BoundedCli $capabilityAdb @('-s', $DeviceSerial, 'shell', 'settings', 'get', 'secure', 'enabled_input_methods') 10
        if (@($enabled.Split(':') | Where-Object { $_.Split(';')[0] -eq $productionIme }).Count -gt 0) { throw 'Production IME disabling not confirmed' }
        Write-Output 'Original input method restored; production IME disabled.'
    }
}
