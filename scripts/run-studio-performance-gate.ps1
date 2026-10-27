[CmdletBinding()]
param(
    [Parameter(Mandatory)][string]$DeviceSerial,
    [Parameter(Mandatory)][ValidatePattern('^[0-9a-f]{64}$')][string]$ExpectedTargetApkSha256,
    [Parameter(Mandatory)][ValidatePattern('^[0-9a-f]{64}$')][string]$ExpectedTestApkSha256,
    [Parameter(Mandatory)][string]$OutputDirectory,
    [ValidateSet('region','expanded','mixed','runtime','editor','workflow','experience','lifecycle')]
    [ValidateCount(1,8)]
    [string[]]$Suites = @('region','expanded','mixed','runtime','editor','workflow','experience','lifecycle'),
    [switch]$ConfirmTargetRestart,
    [string]$AdbPath = 'C:\Work\AndroidSDK\platform-tools\adb.exe'
)
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
if ($DeviceSerial -notmatch '^[A-Za-z0-9._:-]{1,128}$') { throw 'Invalid explicit device serial' }
if (@($Suites | Select-Object -Unique).Count -ne $Suites.Count) { throw 'Duplicate gate suite' }
$gateAdb = $AdbPath
. (Join-Path $PSScriptRoot 'build-install-mumu.ps1')
. (Join-Path $PSScriptRoot 'device-acceptance-result.ps1')
$names = Invoke-BoundedCli $gateAdb @('-s', $DeviceSerial, 'shell', 'ps', '-A', '-o', 'NAME') 15
Assert-StudioAcceptanceIdleTarget -ProcessNames $names -RestartConfirmed ([bool]$ConfirmTargetRestart)
if (-not (Test-Path -LiteralPath $OutputDirectory)) { [void](New-Item -ItemType Directory -Path $OutputDirectory) }
foreach ($suite in $Suites) {
    if (Test-Path -LiteralPath (Join-Path $OutputDirectory "$suite.log")) { throw 'Gate evidence already exists; do not overwrite it' }
}
foreach ($suite in $Suites) {
    $arguments = @('-s',$DeviceSerial,'shell','am','instrument','-w','-r','-e','expectedTargetApkSha256',$ExpectedTargetApkSha256)
    switch ($suite) {
        'region' { $arguments += @('-e','captureRegionOnly','true') }
        'expanded' { $arguments += @('-e','performanceOnly','true','-e','performanceExpanded','true') }
        'mixed' { $arguments += @('-e','performanceOnly','true','-e','performanceMixed','true') }
        'runtime' { $arguments += @('-e','runtimePerformanceOnly','true') }
        'workflow' { $arguments += @('-e','boundariesOnly','true') }
        'experience' { $arguments += @('-e','experienceOnly','true') }
        'lifecycle' { $arguments += @('-e','lifecycleOnly','true') }
    }
    $arguments += 'com.zhigou.kaifaban.acceptance/com.autoscript.studio.StudioAcceptanceInstrumentation'
    Write-Output "GATE_START:$suite"
    # A failing/timeout suite stops the gate. Never restart/retry it automatically.
    $output = Invoke-BoundedCli $gateAdb $arguments 570
    $output | Out-File -LiteralPath (Join-Path $OutputDirectory "$suite.log") -Encoding utf8
    $report = ConvertFrom-StudioAcceptanceOutput $output
    if ($report.cleanup -isnot [bool] -or -not $report.cleanup -or $report.targetApkSha256 -ne $ExpectedTargetApkSha256 -or
        $report.testApkSha256 -ne $ExpectedTestApkSha256 -or $report.cases.Count -eq 0 -or
        @($report.cases | Where-Object { $_.pass -isnot [bool] -or -not $_.pass }).Count -gt 0) { throw 'Gate identity, cleanup or cases differ' }
    Write-Output "GATE_PASS:${suite}:cases=$($report.cases.Count):cleanup=true"
}
Write-Output 'PASS: exact-candidate Studio performance regression gate; device-specific exclusions remain separate'
