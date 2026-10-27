# Pure, bounded Android process accounting. Never expose the raw report or process arguments.
function ConvertFrom-StudioMemoryReport {
    param([Parameter(Mandatory)][string]$Text, [Parameter(Mandatory)][int]$ExpectedPid,
        [switch]$VerifiedNativeProcess)
    if ($Text.Length -gt 65536) { throw 'Memory report budget exceeded' }
    $identity = [regex]::Matches($Text, '(?m)^\*\* MEMINFO in pid (\d+) \[')
    $nativeReport = $VerifiedNativeProcess -and $identity.Count -eq 0 -and
        $Text -match '^Applications Memory Usage \(in Kilobytes\):'
    if (-not $nativeReport -and ($identity.Count -ne 1 -or [long]$identity[0].Groups[1].Value -ne $ExpectedPid)) {
        return [pscustomobject]@{ available = $false; code = 'MEMORY_IDENTITY_UNAVAILABLE' }
    }
    function metric([string]$pattern) {
        $match = [regex]::Match($Text, $pattern, [Text.RegularExpressions.RegexOptions]::Multiline)
        if ($match.Success) { return [long]$match.Groups[1].Value }
        return $null
    }
    $pss = metric '^\s*TOTAL\s+(\d+)\s+'
    if ($null -eq $pss) { $pss = metric '^\s*TOTAL PSS:\s+(\d+)\s+' }
    if ($null -eq $pss -or $pss -le 0) {
        return [pscustomobject]@{ available = $false; code = 'MEMORY_ACCOUNTING_UNAVAILABLE' }
    }
    $native = [regex]::Match($Text, '(?m)^\s*Native Heap\s+([\d \t]+)\r?$')
    $columns = @(if ($native.Success) { $native.Groups[1].Value.Trim() -split '\s+' | ForEach-Object { [long]$_ } })
    # Native processes often expose PSS but not allocator accounting through dumpsys.
    $hasHeap = $columns.Count -ge 7 -and ($columns[-3] -gt 0 -or $columns[-2] -gt 0 -or $columns[-1] -gt 0)
    [pscustomobject][ordered]@{
        available = $true; totalPssKiB = $pss
        nativePssKiB = $(if ($columns.Count -ge 7) { $columns[0] } else { $null })
        nativeHeapSizeKiB = $(if ($hasHeap) { $columns[-3] } else { $null })
        nativeHeapAllocatedKiB = $(if ($hasHeap) { $columns[-2] } else { $null })
        nativeHeapFreeKiB = $(if ($hasHeap) { $columns[-1] } else { $null })
        javaHeapPssKiB = metric '^\s*Java Heap:\s+(\d+)'
        graphicsPssKiB = metric '^\s*Graphics:\s+(\d+)'
        codePssKiB = metric '^\s*Code:\s+(\d+)'
        views = metric '^\s*Views:\s+(\d+)'
        viewRoots = metric 'ViewRootImpl:\s+(\d+)'
        activities = metric 'Activities:\s+(\d+)'
        localBinders = metric '^\s*Local Binders:\s+(\d+)'
        proxyBinders = metric 'Proxy Binders:\s+(\d+)'
        platformAccountingOnly = $true
        nativeAllocatorAccountingAvailable = [bool]$hasHeap
    }
}

function ConvertFrom-StudioProcessRows {
    param([Parameter(Mandatory)][string]$Text)
    if ($Text.Length -gt 131072) { throw 'Process listing budget exceeded' }
    $legacy = $Text -match '(?m)^\s*USER\s+PID\s+PPID\s+VSIZE\s+RSS\s+WCHAN\s+PC\s+NAME\s*$'
    foreach ($line in $Text -split '\r?\n') {
        if ($line -match '^\s*(\S+)\s+(\d+)\s+(\d+)\s+(\S+)\s*$') {
            [pscustomobject]@{ User = $Matches[1]; Id = [int]$Matches[2]; Parent = [int]$Matches[3]; Name = $Matches[4] }
        } elseif ($legacy -and $line -match '^\s*(\S+)\s+(\d+)\s+(\d+)\s+\d+\s+\d+\s+\S+\s+\S+\s+\S\s+(\S+)\s*$') {
            [pscustomobject]@{ User = $Matches[1]; Id = [int]$Matches[2]; Parent = [int]$Matches[3]; Name = $Matches[4] }
        }
    }
}

function ConvertFrom-StudioProcessStartTicks {
    param([Parameter(Mandatory)][string]$Text, [Parameter(Mandatory)][int]$ExpectedPid)
    if ($Text.Length -gt 4096) { throw 'Process stat budget exceeded' }
    if ($Text -notmatch '^([0-9]+) \([^\r\n]*\) ([^\r\n]+)\s*$' -or [int]$Matches[1] -ne $ExpectedPid) { return $null }
    $fields = $Matches[2].Trim() -split '\s+'
    if ($fields.Count -lt 20 -or $fields[19] -notmatch '^[0-9]+$') { return $null }
    return [long]$fields[19]
}
