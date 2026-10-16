$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
. (Join-Path $PSScriptRoot 'build-install-mumu.ps1')

$cli = (Get-Process -Id $PID).Path
$fixture = Join-Path $PSScriptRoot 'test-fixtures\bounded-cli.ps1'
$echoed = Invoke-BoundedCli $cli @('-NoProfile', '-File', $fixture, 'echo', 'spaces and "quotes"\') 10
if ($echoed -ne 'spaces and "quotes"\') { throw 'CLI argument quoting changed data' }
$pipes = Invoke-BoundedCli $cli @('-NoProfile', '-File', $fixture, 'pipes') 10
if ($pipes -notmatch 'output' -or $pipes -notmatch 'error') { throw 'Both pipes must drain without deadlock' }
$failed = $false
try { Invoke-BoundedCli $cli @('-NoProfile', '-File', $fixture, 'failure') 10 } catch { $failed = $_.Exception.Message -match 'failed \(7\)' }
if (-not $failed) { throw 'Nonzero CLI exit must fail' }
$timer = [Diagnostics.Stopwatch]::StartNew()
$timedOut = $false
try { Invoke-BoundedCli $cli @('-NoProfile', '-File', $fixture, 'timeout') 1 } catch { $timedOut = $_.Exception.Message -match 'timed out' }
if (-not $timedOut -or $timer.Elapsed.TotalSeconds -gt 6) { throw 'Hung CLI did not stop within its own timeout' }
Write-Host 'PASS: real CLI quoting, asynchronous pipes, failure and owned-process timeout'

$script:Scenario = 'success'
$script:Calls = [System.Collections.Generic.List[object]]::new()
function Invoke-CheckedCli {
    param([string]$Executable, [string[]]$Arguments)
    $script:Calls.Add([pscustomobject]@{ Executable = $Executable; Arguments = $Arguments })
    if ($Arguments[0] -eq 'info') {
        $hostIp = if ($script:Scenario -eq 'wrong-host') { '192.168.1.8' } else { '127.0.0.1' }
        return (@{ error_code = 0; index = '3'; is_process_started = $true; is_android_started = $true;
            adb_host_ip = $hostIp; adb_port = 16448 } | ConvertTo-Json -Compress)
    }
    if ($Arguments[0] -eq 'connect') { return 'connected' }
    if ($Arguments[2] -eq 'get-state') { return 'device' }
    if ($Arguments[2] -eq 'shell') { return '1' }
    if ($Arguments[2] -eq 'install') {
        if ($script:Scenario -eq 'install-failure') { return 'Failure [INSTALL_FAILED_TEST]' }
        return 'Success'
    }
    throw 'Unexpected command in test'
}

function Assert-True {
    param([bool]$Condition, [string]$Message)
    if (-not $Condition) { throw $Message }
}

function Assert-Fails {
    param([scriptblock]$Action, [string]$Expected)
    $message = ''
    try { & $Action } catch { $message = $_.Exception.Message }
    Assert-True ($message.Contains($Expected)) "Expected failure: $Expected; actual: $message"
}

$candidates = @(Get-MuMuManagerCandidates @(
    [pscustomobject]@{ DisplayName='MuMu'; InstallLocation=''; DisplayIcon='"D:\soft\MuMuPlayer\nx_main\MuMuNxMain.ico",0' },
    [pscustomobject]@{ DisplayName='MuMuPlayer'; InstallLocation='E:\MuMu Player'; DisplayIcon='' },
    [pscustomobject]@{ DisplayName='Other'; InstallLocation='F:\other'; DisplayIcon='' }
))
Assert-True ($candidates -contains 'D:\soft\MuMuPlayer\nx_main\MuMuManager.exe') 'Custom-drive icon path was not discovered'
Assert-True ($candidates -contains 'E:\MuMu Player\nx_main\MuMuManager.exe') 'InstallLocation with spaces was not preserved'
Assert-True (@($candidates | Where-Object { $_ -like 'F:*' }).Count -eq 0) 'Unrelated installation must not be used'
Assert-Fails { Resolve-BuildTool 'Z:\not-present\MuMuManager.exe' @($PSCommandPath) 'MuMuManager' } 'was not found'
Write-Host 'PASS: bounded custom-drive registry discovery and explicit-path precedence'

Install-StudioToMuMu $PSCommandPath 'manager' 'adb' 3 10
$install = @($script:Calls | Where-Object { $_.Arguments -contains 'install' })
Assert-True ($install.Count -eq 1) 'Expected exactly one installation'
Assert-True (($install[0].Arguments -join '|') -eq "-s|127.0.0.1:16448|install|--no-streaming|-r|-t|$PSCommandPath") 'Wrong target or missing data-preserving install flags'
Write-Host 'PASS: selected instance, actual port and update installation'

$script:Scenario = 'wrong-host'
$script:Calls.Clear()
Assert-Fails { Install-StudioToMuMu $PSCommandPath 'manager' 'adb' 3 10 } 'invalid local ADB endpoint'
Assert-True (@($script:Calls | Where-Object { $_.Executable -eq 'adb' }).Count -eq 0) 'Invalid endpoint must not receive ADB commands'
Write-Host 'PASS: invalid target rejected before ADB use'

$script:Scenario = 'install-failure'
Assert-Fails { Install-StudioToMuMu $PSCommandPath 'manager' 'adb' 3 10 } 'installation did not succeed'
Write-Host 'PASS: failed installation cannot report success'

function Invoke-ProjectBuild { throw 'Build failed' }
$script:Calls.Clear()
Assert-Fails { Invoke-BuildAndInstall '.' 3 '' '' 10 $false $false } 'Build failed'
Assert-True ($script:Calls.Count -eq 0) 'Failed build must not start or install to MuMu'
Write-Host 'PASS: build failure prevents emulator installation'
