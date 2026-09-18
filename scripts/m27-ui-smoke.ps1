[CmdletBinding()]
param(
    # 两者都可省略：默认取 build\ 下最新的 m27-* 门禁产物目录。
    [string]$StudioApk = "",
    [string]$ReportDirectory = "",
    [string]$AdbPath = "C:\Work\AndroidSDK\platform-tools\adb.exe",
    [string]$DeviceSerial = "127.0.0.1:16384",
    [string]$ProjectName = "VisualSmoke",
    [switch]$SkipInstall
)

# M27 页面路由与关键文字回归（MuMu 720x1280）。
# 覆盖：主页轮播/价值面板、我的四个子页、运行环境、备份/学习/后台、工作台项目卡、
# 界面设计器、打包、悬浮程序树（小球→面板→文件弹窗→更多→录制）、源文件管理、编辑窗口。
# 每一步同时保存 UIAutomator XML 与截图到 ReportDirectory\m27-ui-shots，供与参考截图并排比对。

$ErrorActionPreference = "Stop"
Set-StrictMode -Version Latest

# 省略参数时自动定位最新一次 m27-gate 的产物，避免每次手抄时间戳。
$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
if ([string]::IsNullOrWhiteSpace($ReportDirectory) -or [string]::IsNullOrWhiteSpace($StudioApk)) {
    $buildRoot = Join-Path $repoRoot "build"
    $latest = Get-ChildItem -LiteralPath $buildRoot -Directory -Filter "m27-*" -ErrorAction SilentlyContinue |
        Sort-Object Name -Descending | Select-Object -First 1
    if ($null -eq $latest) { throw "build\ 下没有 m27-* 目录，请先运行 scripts\m27-gate.ps1" }
    if ([string]::IsNullOrWhiteSpace($ReportDirectory)) { $ReportDirectory = $latest.FullName }
    if ([string]::IsNullOrWhiteSpace($StudioApk)) { $StudioApk = Join-Path $latest.FullName "studio-debug.apk" }
    Write-Host "使用最新门禁产物：$($latest.Name)" -ForegroundColor DarkGray
}
if (-not (Test-Path -LiteralPath $StudioApk -PathType Leaf)) { throw "Studio APK 不存在：$StudioApk" }

# adb 的 UIAutomator XML 是 UTF-8。PowerShell 解码原生命令 stdout 用的是 [Console]::OutputEncoding，
# 中文 Windows 上默认是 GB2312/GBK，会把 XML 里的中文解成乱码，连结束引号都可能被吞掉，
# 于是所有中文文字断言静默失配（Get-UiXml 仍会成功，因为 "<hierarchy" 是 ASCII）。
# 这里强制按 UTF-8 解码；$OutputEncoding 同时保证传给 adb 的参数也是 UTF-8。
$previousOutputEncoding = [Console]::OutputEncoding
[Console]::OutputEncoding = [Text.UTF8Encoding]::new($false)
$OutputEncoding = [Text.UTF8Encoding]::new($false)
$results = [System.Collections.Generic.List[object]]::new()
$remoteXml = "/sdcard/m27-window.xml"
$remotePng = "/sdcard/m27-shot.png"
$shotDirectory = Join-Path ([IO.Path]::GetFullPath($ReportDirectory)) "m27-ui-shots"
[IO.Directory]::CreateDirectory($shotDirectory) | Out-Null
$shotIndex = 0

# 必须是简单函数（用 $args，不写 param）：只要 param 里带 [Parameter()] 属性就成了高级函数，
# PowerShell 会自动附加 -ProgressAction / -PipelineVariable 等公共参数，于是 adb 自己的短选项
# 会被当成公共参数的前缀匹配 —— `shell screencap -p` 的 -p 就会报“参数名称存在歧义”。
function Invoke-Adb {
    $arguments = @($args)
    $output = $null
    foreach ($attempt in 1..3) {
        $output = & $AdbPath -s $DeviceSerial @arguments 2>&1
        if ($LASTEXITCODE -eq 0) { return $output }
        if ($attempt -lt 3) {
            & $AdbPath start-server 2>&1 | Out-Null
            & $AdbPath connect $DeviceSerial 2>&1 | Out-Null
            Start-Sleep -Milliseconds 500
        }
    }
    throw "adb失败：$($arguments -join ' ')；$($output | Out-String)"
}

# 把 App 拉回干净的首屏。某一步失败时界面可能停在任意子页/弹窗里，
# 不复位的话后面每一步都会连锁失败，一轮就只能拿到一个真问题。
function Reset-App {
    try {
        $before = (Invoke-Adb shell pidof com.autoscript.studio | Out-String).Trim()
        Invoke-Adb shell am force-stop com.autoscript.studio | Out-Null
        Invoke-Adb shell am start -n com.autoscript.studio/.MainActivity | Out-Null
        Wait-Text "工作台" 20 | Out-Null
        # 判断"是否真的重启了"必须看进程号，不能看界面状态。
        # 曾经用"项目卡是否仍展开"来判断，前提是"重启后必然收起"——那是错的：
        # ProjectPage.kt 的 LaunchedEffect 会把第一个项目自动展开，所以重启后
        # "打包"本来就在。那版判断会在任何一步失败时必然误报"设备已冻结"，
        # 把真正的失败原因盖掉。之前 8/8 全过、Reset-App 从未执行，才一直没暴露。
        $after = (Invoke-Adb shell pidof com.autoscript.studio | Out-String).Trim()
        if ([string]::IsNullOrWhiteSpace($after)) {
            throw "App 重启后进程不存在：可能启动即崩溃，检查 adb logcat"
        }
        if ($before -ne "" -and $before -eq $after) {
            throw "App 未真正重启（进程号仍是 $after）：force-stop 没生效，设备可能已冻结，请重启 MuMu 后重跑"
        }
    } catch {
        Write-Host "  复位失败：$($_.Exception.Message)" -ForegroundColor DarkYellow
        throw
    }
}

# 默认「记录失败并继续」：一轮跑完收集全部问题，不用每修一条就重跑整个 smoke。
# -Critical 的步骤（设备校验、安装）失败则直接终止，继续跑没有意义。
function Invoke-Step {
    param([string]$Name, [scriptblock]$Action, [switch]$Critical)
    $watch = [Diagnostics.Stopwatch]::StartNew()
    Write-Host "`n[M27 UI] $Name" -ForegroundColor Cyan
    try {
        & $Action
        $watch.Stop()
        $results.Add([pscustomobject]@{ Step = $Name; Result = "PASS"; Seconds = [math]::Round($watch.Elapsed.TotalSeconds, 1) })
    } catch {
        $watch.Stop()
        $message = $_.Exception.Message
        try { Save-Shot ("FAIL-" + ($Name -replace '[^\w\-]', '_')) } catch { }
        $results.Add([pscustomobject]@{ Step = $Name; Result = "FAIL"; Seconds = [math]::Round($watch.Elapsed.TotalSeconds, 1); Error = $message })
        Write-Host "  FAIL: $message" -ForegroundColor Red
        if ($Critical) { throw }
        Reset-App
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

function Save-Shot {
    param([string]$Label)
    $script:shotIndex++
    $stem = "{0:D2}-{1}" -f $script:shotIndex, $Label
    Invoke-Adb shell screencap -p $remotePng | Out-Null
    Invoke-Adb pull $remotePng (Join-Path $shotDirectory "$stem.png") | Out-Null
    try { Get-UiXml | Set-Content -LiteralPath (Join-Path $shotDirectory "$stem.xml") -Encoding utf8 } catch { }
}

function Get-AttrBounds {
    param([string]$Xml, [string]$Attribute, [string]$Value)
    $pattern = '<node[^>]*' + $Attribute + '="' + [regex]::Escape($Value) + '"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"'
    $match = [regex]::Match($Xml, $pattern)
    if (-not $match.Success) { return $null }
    return @([int]$match.Groups[1].Value, [int]$match.Groups[2].Value, [int]$match.Groups[3].Value, [int]$match.Groups[4].Value)
}

function Get-TextBounds {
    param([string]$Xml, [string]$Text)
    return Get-AttrBounds $Xml "text" $Text
}

function Get-DescBounds {
    param([string]$Xml, [string]$Description)
    return Get-AttrBounds $Xml "content-desc" $Description
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

function Wait-Desc {
    param([string]$Description, [int]$TimeoutSeconds = 10)
    $deadline = [DateTime]::UtcNow.AddSeconds($TimeoutSeconds)
    do {
        $xml = Get-UiXml
        if ($null -ne (Get-DescBounds $xml $Description)) { return $xml }
        Start-Sleep -Milliseconds 300
    } while ([DateTime]::UtcNow -lt $deadline)
    throw "等待界面元素超时：$Description"
}

# Compose 的 LazyColumn 只组合可见项：屏幕外的元素根本不在 UIAutomator 树里，
# 所以对长页面（我的、打包表单）必须先滚动到目标出现，再断言或点击。
function Scroll-ToText {
    param([string]$Text, [int]$MaxSwipes = 8)
    foreach ($attempt in 0..$MaxSwipes) {
        $xml = Get-UiXml
        if ($null -ne (Get-TextBounds $xml $Text)) { return $xml }
        if ($attempt -eq $MaxSwipes) { break }
        Invoke-Adb shell input swipe 360 1000 360 520 220 | Out-Null
        Start-Sleep -Milliseconds 450
    }
    throw "滚动 $MaxSwipes 次后仍找不到界面文字：$Text"
}

function Wait-TextGone {
    param([string]$Text, [int]$TimeoutSeconds = 10)
    $deadline = [DateTime]::UtcNow.AddSeconds($TimeoutSeconds)
    do {
        $xml = Get-UiXml
        if ($null -eq (Get-TextBounds $xml $Text)) { return $xml }
        Start-Sleep -Milliseconds 300
    } while ([DateTime]::UtcNow -lt $deadline)
    throw "界面文字未消失：$Text"
}

function Tap-Bounds {
    param([int[]]$Bounds)
    Invoke-Adb shell input tap ([int](($Bounds[0] + $Bounds[2]) / 2)) ([int](($Bounds[1] + $Bounds[3]) / 2)) | Out-Null
}

function Tap-Text {
    param([string]$Text, [string]$Xml = "")
    if ([string]::IsNullOrEmpty($Xml)) { $Xml = Get-UiXml }
    $bounds = Get-TextBounds $Xml $Text
    if ($null -eq $bounds) { throw "界面中找不到：$Text" }
    Tap-Bounds $bounds
}

function Tap-Desc {
    param([string]$Description, [string]$Xml = "")
    if ([string]::IsNullOrEmpty($Xml)) { $Xml = Get-UiXml }
    $bounds = Get-DescBounds $Xml $Description
    if ($null -eq $bounds) { throw "界面中找不到元素：$Description" }
    Tap-Bounds $bounds
}

function Back-ToText {
    param([string]$Text)
    Invoke-Adb shell input keyevent 4 | Out-Null
    Wait-Text $Text | Out-Null
}

function Assert-Text {
    param([string]$Xml, [string]$Text, [string]$What)
    if ($null -eq (Get-TextBounds $Xml $Text)) { throw "${What}缺少文字：$Text" }
}

# 工作台项目卡：展开后才出现 编辑/界面/备份/打包/删除 按钮。
# 「是否已展开」必须限定在目标项目自己的卡片内：整屏搜“打包”在多项目下会命中
# 别的卡片，于是跳过点击，后续步骤全作用在错误的项目上。曾因此让第 8 步跑到
# Lua 项目上找源文件管理——而 Lua 项目的标题本来就不弹那个弹窗。
# 取「属于这张卡」的那个按钮。整屏取第一个匹配在多项目下会命中别的卡片：
# 卡片动作行都是同样的 启动/编辑/界面/备份/打包/删除，谁在前面就取到谁。
function Get-BoundsInCard {
    param([string]$Xml, [string]$Text, [int[]]$NameBounds)
    $pattern = '<node[^>]*text="' + [regex]::Escape($Text) + '"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"'
    foreach ($m in [regex]::Matches($Xml, $pattern)) {
        $top = [int]$m.Groups[2].Value
        # 动作行在卡片标题下方，间距远小于一张卡的高度；240px 足够覆盖且不会跨到下一张卡。
        if ($top -gt $NameBounds[1] -and $top -lt ($NameBounds[1] + 240)) {
            return @([int]$m.Groups[1].Value, $top, [int]$m.Groups[3].Value, [int]$m.Groups[4].Value)
        }
    }
    return $null
}

function Test-CardExpanded {
    param([string]$Xml, [int[]]$NameBounds)
    return $null -ne (Get-BoundsInCard $Xml "打包" $NameBounds)
}

# 点目标项目卡里的某个动作按钮。$script:cardNameBounds 由 Open-ProjectCard 设好。
function Tap-CardAction {
    param([string]$Xml, [string]$Text)
    if ($null -eq $script:cardNameBounds) { throw "Tap-CardAction 必须在 Open-ProjectCard 之后调用" }
    $bounds = Get-BoundsInCard $Xml $Text $script:cardNameBounds
    if ($null -eq $bounds) { throw "$ProjectName 的卡片里找不到按钮：$Text" }
    Tap-Bounds $bounds
}

function Open-ProjectCard {
    Tap-Text "工作台"
    $xml = Wait-Text "项目列表"
    $nameBounds = Get-TextBounds $xml $ProjectName
    if ($null -eq $nameBounds) {
        throw "工作台没有项目：$ProjectName（先在 App 中创建，或传入实际项目名）"
    }
    if (-not (Test-CardExpanded $xml $nameBounds)) {
        Tap-Text $ProjectName $xml
        Start-Sleep -Milliseconds 400
        $xml = Wait-Text "打包"
        $nameBounds = Get-TextBounds $xml $ProjectName
        if ($null -eq $nameBounds -or -not (Test-CardExpanded $xml $nameBounds)) {
            throw "点开 $ProjectName 后它的卡片仍未展开（可能点到了别的卡片）"
        }
    }
    $script:cardNameBounds = $nameBounds
    return $xml
}

$deviceInfo = [ordered]@{}
Invoke-Step "MuMu identity" -Critical {
    if (-not (Test-Path -LiteralPath $AdbPath -PathType Leaf)) { throw "ADB不存在：$AdbPath" }
    & $AdbPath connect $DeviceSerial | Out-Null
    $abi = (Invoke-Adb shell getprop ro.product.cpu.abi | Out-String).Trim()
    $sdk = (Invoke-Adb shell getprop ro.build.version.sdk | Out-String).Trim()
    $size = Invoke-Adb shell wm size | Out-String
    if ($abi -ne "x86_64" -or $sdk -ne "32" -or $size -notmatch "720x1280") {
        throw "设备不符合预期：ABI=$abi SDK=$sdk size=$size"
    }
    # 字体缩放和密度直接决定所有 sp/dp 的实际像素，截图比对必须连它们一起记录，
    # 否则“我们比参考宽一点”分不清是代码字号问题还是设备设置问题。
    $fontScale = (Invoke-Adb shell settings get system font_scale | Out-String).Trim()
    if ([string]::IsNullOrWhiteSpace($fontScale) -or $fontScale -eq "null") { $fontScale = "1.0 (系统默认)" }
    $density = (Invoke-Adb shell wm density | Out-String).Trim()
    $deviceInfo["abi"] = $abi
    $deviceInfo["sdk"] = $sdk
    $deviceInfo["size"] = $size.Trim()
    $deviceInfo["density"] = $density
    $deviceInfo["fontScale"] = $fontScale
    Write-Host "  $density | font_scale=$fontScale" -ForegroundColor DarkGray
}

if (-not $SkipInstall) {
    Invoke-Step "Install Studio" -Critical {
        $apk = (Resolve-Path -LiteralPath $StudioApk).Path
        $output = Invoke-Adb install -r -t $apk | Out-String
        if (-not $output.Contains("Success")) { throw "安装失败：$output" }
    }
}

Invoke-Step "Home carousel and value panel" {
    Invoke-Adb shell am force-stop com.autoscript.studio | Out-Null
    Invoke-Adb shell am start -n com.autoscript.studio/.MainActivity | Out-Null
    Wait-Text "工作台" 15 | Out-Null
    Tap-Text "主页"
    $xml = Wait-Text "本机运行"
    Assert-Text $xml "Lua 引擎" "主页价值面板"
    Save-Shot "home"
    # 轮播自动翻页：4.6 秒后页码文字应变化。
    $before = [regex]::Match($xml, 'text="(0[1-4]) / 04"').Groups[1].Value
    Start-Sleep -Milliseconds 5200
    $after = [regex]::Match((Get-UiXml), 'text="(0[1-4]) / 04"').Groups[1].Value
    if ($before -eq "" -or $after -eq "" -or $before -eq $after) { throw "主页轮播没有自动翻页：$before -> $after" }
}

Invoke-Step "Profile and runtime environment" {
    Tap-Text "我的"
    $xml = Wait-Text "功能服务"
    Assert-Text $xml "登录 / 注册" "我的页头"
    Save-Shot "profile"
    Tap-Text "运行环境" $xml
    $xml = Wait-Text "服务与权限"
    Assert-Text $xml "设备 Root 状态" "运行环境"
    Assert-Text $xml "截屏服务" "运行环境"
    Assert-Text $xml "重启服务" "运行环境"
    Save-Shot "runtime-environment"
    Back-ToText "功能服务"
    Tap-Text "开发文档"
    Wait-Text "脚本函数 · 本地 API" | Out-Null
    Save-Shot "docs"
    Back-ToText "功能服务"
    Tap-Text "检查更新"
    Wait-Text "技术架构" | Out-Null
    Save-Shot "update"
    Back-ToText "功能服务"
    # 底部法律链接在首屏之外，LazyColumn 没组合它；先滚到底再点。
    $xml = Scroll-ToText "用户协议"
    Assert-Text $xml "隐私政策" "我的页底部"
    Tap-Text "用户协议" $xml
    Wait-Text "AutoScript 软件许可及服务协议" | Out-Null
    Back-ToText "功能服务"
    Tap-Text "登录 / 注册"
    Wait-Text "账号登录" | Out-Null
    Save-Shot "login"
    # 这条“不会上传或保存”的说明是预留页诚实性的关键证据，在页面底部，要滚动才可见。
    Scroll-ToText "账号服务尚未配置，输入内容不会上传或保存。" | Out-Null
    Back-ToText "功能服务"
}

Invoke-Step "Workspace utility pages" {
    Tap-Text "工作台"
    $xml = Wait-Text "项目列表"
    Save-Shot "workspace"
    Tap-Text "备份管理" $xml
    $xml = Wait-Text "可用 3 槽位/项目"
    Assert-Text $xml "导入" "备份管理头部"
    Save-Shot "backup-management"
    Back-ToText "项目列表"
    Tap-Text "学习项目"
    Wait-Text "暂无学习项目" | Out-Null
    Back-ToText "项目列表"
    Tap-Text "开发者后台"
    Wait-TextGone "项目列表" | Out-Null
    Save-Shot "developer-backend"
    Back-ToText "项目列表"
}

Invoke-Step "Project card tools: designer, package, backup slots" {
    $xml = Open-ProjectCard
    Save-Shot "project-card"
    Tap-CardAction $xml "界面"
    $xml = Wait-Text "坐标显示"
    Assert-Text $xml "常用" "脚本界面设计器右栏"
    Assert-Text $xml "属性" "脚本界面设计器右栏"
    Save-Shot "designer"
    Back-ToText "项目列表"
    $xml = Open-ProjectCard
    Tap-CardAction $xml "打包"
    # “应用外观”在首屏，“安装信息”是下一个分区，可能要滚；“开始打包 APK”是固定底栏。
    $xml = Wait-Text "应用外观"
    Assert-Text $xml "开始打包 APK" "打包页底栏"
    Save-Shot "package"
    Scroll-ToText "安装信息" | Out-Null
    Back-ToText "项目列表"
    $xml = Open-ProjectCard
    Tap-CardAction $xml "备份"
    # 用固定的“备份槽位 1”判定，不依赖剩余槽位数（已有备份时标题会变成“可用 2 槽位”）。
    $xml = Wait-Text "备份槽位 1"
    Assert-Text $xml "备份槽位 3" "备份槽位页"
    Save-Shot "backup-slots"
    Back-ToText "项目列表"
}

Invoke-Step "Floating dock: ball, panel, file dialog, recorder" {
    $xml = Open-ProjectCard
    Tap-CardAction $xml "编辑"
    # 点击编辑后先出现靠边小球，不直接弹面板。
    $xml = Wait-Desc "悬浮操作按钮"
    if ($null -ne (Get-TextBounds $xml "函数")) { throw "编辑后直接弹出了面板，应先显示小球" }
    Save-Shot "dock-ball"
    Tap-Desc "悬浮操作按钮" $xml
    $xml = Wait-Text "函数"
    Assert-Text $xml "文件" "悬浮面板右栏"
    Assert-Text $xml "工具" "悬浮面板右栏"
    # 注意：PowerShell 把中文弯引号当成字符串定界符，字符串里只能用「」。
    if ($null -eq (Get-DescBounds $xml "更多工具")) { throw "悬浮面板底栏缺少「更多」按钮" }
    Save-Shot "dock-panel"
    Tap-Text "文件" $xml
    $xml = Wait-Text "project.json"
    Assert-Text $xml "全选" "文件弹窗第二行"
    Save-Shot "dock-file-dialog"
    Back-ToText "函数"
    Tap-Desc "更多工具"
    $xml = Wait-Text "录制动作"
    Save-Shot "dock-more-menu"
    Tap-Text "录制动作" $xml
    Wait-Text "悬浮控制预览" | Out-Null
    Save-Shot "recorder"
    Back-ToText "项目列表"
}

Invoke-Step "Source manager and edit window" {
    $xml = Open-ProjectCard
    Tap-CardAction $xml "编辑"
    Tap-Desc "悬浮操作按钮" (Wait-Desc "悬浮操作按钮")
    $xml = Wait-Text "函数"
    # 标题栏文字：有源文件时是当前 Flow 名，否则“点击创建源文件”；两者都应打开源文件管理。
    $title = Get-TextBounds $xml "点击创建源文件"
    if ($null -eq $title) {
        $header = Get-TextBounds $xml "函数"
        # 面板标题在右栏“函数”按钮上方约 35dp 标题栏内；取右栏顶部同一行左侧位置。
        $panel = [regex]::Match($xml, 'text="文件"[^>]*bounds="\[(\d+),(\d+)\]')
        if (-not $panel.Success) { throw "无法定位悬浮面板" }
        $left = [int]$panel.Groups[1].Value - 150
        $top = [int]$panel.Groups[2].Value - 30
        Invoke-Adb shell input tap $left $top | Out-Null
    } else {
        Tap-Bounds $title
    }
    $xml = Wait-Text "全选"
    Assert-Text $xml "取消" "源文件管理底栏"
    Assert-Text $xml "确定" "源文件管理底栏"
    Save-Shot "source-manager"
    Invoke-Adb shell input keyevent 4 | Out-Null
    $xml = Wait-Text "函数"
    # 底栏“编辑”只在选中节点后可用；未选中时点一下程序树第一行（标题栏 35dp 下的首个 35dp 行）。
    $editNode = [regex]::Match($xml, '<node[^>]*content-desc="编辑"[^>]*enabled="(true|false)"')
    if (-not $editNode.Success) { throw "悬浮面板底栏缺少「编辑」按钮" }
    if ($editNode.Groups[1].Value -ne "true") {
        $file = Get-TextBounds $xml "文件"
        if ($null -eq $file) { throw "无法定位悬浮面板右栏" }
        $density = [double]((Invoke-Adb shell wm density | Out-String) -replace '[^\d]', '') / 160.0
        if ($density -le 0) { $density = 2.0 }
        $rowX = [int]($file[0] - 120 * $density)
        $rowY = [int]($file[1] + 17 * $density)
        Invoke-Adb shell input tap $rowX $rowY | Out-Null
        Start-Sleep -Milliseconds 300
        $xml = Get-UiXml
        $editNode = [regex]::Match($xml, '<node[^>]*content-desc="编辑"[^>]*enabled="(true|false)"')
        if ($editNode.Groups[1].Value -ne "true") { throw "程序树没有可选中的节点，无法打开编辑窗口（项目 $ProjectName 需要至少一个积木）" }
    }
    Tap-Desc "编辑" $xml
    $xml = Wait-Text "关闭"
    Assert-Text $xml "撤销" "编辑窗口底栏"
    Assert-Text $xml "粘贴" "编辑窗口底栏"
    Save-Shot "edit-window"
    Back-ToText "函数"
    # 返回键层级：面板 → 小球 → 退出悬浮编辑回到项目列表。
    # 小球态必须由 ProjectPage 的 BackHandler 兜底，否则会穿透到 Activity 直接退出 App。
    Invoke-Adb shell input keyevent 4 | Out-Null
    Start-Sleep -Milliseconds 400
    Invoke-Adb shell input keyevent 4 | Out-Null
    $xml = Wait-Text "项目列表"
    Assert-Text $xml "工作台" "退出悬浮编辑后应回到工作台，而不是退出 App"
}

$directory = [IO.Path]::GetFullPath($ReportDirectory)
[IO.Directory]::CreateDirectory($directory) | Out-Null
$reportPath = Join-Path $directory "m27-ui-device-report.json"
[pscustomobject]@{
    generatedAt = (Get-Date).ToString("o")
    device = $DeviceSerial
    deviceInfo = $deviceInfo
    package = "com.autoscript.studio"
    project = $ProjectName
    screenshots = $shotDirectory
    steps = @($results)
} | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath $reportPath -Encoding utf8
[Console]::OutputEncoding = $previousOutputEncoding
$results | Format-Table -AutoSize

$failures = @($results | Where-Object { $_.Result -eq "FAIL" })
Write-Host "截图与 UIAutomator XML：$shotDirectory"
Write-Host "JSON 报告：$reportPath"
if ($failures.Count -gt 0) {
    Write-Host "`nM27 UI smoke 失败 $($failures.Count) 步：" -ForegroundColor Red
    foreach ($item in $failures) {
        Write-Host ("  - {0}：{1}" -f $item.Step, $item.Error) -ForegroundColor Red
    }
    exit 1
}
Write-Host "`nM27 UI smoke PASS" -ForegroundColor Green
