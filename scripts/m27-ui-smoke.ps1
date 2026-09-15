[CmdletBinding()]
param(
    [Parameter(Mandatory)]
    [string]$StudioApk,
    [Parameter(Mandatory)]
    [string]$ReportDirectory,
    [string]$AdbPath = "C:\Work\AndroidSDK\platform-tools\adb.exe",
    [string]$DeviceSerial = "127.0.0.1:16384",
    [string]$ProjectName = "VisualSmoke",
    [switch]$SkipInstall
)

$ErrorActionPreference = "Stop"
Set-StrictMode -Version Latest
$results = [System.Collections.Generic.List[object]]::new()
$remoteXml = "/sdcard/m27-window.xml"

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

function Invoke-Step {
    param([string]$Name, [scriptblock]$Action)
    $watch = [Diagnostics.Stopwatch]::StartNew()
    Write-Host "`n[M27 UI] $Name" -ForegroundColor Cyan
    try {
        & $Action
        $watch.Stop()
        $results.Add([pscustomobject]@{ Step = $Name; Result = "PASS"; Seconds = [math]::Round($watch.Elapsed.TotalSeconds, 1) })
    } catch {
        $watch.Stop()
        $results.Add([pscustomobject]@{ Step = $Name; Result = "FAIL"; Seconds = [math]::Round($watch.Elapsed.TotalSeconds, 1); Error = $_.Exception.Message })
        throw
    }
}

function Get-UiXml {
    foreach ($attempt in 1..4) {
        $dump = Invoke-Adb shell uiautomator dump $remoteXml | Out-String
        if ($dump -match "dumped to") {
            $xml = Invoke-Adb exec-out cat $remoteXml | Out-String
            if ($xml -match "<hierarchy") { return $xml }
        }
        Start-Sleep -Milliseconds 400
    }
    throw "UIAutomator连续返回空窗口"
}

function Get-TextBounds {
    param([string]$Xml, [string]$Text)
    $pattern = '<node[^>]*text="' + [regex]::Escape($Text) + '"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"'
    $match = [regex]::Match($Xml, $pattern)
    if (-not $match.Success) { return $null }
    return @([int]$match.Groups[1].Value, [int]$match.Groups[2].Value, [int]$match.Groups[3].Value, [int]$match.Groups[4].Value)
}

function Wait-Text {
    param([string]$Text, [int]$TimeoutSeconds = 10)
    $deadline = [DateTime]::UtcNow.AddSeconds($TimeoutSeconds)
    do {
        $xml = Get-UiXml
        if ($null -ne (Get-TextBounds $xml $Text)) { return $xml }
        Start-Sleep -Milliseconds 300
    } while ([DateTime]::UtcNow -lt $deadline)
    throw "等待界面文字超时：$Text"
}

function Tap-Text {
    param([string]$Text, [string]$Xml = "")
    if ([string]::IsNullOrEmpty($Xml)) { $Xml = Get-UiXml }
    $bounds = Get-TextBounds $Xml $Text
    if ($null -eq $bounds) { throw "界面中找不到：$Text" }
    Invoke-Adb shell input tap ([int](($bounds[0] + $bounds[2]) / 2)) ([int](($bounds[1] + $bounds[3]) / 2)) | Out-Null
}

function Back-ToText {
    param([string]$Text)
    Invoke-Adb shell input keyevent 4 | Out-Null
    Wait-Text $Text | Out-Null
}

Invoke-Step "MuMu identity" {
    if (-not (Test-Path -LiteralPath $AdbPath -PathType Leaf)) { throw "ADB不存在：$AdbPath" }
    & $AdbPath connect $DeviceSerial | Out-Null
    $abi = (Invoke-Adb shell getprop ro.product.cpu.abi | Out-String).Trim()
    $sdk = (Invoke-Adb shell getprop ro.build.version.sdk | Out-String).Trim()
    $size = Invoke-Adb shell wm size | Out-String
    if ($abi -ne "x86_64" -or $sdk -ne "32" -or $size -notmatch "720x1280") {
        throw "设备不符合预期：ABI=$abi SDK=$sdk size=$size"
    }
}

if (-not $SkipInstall) {
    Invoke-Step "Install Studio" {
        $apk = (Resolve-Path -LiteralPath $StudioApk).Path
        $output = Invoke-Adb install -r -t $apk | Out-String
        if (-not $output.Contains("Success")) { throw "安装失败：$output" }
    }
}

Invoke-Step "Home and runtime environment" {
    Invoke-Adb shell am force-stop com.autoscript.studio | Out-Null
    Invoke-Adb shell am start -n com.autoscript.studio/.MainActivity | Out-Null
    Wait-Text "工作台" 15 | Out-Null
    Tap-Text "首页"
    $xml = Wait-Text "从项目开始"
    Tap-Text "查看运行环境" $xml
    $xml = Wait-Text "服务与权限"
    if ($xml -notmatch 'text="截图权限"' -or $xml -notmatch 'text="设备 Root 状态"') {
        throw "运行环境缺少关键状态"
    }
    Back-ToText "首页"
}

Invoke-Step "Profile reserved pages" {
    Tap-Text "我的"
    $xml = Wait-Text "登录入口"
    Tap-Text "登录入口" $xml
    Wait-Text "远程账号尚未开放" | Out-Null
    Back-ToText "我的"
    Tap-Text "隐私政策与用户协议"
    Wait-Text "本地版使用约定" | Out-Null
    Back-ToText "我的"
    Tap-Text "关于 AutoScript"
    Wait-Text "技术架构" | Out-Null
    Back-ToText "我的"
}

Invoke-Step "Workspace utility pages" {
    Tap-Text "工作台"
    $xml = Wait-Text "备份管理"
    Tap-Text "备份管理" $xml
    Wait-Text "从文件导入备份" | Out-Null
    Back-ToText "工作台"
    Tap-Text "学习项目"
    Wait-Text "暂无学习项目" | Out-Null
    Back-ToText "工作台"
    Tap-Text "开发者后台"
    Wait-Text "本地只读概览" | Out-Null
    Back-ToText "工作台"
}

Invoke-Step "Project file workspace and tools" {
    $xml = Wait-Text $ProjectName
    if ($null -eq (Get-TextBounds $xml "编辑")) {
        Tap-Text $ProjectName $xml
        Start-Sleep -Milliseconds 300
        $xml = Get-UiXml
    }
    Tap-Text "编辑" $xml
    $xml = Wait-Text "项目文件"
    if ($xml -notmatch 'text="project.json"') { throw "文件工作台缺少 project.json" }
    Tap-Text "界面" $xml
    Wait-Text "脚本界面" | Out-Null
    Back-ToText "项目文件"
    Tap-Text "图片"
    Wait-Text "截图工作区" | Out-Null
    Back-ToText "项目文件"
    Tap-Text "打包"
    Wait-Text "安装信息" | Out-Null
    Back-ToText "项目文件"
    Tap-Text "动作录制"
    Wait-Text "悬浮控制预览" | Out-Null
    Back-ToText "项目文件"
}

$directory = [IO.Path]::GetFullPath($ReportDirectory)
[IO.Directory]::CreateDirectory($directory) | Out-Null
$reportPath = Join-Path $directory "m27-ui-device-report.json"
[pscustomobject]@{
    generatedAt = (Get-Date).ToString("o")
    device = $DeviceSerial
    package = "com.autoscript.studio"
    steps = @($results)
} | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath $reportPath -Encoding utf8
$results | Format-Table -AutoSize
Write-Host "`nM27 UI smoke PASS: $reportPath" -ForegroundColor Green
