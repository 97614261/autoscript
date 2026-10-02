[CmdletBinding()]
param(
    [ValidateRange(0, 999)]
    [int]$VmIndex = 0,
    [string]$MuMuManagerPath = "",
    [string]$AdbPath = "",
    [ValidateRange(10, 600)]
    [int]$StartupTimeoutSeconds = 120,
    [switch]$Verify,
    [switch]$SkipBuild
)

$ErrorActionPreference = "Stop"
Set-StrictMode -Version Latest

function Resolve-BuildTool {
    param([string]$ExplicitPath, [string[]]$Candidates, [string]$Name)
    if ($ExplicitPath) { $Candidates = @($ExplicitPath) }
    foreach ($candidate in $Candidates) {
        if ($candidate -and (Test-Path -LiteralPath $candidate -PathType Leaf)) {
            return (Resolve-Path -LiteralPath $candidate).Path
        }
    }
    throw "$Name was not found. Pass an explicit tool path."
}

function Invoke-CheckedCli {
    param([string]$Executable, [string[]]$Arguments)
    $output = & $Executable @Arguments 2>&1
    $exitCode = $LASTEXITCODE
    $text = ($output | Out-String).Trim()
    if ($exitCode -ne 0) { throw "$Executable failed ($exitCode): $text" }
    return $text
}

function Invoke-ProjectBuild {
    param([string]$ProjectRoot, [bool]$RunChecks)
    $tasks = @()
    if ($RunChecks) {
        $tasks += @(':apps:studio-android:testDebugUnitTest', ':apps:studio-android:lintDebug')
    }
    $tasks += ':apps:studio-android:assembleDebug'
    Push-Location -LiteralPath $ProjectRoot
    try {
        & (Join-Path $ProjectRoot 'gradlew.bat') @tasks --console=plain
        if ($LASTEXITCODE -ne 0) { throw "Build failed. MuMu installation was not attempted." }
    } finally {
        Pop-Location
    }
}

function Get-MuMuInstance {
    param([string]$Manager, [int]$Index)
    $info = Invoke-CheckedCli $Manager @('info', '--vmindex', [string]$Index) | ConvertFrom-Json
    if ($info.error_code -ne 0 -or [string]$info.index -ne [string]$Index) {
        throw "MuMu instance $Index was not found."
    }
    return $info
}

function Install-StudioToMuMu {
    param([string]$Apk, [string]$Manager, [string]$Adb, [int]$Index, [int]$TimeoutSeconds)
    if (-not (Test-Path -LiteralPath $Apk -PathType Leaf)) { throw "APK was not found: $Apk" }
    $info = Get-MuMuInstance $Manager $Index
    if (-not $info.is_process_started) {
        Write-Host "Starting MuMu instance $Index..."
        $launch = Invoke-CheckedCli $Manager @('control', '--vmindex', [string]$Index, 'launch') | ConvertFrom-Json
        if ($launch.errcode -ne 0) { throw "MuMu launch failed: $($launch.errmsg)" }
    }
    $deadline = [DateTime]::UtcNow.AddSeconds($TimeoutSeconds)
    while (-not $info.is_android_started) {
        if ([DateTime]::UtcNow -ge $deadline) { throw "MuMu startup timed out. APK remains at $Apk" }
        Start-Sleep -Seconds 2
        $info = Get-MuMuInstance $Manager $Index
    }
    # Use the selected MuMu instance's actual port, never the first connected device.
    $port = [int]$info.adb_port
    if ($info.adb_host_ip -ne '127.0.0.1' -or $port -lt 1 -or $port -gt 65535) {
        throw "MuMu returned an invalid local ADB endpoint."
    }
    $serial = "127.0.0.1:$port"
    $lastError = "Android is not ready"
    while ($true) {
        try {
            Invoke-CheckedCli $Adb @('connect', $serial) | Out-Null
            $state = Invoke-CheckedCli $Adb @('-s', $serial, 'get-state')
            $booted = Invoke-CheckedCli $Adb @('-s', $serial, 'shell', 'getprop', 'sys.boot_completed')
            if ($state -eq 'device' -and $booted -eq '1') { break }
        } catch {
            $lastError = $_.Exception.Message
        }
        if ([DateTime]::UtcNow -ge $deadline) { throw "MuMu ADB startup timed out: $lastError" }
        Start-Sleep -Seconds 2
    }
    Write-Host "Installing Studio to MuMu instance $Index ($serial)..."
    $installed = Invoke-CheckedCli $Adb @('-s', $serial, 'install', '-r', '-t', $Apk)
    if ($installed -notmatch '(?m)^Success\r?$') { throw "APK installation did not succeed: $installed" }
    Write-Host "Installed successfully. Existing app data is retained."
    Write-Host "APK: $Apk"
}

function Invoke-BuildAndInstall {
    param([string]$ProjectRoot, [int]$Index, [string]$ManagerPath, [string]$AdbOverride,
        [int]$TimeoutSeconds, [bool]$RunChecks, [bool]$InstallOnly)
    if (-not $InstallOnly) { Invoke-ProjectBuild $ProjectRoot $RunChecks }
    $manager = Resolve-BuildTool $ManagerPath @(
        (Join-Path $env:ProgramFiles 'Netease\MuMu\nx_main\MuMuManager.exe'),
        (Join-Path $env:ProgramFiles 'Netease\MuMuPlayer-12.0\shell\MuMuManager.exe')
    ) 'MuMuManager'
    $sdkRoots = @($env:ANDROID_HOME, $env:ANDROID_SDK_ROOT)
    $localProperties = Join-Path $ProjectRoot 'local.properties'
    if (Test-Path -LiteralPath $localProperties) {
        $sdkLine = Get-Content -LiteralPath $localProperties | Where-Object { $_ -match '^sdk\.dir=' } | Select-Object -First 1
        if ($sdkLine) { $sdkRoots += $sdkLine.Substring(8).Replace('\\', '\').Replace('\:', ':') }
    }
    $adbCandidates = @($sdkRoots | Where-Object { $_ } | ForEach-Object { Join-Path $_ 'platform-tools\adb.exe' })
    $adbCandidates += Join-Path (Split-Path -Parent $manager) 'adb.exe'
    $adb = Resolve-BuildTool $AdbOverride $adbCandidates 'ADB'
    $apk = Join-Path $ProjectRoot 'apps\studio-android\build\outputs\apk\debug\studio-android-debug.apk'
    Install-StudioToMuMu $apk $manager $adb $Index $TimeoutSeconds
}

if ($MyInvocation.InvocationName -ne '.') {
    try {
        Invoke-BuildAndInstall (Split-Path -Parent $PSScriptRoot) $VmIndex $MuMuManagerPath $AdbPath `
            $StartupTimeoutSeconds ([bool]$Verify) ([bool]$SkipBuild)
    } catch {
        Write-Host $_.Exception.Message -ForegroundColor Red
        exit 1
    }
}
