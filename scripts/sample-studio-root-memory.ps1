[CmdletBinding()]
param(
    [Parameter(Mandatory)][string]$DeviceSerial,
    [ValidateRange(1, 65)][int]$Samples = 28,
    [ValidateRange(10, 60)][int]$IntervalSeconds = 60,
    [switch]$IncludeStudio,
    [string]$AdbPath = 'C:\Work\AndroidSDK\platform-tools\adb.exe'
)
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
if ($DeviceSerial -notmatch '^[A-Za-z0-9._:-]{1,128}$') { throw 'Invalid explicit device serial' }
$taskAdb = $AdbPath
. (Join-Path $PSScriptRoot 'build-install-mumu.ps1')
. (Join-Path $PSScriptRoot 'runtime-memory-metrics.ps1')
$taskPackage = 'com.zhigou.kaifaban'
$taskInstalled = Invoke-BoundedCli $taskAdb @('-s', $DeviceSerial, 'shell', 'pm', 'path', $taskPackage) 15
if ($taskInstalled -notmatch '^package:(/data/app/[^\r\n]+/base[.]apk)$') { throw 'Installed package identity unavailable' }
$taskBase = $Matches[1].Substring(0, $Matches[1].Length - '/base.apk'.Length)
$taskExecutables = @('arm64', 'x86_64', 'arm', 'x86') | ForEach-Object { "$taskBase/lib/$_/libautoscript_root_daemon.so" }
$taskClock = [Diagnostics.Stopwatch]::StartNew()
for ($sampleIndex = 0; $sampleIndex -lt $Samples; $sampleIndex++) {
    try { $listing = Invoke-BoundedCli $taskAdb @('-s', $DeviceSerial, 'shell', 'ps', '-A', '-o', 'USER,PID,PPID,NAME') 15 }
    catch { $listing = Invoke-BoundedCli $taskAdb @('-s', $DeviceSerial, 'shell', 'ps') 15 }
    $rows = @(ConvertFrom-StudioProcessRows $listing)
    if ($rows.Count -eq 0) {
        $listing = Invoke-BoundedCli $taskAdb @('-s', $DeviceSerial, 'shell', 'ps') 15
        $rows = @(ConvertFrom-StudioProcessRows $listing)
    }
    $daemons = @($rows | Where-Object { $_.User -eq 'root' -and
        ($_.Name -eq 'libautoscript_root_daemon.so' -or $_.Name -in $taskExecutables) } | Where-Object {
        try {
            $executable = Invoke-BoundedCli $taskAdb @('-s', $DeviceSerial, 'shell', 'su', '0', 'readlink', "/proc/$($_.Id)/exe") 15
            $executable -in $taskExecutables
        } catch { $false }
    })
    $owned = @()
    if ($IncludeStudio) {
        foreach ($name in @($taskPackage, "${taskPackage}:runner")) {
            $matchesForName = @($rows | Where-Object { $_.Name -eq $name -and $_.User -match '^u\d+_a\d+$' })
            $role = if ($name -eq $taskPackage) { 'studio' } else { 'runner' }
            if ($matchesForName.Count -gt 1) { throw 'Studio/Runner process is not unique' }
            if ($matchesForName.Count -eq 0) {
                Write-Output ('ROOT_MEMORY_SAMPLE:' + (@{ elapsedMs=$taskClock.ElapsedMilliseconds; role=$role; available=$false; code='OWNED_PROCESS_EXITED' } | ConvertTo-Json -Compress))
            } else { $owned += [pscustomobject]@{ Id=$matchesForName[0].Id; Role=$role } }
        }
    }
    if ($daemons.Count -ne 1) {
        Write-Output ('ROOT_MEMORY_SAMPLE:' + (@{ elapsedMs = $taskClock.ElapsedMilliseconds; available = $false; code = 'OWNED_ROOT_NOT_UNIQUE' } | ConvertTo-Json -Compress))
    } else {
        $daemon = $daemons[0]
        $bridges = @($rows | Where-Object { $_.User -eq 'root' -and $_.Parent -eq $daemon.Id })
        if ($bridges.Count -gt 3) { throw 'Unexpected owned Root process count' }
        $owned += [pscustomobject]@{ Id=$daemon.Id; Role='root-daemon' }
        $owned += @($bridges | ForEach-Object { [pscustomobject]@{ Id=$_.Id; Role='root-bridge' } })
    }
    foreach ($processRow in $owned) {
        $record = [ordered]@{ elapsedMs=$taskClock.ElapsedMilliseconds; pid=$processRow.Id; role=$processRow.Role }
        try {
        $startBefore = $null
        $executableBefore = $null
        $nativeRole = $processRow.Role -in @('root-daemon', 'root-bridge')
        if ($nativeRole) {
            $stat = Invoke-BoundedCli $taskAdb @('-s', $DeviceSerial, 'shell', 'su', '0', 'cat', "/proc/$($processRow.Id)/stat") 15
            $startBefore = ConvertFrom-StudioProcessStartTicks $stat $processRow.Id
            $executableBefore = Invoke-BoundedCli $taskAdb @('-s', $DeviceSerial, 'shell', 'su', '0', 'readlink', "/proc/$($processRow.Id)/exe") 15
        }
        $memory = Invoke-BoundedCli $taskAdb @('-s', $DeviceSerial, 'shell', 'dumpsys', 'meminfo', "$($processRow.Id)") 15
        $verifiedNative = $false
        if ($nativeRole) {
            $statAfter = Invoke-BoundedCli $taskAdb @('-s', $DeviceSerial, 'shell', 'su', '0', 'cat', "/proc/$($processRow.Id)/stat") 15
            $startAfter = ConvertFrom-StudioProcessStartTicks $statAfter $processRow.Id
            $executableAfter = Invoke-BoundedCli $taskAdb @('-s', $DeviceSerial, 'shell', 'su', '0', 'readlink', "/proc/$($processRow.Id)/exe") 15
            $expectedExecutable = if ($processRow.Role -eq 'root-daemon') { $taskExecutables } else { @('/system/bin/app_process64', '/system/bin/app_process32', '/system/bin/app_process') }
            $verifiedNative = $null -ne $startBefore -and $startBefore -eq $startAfter -and
                $executableBefore -eq $executableAfter -and $executableBefore -in $expectedExecutable
            $record['startTicks'] = $startBefore
        }
        $accounting = if ($nativeRole -and -not $verifiedNative) {
            [pscustomobject]@{ available=$false; code='MEMORY_IDENTITY_UNAVAILABLE' }
        } else { ConvertFrom-StudioMemoryReport $memory $processRow.Id -VerifiedNativeProcess:$verifiedNative }
        foreach ($property in $accounting.PSObject.Properties) { $record[$property.Name]=$property.Value }
        } catch {
            # Process exit is an unavailable sample, never a zero or a raw diagnostic dump.
            $record['available'] = $false
            $record['code'] = 'PROCESS_ACCOUNTING_UNAVAILABLE'
        }
        Write-Output ('ROOT_MEMORY_SAMPLE:' + ($record | ConvertTo-Json -Compress))
    }
    if ($sampleIndex + 1 -lt $Samples) { Start-Sleep -Seconds $IntervalSeconds }
}
