$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
. (Join-Path $PSScriptRoot 'runtime-memory-metrics.ps1')
$modern = @'
** MEMINFO in pid 42 [com.zhigou.kaifaban:runner] **
  Native Heap    63584    63552        0        0    64428    65292    43822    16447
        TOTAL   149000   130180     6180        0   224840    68601    45477    18101
           Java Heap:    24084                          39004
            Graphics:        0                              0
                Code:    45228                         113920
               Views:        4         ViewRootImpl:        2
         AppContexts:        5           Activities:        1
       Local Binders:        2        Proxy Binders:       26
'@
$current = ConvertFrom-StudioMemoryReport $modern 42
if (-not $current.available -or $current.totalPssKiB -ne 149000 -or $current.nativePssKiB -ne 63584 -or
    $current.nativeHeapSizeKiB -ne 65292 -or $current.nativeHeapAllocatedKiB -ne 43822 -or $current.nativeHeapFreeKiB -ne 16447 -or
    $current.javaHeapPssKiB -ne 24084 -or $current.graphicsPssKiB -ne 0 -or $current.codePssKiB -ne 45228 -or
    $current.views -ne 4 -or $current.viewRoots -ne 2 -or $current.activities -ne 1 -or
    $current.localBinders -ne 2 -or $current.proxyBinders -ne 26) { throw 'Modern accounting differs' }
$old = "** MEMINFO in pid 42 [owned] **`n Native Heap 12 11 0 0 90 60 30`n TOTAL 100 80 0 0 99 66 33`n"
$legacy = ConvertFrom-StudioMemoryReport $old 42
if (-not $legacy.available -or $legacy.nativeHeapAllocatedKiB -ne 60 -or $legacy.nativePssKiB -ne 12 -or $null -ne $legacy.views) { throw 'Legacy accounting differs' }
$missing = ConvertFrom-StudioMemoryReport "** MEMINFO in pid 42 [owned] **`n TOTAL PSS: 19 TOTAL RSS: 20`n" 42
if (-not $missing.available -or $missing.totalPssKiB -ne 19 -or $null -ne $missing.nativeHeapAllocatedKiB) { throw 'Missing fields became zero' }
foreach ($invalid in @('', 'No process found', ($modern.Replace('pid 42', 'pid 43')), ($modern + "`n** MEMINFO in pid 42 [duplicate] **"))) {
    if ((ConvertFrom-StudioMemoryReport -Text $(if ($invalid) { $invalid } else { ' ' }) -ExpectedPid 42).available) { throw 'Invalid identity accepted' }
}
$rejected = $false
try { ConvertFrom-StudioMemoryReport ('x' * 65537) 42 | Out-Null } catch { $rejected = $true }
if (-not $rejected) { throw 'Accounting budget ignored' }
$rows = @(ConvertFrom-StudioProcessRows "USER PID PPID NAME`nroot 7 1 libautoscript_root_daemon.so`nu0_a1 9 2 com.zhigou.kaifaban:runner`nroot 8 7 /system/bin/app_process64`nmalformed`n")
if ($rows.Count -ne 3 -or $rows[0].Id -ne 7 -or $rows[2].Parent -ne 7) { throw 'Process columns differ' }
$native = "Applications Memory Usage (in Kilobytes):`n Native Heap 12 11 0 0 90 0 0 0`n TOTAL 100 80 0 0 99 0 0 0`n"
if ((ConvertFrom-StudioMemoryReport $native 42).available) { throw 'Unverified native identity accepted' }
$verified = ConvertFrom-StudioMemoryReport $native 42 -VerifiedNativeProcess
if (-not $verified.available -or $verified.nativePssKiB -ne 12 -or $null -ne $verified.nativeHeapAllocatedKiB -or $verified.nativeAllocatorAccountingAvailable) { throw 'Native allocator absence became zero' }
if ((ConvertFrom-StudioMemoryReport $modern 43 -VerifiedNativeProcess).available) { throw 'Native flag bypassed explicit PID mismatch' }
$oldRows = @(ConvertFrom-StudioProcessRows "USER PID PPID VSIZE RSS WCHAN PC NAME`nroot 7 1 123 45 ffffffff 0000 S /owned/root`n")
if ($oldRows.Count -ne 1 -or $oldRows[0].Id -ne 7) { throw 'Legacy process columns differ' }
$stat = '42 (name with ) parentheses) S ' + ((1..18) -join ' ') + ' 98765 9 10'
if ((ConvertFrom-StudioProcessStartTicks $stat 42) -ne 98765 -or $null -ne (ConvertFrom-StudioProcessStartTicks $stat 43)) { throw 'Process start identity differs' }
'PASS: modern/legacy/native accounting, unavailable allocator, exact identity, budgets and process columns'
