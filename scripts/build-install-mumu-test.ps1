$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
. (Join-Path $PSScriptRoot 'build-install-mumu.ps1')

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

Install-StudioToMuMu $PSCommandPath 'manager' 'adb' 3 10
$install = @($script:Calls | Where-Object { $_.Arguments -contains 'install' })
Assert-True ($install.Count -eq 1) 'Expected exactly one installation'
Assert-True (($install[0].Arguments -join '|') -eq "-s|127.0.0.1:16448|install|-r|-t|$PSCommandPath") 'Wrong target or missing data-preserving install flags'
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
