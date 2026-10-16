# Shared pure preflight/parser helpers. An adb exit code of zero is not test success.
function Assert-StudioAcceptanceIdleTarget {
    param([Parameter(Mandatory)][string]$ProcessNames, [bool]$RestartConfirmed = $false)
    if ($ProcessNames.Length -gt 131072) { throw 'Device process listing exceeded budget' }
    $present = @($ProcessNames -split '\r?\n' | ForEach-Object { $_.Trim() } | Where-Object {
        $_ -eq 'com.zhigou.kaifaban' -or $_.StartsWith('com.zhigou.kaifaban:', [StringComparison]::Ordinal)
    }).Count -gt 0
    if ($present -and -not $RestartConfirmed) {
        throw 'Studio or its runtime is already running. Save/close editing and stop user runs first; restarting the instrumentation target requires explicit confirmation.'
    }
}

function ConvertFrom-StudioAcceptanceOutput {
    param([Parameter(Mandatory)][string]$OutputText)
    if ($OutputText.Length -gt 65536) { throw 'Device acceptance output exceeded budget' }
    $codes = [regex]::Matches($OutputText, '(?m)^INSTRUMENTATION_CODE:\s*(-?\d+)\s*\r?$')
    if ($codes.Count -ne 1) { throw 'Missing or ambiguous instrumentation completion' }
    $start = [regex]::Matches($OutputText, '(?m)^INSTRUMENTATION_RESULT: stream=')
    if ($start.Count -ne 1 -or $codes[0].Index -le $start[0].Index) { throw 'Missing or ambiguous acceptance report' }
    $jsonStart = $start[0].Index + $start[0].Length
    $next = [regex]::new('(?m)^INSTRUMENTATION_[A-Z_]+:').Match($OutputText, $jsonStart)
    if (-not $next.Success -or $next.Index -gt $codes[0].Index) { throw 'Missing report boundary' }
    $report = $OutputText.Substring($jsonStart, $next.Index - $jsonStart).Trim() | ConvertFrom-Json -ErrorAction Stop
    if (-not $report -or -not $report.PSObject.Properties['pass'] -or $report.pass -isnot [bool]) { throw 'Invalid acceptance pass field' }
    if ([int]$codes[0].Groups[1].Value -ne -1 -or -not $report.pass) { throw 'Device acceptance reported failure' }
    return $report
}
