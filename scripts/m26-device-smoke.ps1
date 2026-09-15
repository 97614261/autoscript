[CmdletBinding()]
param(
    [Parameter(Mandatory)]
    [string]$ArtifactRoot,
    [string]$AdbPath = "C:\Work\AndroidSDK\platform-tools\adb.exe",
    [string]$DeviceSerial = "127.0.0.1:16384",
    [switch]$SkipInstall
)

$ErrorActionPreference = "Stop"
Set-StrictMode -Version Latest

$artifact = (Resolve-Path -LiteralPath $ArtifactRoot).Path
$report = [System.Collections.Generic.List[object]]::new()
$remoteXml = "/sdcard/m26-window.xml"

function Invoke-Adb {
    param([Parameter(ValueFromRemainingArguments)][string[]]$Arguments)
    foreach ($attempt in 1..3) {
        $output = & $AdbPath -s $DeviceSerial @Arguments 2>&1
        if ($LASTEXITCODE -eq 0) { return $output }
        if ($attempt -lt 3) {
            & $AdbPath start-server 2>&1 | Out-Null
            & $AdbPath connect $DeviceSerial 2>&1 | Out-Null
            Start-Sleep -Milliseconds 500
        }
    }
    throw "adb失败：$($Arguments -join ' ')；$($output | Out-String)"
}

function Invoke-SmokeStep {
    param([string]$Name, [scriptblock]$Action)
    $watch = [Diagnostics.Stopwatch]::StartNew()
    Write-Host "`n[M26 device] $Name" -ForegroundColor Cyan
    try {
        & $Action
        $watch.Stop()
        $report.Add([pscustomobject]@{ Step = $Name; Result = "PASS"; Seconds = [math]::Round($watch.Elapsed.TotalSeconds, 1) })
    } catch {
        $watch.Stop()
        $report.Add([pscustomobject]@{ Step = $Name; Result = "FAIL"; Seconds = [math]::Round($watch.Elapsed.TotalSeconds, 1); Error = $_.Exception.Message })
        $report | Format-Table -AutoSize
        throw
    }
}

function Get-UiXml {
    foreach ($attempt in 1..4) {
        $dump = Invoke-Adb shell uiautomator dump $remoteXml | Out-String
        if ($dump -match 'dumped to') {
            $xml = Invoke-Adb exec-out cat $remoteXml | Out-String
            if ($xml -match '<hierarchy') { return $xml }
        }
        Start-Sleep -Milliseconds 500
    }
    throw "UIAutomator连续返回空窗口"
}

function Get-TextBounds {
    param([string]$Xml, [string]$Text)
    $pattern = '<node[^>]*text="' + [regex]::Escape($Text) + '"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"'
    $match = [regex]::Match($Xml, $pattern)
    if (-not $match.Success) { return $null }
    return @(
        [int]$match.Groups[1].Value,
        [int]$match.Groups[2].Value,
        [int]$match.Groups[3].Value,
        [int]$match.Groups[4].Value
    )
}

function Tap-Text {
    param([string]$Text, [string]$Xml = "")
    if ([string]::IsNullOrEmpty($Xml)) { $Xml = Get-UiXml }
    $bounds = Get-TextBounds $Xml $Text
    if ($null -eq $bounds) { throw "界面中找不到：$Text" }
    $x = [int](($bounds[0] + $bounds[2]) / 2)
    $y = [int](($bounds[1] + $bounds[3]) / 2)
    Invoke-Adb shell input tap $x $y | Out-Null
}

function Wait-Text {
    param([string]$Text, [int]$TimeoutSeconds = 12)
    $deadline = [DateTime]::UtcNow.AddSeconds($TimeoutSeconds)
    do {
        $xml = Get-UiXml
        if ($null -ne (Get-TextBounds $xml $Text)) { return $xml }
        Start-Sleep -Milliseconds 300
    } while ([DateTime]::UtcNow -lt $deadline)
    throw "等待界面文字超时：$Text"
}

function Approve-RootIfRequested {
    $deadline = [DateTime]::UtcNow.AddSeconds(6)
    do {
        $xml = Get-UiXml
        if ($xml -match '正在请求超级用户访问权限') {
            $remember = Get-TextBounds $xml "永久记住选择"
            if ($null -ne $remember) {
                Tap-Text "永久记住选择" $xml
                Start-Sleep -Milliseconds 250
                $xml = Get-UiXml
            }
            Tap-Text "允许" $xml
            Start-Sleep -Seconds 1
            return
        }
        if ($null -ne (Get-TextBounds $xml "已认证")) { return }
        Start-Sleep -Milliseconds 300
    } while ([DateTime]::UtcNow -lt $deadline)
}

function Assert-Contains {
    param([string]$Value, [string]$Expected, [string]$Label)
    if (-not $Value.Contains($Expected)) { throw "$Label 缺少：$Expected" }
}

Invoke-SmokeStep "MuMu identity" {
    if (-not (Test-Path -LiteralPath $AdbPath -PathType Leaf)) { throw "ADB不存在：$AdbPath" }
    & $AdbPath connect $DeviceSerial | Out-Null
    $state = Invoke-Adb get-state
    if (($state | Out-String).Trim() -ne "device") { throw "MuMu未连接" }
    $abi = (Invoke-Adb shell getprop ro.product.cpu.abi | Out-String).Trim()
    $sdk = (Invoke-Adb shell getprop ro.build.version.sdk | Out-String).Trim()
    $size = (Invoke-Adb shell wm size | Out-String)
    if ($abi -ne "x86_64" -or $sdk -ne "32" -or $size -notmatch "720x1280") {
        throw "设备不符合预期：ABI=$abi SDK=$sdk size=$size"
    }
}

$studioApk = Join-Path $artifact "studio-debug.apk"
$helloApk = Join-Path $artifact "hello-runner-debug.apk"
$visualApk = Join-Path $artifact "visual-runner-debug.apk"
$visualErrorApk = Join-Path $artifact "visual-error-runner-debug.apk"
if (-not $SkipInstall) {
    Invoke-SmokeStep "Install current APKs" {
        foreach ($apk in @($studioApk, $helloApk, $visualApk, $visualErrorApk)) {
            if (-not (Test-Path -LiteralPath $apk -PathType Leaf)) { throw "APK不存在：$apk" }
            $install = Invoke-Adb install -r -t $apk | Out-String
            Assert-Contains $install "Success" "安装结果"
        }
    }
}

Invoke-SmokeStep "Studio settings scroll and capabilities" {
    Invoke-Adb shell am force-stop com.autoscript.studio | Out-Null
    Invoke-Adb shell am start -n com.autoscript.studio/.MainActivity | Out-Null
    Start-Sleep -Seconds 1
    Approve-RootIfRequested
    $xml = Wait-Text "VisualSmoke"
    if ($null -eq (Get-TextBounds $xml "资源设置")) {
        Tap-Text "VisualSmoke" $xml
        Start-Sleep -Milliseconds 500
        $xml = Get-UiXml
    }
    Tap-Text "资源设置" $xml
    Wait-Text "项目设置" | Out-Null
    1..3 | ForEach-Object {
        Invoke-Adb shell input swipe 350 1050 350 500 350 | Out-Null
        Start-Sleep -Milliseconds 250
    }
    $xml = Get-UiXml
    Assert-Contains $xml 'text="vision.template"' "滚动后的能力列表"
    Assert-Contains $xml 'text="screen.capture"' "滚动后的能力列表"
}

Invoke-SmokeStep "Hello Runner lifecycle and stop observer" {
    Invoke-Adb shell cmd appops set com.autoscript.hello SYSTEM_ALERT_WINDOW allow | Out-Null
    Invoke-Adb shell am force-stop com.autoscript.hello | Out-Null
    Invoke-Adb shell am start -n com.autoscript.hello/com.autoscript.runner.MainActivity | Out-Null
    Approve-RootIfRequested
    Wait-Text "已认证" 15 | Out-Null
    Invoke-Adb shell input swipe 360 1050 360 350 450 | Out-Null
    Start-Sleep -Milliseconds 300
    # ScriptUiHost是传统View，MuMu的UIAutomator不公开其EditText；720x1280下使用稳定坐标。
    Invoke-Adb shell input tap 180 650 | Out-Null
    Invoke-Adb shell input keyevent 123 | Out-Null
    1..8 | ForEach-Object { Invoke-Adb shell input keyevent 67 | Out-Null }
    Invoke-Adb shell input text 60000 | Out-Null
    Invoke-Adb shell input keyevent 4 | Out-Null
    Start-Sleep -Milliseconds 250
    Tap-Text "运行" (Get-UiXml)
    $xml = Wait-Text "运行中" 12
    Tap-Text "暂停" $xml
    $xml = Wait-Text "已暂停" 8
    Tap-Text "继续" $xml
    $xml = Wait-Text "运行中" 8
    Tap-Text "停止" $xml
    Wait-Text "已停止" 8 | Out-Null
    Start-Sleep -Milliseconds 500
    $notifications = Invoke-Adb shell dumpsys notification --noredact | Out-String
    if ($notifications -match 'pkg=com\.autoscript\.hello.*id=16723') {
        throw "停止后Runner前台通知仍存在"
    }
}

Invoke-SmokeStep "Visual Runner embedded source-map runtime" {
    Invoke-Adb shell cmd appops set com.autoscript.visualsmoke SYSTEM_ALERT_WINDOW allow | Out-Null
    Invoke-Adb shell am force-stop com.autoscript.visualsmoke | Out-Null
    Invoke-Adb shell am start -n com.autoscript.visualsmoke/com.autoscript.runner.MainActivity | Out-Null
    Approve-RootIfRequested
    Wait-Text "已认证" 15 | Out-Null
    Invoke-Adb shell input swipe 360 1050 360 350 450 | Out-Null
    Start-Sleep -Milliseconds 250
    $xml = Get-UiXml
    Assert-Contains $xml "Visual Release Smoke" "可视化Runner"
    Assert-Contains $xml "visual" "可视化Runner源码类型"
    Tap-Text "运行" $xml
    Start-Sleep -Seconds 3
    $xml = Get-UiXml
    Assert-Contains $xml "已停止" "可视化Runner终态"
    Assert-Contains $xml "运行中" "可视化Runner生命周期日志"
}

Invoke-SmokeStep "Visual Runner source-map error location" {
    Invoke-Adb shell am force-stop com.autoscript.visualerror | Out-Null
    Invoke-Adb shell am start -n com.autoscript.visualerror/com.autoscript.runner.MainActivity | Out-Null
    Approve-RootIfRequested
    $xml = Wait-Text "已认证" 15
    Assert-Contains $xml "Visual Error Smoke" "错误定位Runner"
    Invoke-Adb shell input swipe 360 1050 360 350 450 | Out-Null
    Start-Sleep -Milliseconds 250
    $xml = Get-UiXml
    Tap-Text "运行" $xml
    Wait-Text "错误信息" 10 | Out-Null
    $xml = Get-UiXml
    Assert-Contains $xml "Flow main · 节点 node-while · Lua第" "source map错误位置"
    Assert-Contains $xml "control.while iteration limit exceeded" "运行错误正文"
}

$deviceReport = [pscustomobject]@{
    generatedAt = (Get-Date).ToString("o")
    device = $DeviceSerial
    artifactRoot = $artifact
    steps = @($report)
}
$reportPath = Join-Path $artifact "device-report.json"
$deviceReport | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath $reportPath -Encoding utf8
$report | Format-Table -AutoSize
Write-Host "`nM26 MuMu smoke PASS: $reportPath" -ForegroundColor Green
