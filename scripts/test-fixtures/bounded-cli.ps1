param([string]$Mode, [string]$Value)
if ($Mode -eq 'timeout') { Start-Sleep -Seconds 60; exit 0 }
if ($Mode -eq 'failure') { [Console]::Error.WriteLine('synthetic failure'); exit 7 }
if ($Mode -eq 'pipes') {
    for ($i = 0; $i -lt 200; $i++) { [Console]::Out.WriteLine('output'); [Console]::Error.WriteLine('error') }
} else { [Console]::Out.WriteLine($Value) }
