$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
. (Join-Path $PSScriptRoot 'device-acceptance-result.ps1')
function Assert-Rejected([string]$Text) {
    $rejected = $false
    try { ConvertFrom-StudioAcceptanceOutput $Text | Out-Null } catch { $rejected = $true }
    if (-not $rejected) { throw 'Invalid instrumentation result was accepted' }
}
$valid = "INSTRUMENTATION_RESULT: stream={`n  `"pass`": true,`n  `"scope`": `"synthetic`"`n}`n`nINSTRUMENTATION_CODE: -1`n"
if (-not (ConvertFrom-StudioAcceptanceOutput $valid).pass) { throw 'Valid result was rejected' }
if (-not (ConvertFrom-StudioAcceptanceOutput ($valid.Replace('INSTRUMENTATION_CODE:', "INSTRUMENTATION_RESULT: report=public.json`nINSTRUMENTATION_CODE:"))).pass) { throw 'Additional result fields were rejected' }
Assert-Rejected ($valid.Replace('true', 'false'))
Assert-Rejected ($valid.Replace('CODE: -1', 'CODE: 0'))
Assert-Rejected ($valid.Replace('CODE: -1', 'CODE: 1'))
Assert-Rejected ($valid.Replace('true', '"true"'))
Assert-Rejected ($valid.Replace('"pass"', '"other"'))
Assert-Rejected ($valid.Replace('INSTRUMENTATION_CODE: -1', 'INSTRUMENTATION_FAILED: error'))
Assert-Rejected ($valid + "INSTRUMENTATION_CODE: -1`n")
Assert-Rejected ($valid.Replace('stream={', 'stream={]'))
Assert-Rejected ($valid + (' ' * 65536))
Write-Output 'PASS: structured pass, completion code, missing/duplicate/malformed/string/oversized rejection'
Assert-StudioAcceptanceIdleTarget "NAME`nsystem_server`ncom.zhigou.kaifaban.acceptance`ncom.zhigou.kaifaban.other"
foreach ($processName in @('com.zhigou.kaifaban', 'com.zhigou.kaifaban:runner')) {
    $blocked = $false
    try { Assert-StudioAcceptanceIdleTarget "NAME`n$processName" } catch { $blocked = $true }
    if (-not $blocked) { throw 'Existing Studio process was not protected' }
    Assert-StudioAcceptanceIdleTarget "NAME`n$processName" -RestartConfirmed $true
}
Write-Output 'PASS: exact Studio/main and runtime preflight protection; unrelated packages unchanged'
