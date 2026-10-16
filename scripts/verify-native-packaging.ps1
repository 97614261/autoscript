param([string]$Sdk = '', [ValidateSet('development', 'arm64')][string]$Profile = 'development',
    [ValidateSet('', 'base', 'opencv', 'onnx', 'full')][string]$VisionProfile = '', [switch]$EmbeddedRunner)
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
Add-Type -AssemblyName System.IO.Compression.FileSystem
$root = Split-Path -Parent $PSScriptRoot
if (-not $Sdk) {
    $sdkLine = Get-Content -LiteralPath (Join-Path $root 'android/local.properties') | Where-Object { $_ -match '^sdk\.dir=' } | Select-Object -First 1
    if (-not $sdkLine) { throw 'Set -Sdk or configure local.properties sdk.dir' }
    $Sdk = $sdkLine.Substring(8).Replace('\\', '\').Replace('\:', ':')
}
$expectedAbis = if ($Profile -eq 'arm64') { @('arm64-v8a') } else { @('arm64-v8a', 'x86_64') }
$profileRoot = if ($Profile -eq 'arm64') { Join-Path $root 'build\profiles\arm64' } else { $root }
if ($VisionProfile) { $profileRoot = Join-Path $root "build/profiles/components/$Profile/$VisionProfile" }
$withOpenCv = $VisionProfile -in @('', 'opencv', 'full')
$withOnnx = $VisionProfile -in @('', 'onnx', 'full')
function Module-Build([string]$Module) { Join-Path $profileRoot ($Module + '\build') }
function Module-Output([string]$Module) {
    if ($Profile -eq 'arm64' -or $VisionProfile) { return Join-Path $profileRoot $Module }
    $physical = if ($Module.StartsWith('apps\')) { 'android\'+$Module } else { $Module.Replace('android\','android\modules\') }
    return Module-Build $physical
}
$readelf = Join-Path $Sdk 'ndk\30.0.14904198\toolchains\llvm\prebuilt\windows-x86_64\bin\llvm-readelf.exe'
if (-not (Test-Path -LiteralPath $readelf)) { throw 'Configured NDK readelf is required' }
$apkPath = Join-Path (Module-Output 'apps\studio-android') 'outputs\apk\debug\studio-android-debug.apk'
$audit = if ($Profile -eq 'arm64') { Join-Path $profileRoot 'native-packaging-audit' } else { Join-Path $root 'build\native-packaging-audit' }
[IO.Directory]::CreateDirectory($audit) | Out-Null
function Hash-Stream([IO.Stream]$Stream) {
    $sha = [Security.Cryptography.SHA256]::Create()
    try { return [BitConverter]::ToString($sha.ComputeHash($Stream)).Replace('-', '').ToLowerInvariant() }
    finally { $sha.Dispose() }
}
function Elf([string]$Option, [string]$Path) {
    $lines = @(& $readelf $Option --wide $Path)
    if ($LASTEXITCODE -ne 0) { throw "ELF inspection failed: $Path" }
    return $lines
}
$apk = [IO.Compression.ZipFile]::OpenRead($apkPath)
$summary = [Collections.Generic.List[string]]::new()
try {
    $summary.Add("APK bytes: $((Get-Item -LiteralPath $apkPath).Length)")
    $compressedPayload = ($apk.Entries | Measure-Object -Property CompressedLength -Sum).Sum
    $summary.Add("ZIP compressed entry payload: $compressedPayload bytes; other ZIP layout/signing/padding space: $((Get-Item -LiteralPath $apkPath).Length - $compressedPayload) bytes")
    $actualAbis = @($apk.Entries | Where-Object { $_.FullName -match '^lib/[^/]+/[^/]+\.so$' } | ForEach-Object { $_.FullName.Split('/')[1] } | Sort-Object -Unique)
    if (@(Compare-Object $expectedAbis $actualAbis).Count -ne 0) { throw 'APK ABIs differ from selected profile' }
    $summary.Add("Profile: $Profile; ABIs: $($actualAbis -join ',')")
    $summary.Add("Vision profile: $(if($VisionProfile) {$VisionProfile} else {'full (default)'})")
    foreach ($abi in $expectedAbis) {
        $cv = $apk.GetEntry("lib/$abi/libopencv_java4.so")
        if (($null -ne $cv) -ne $withOpenCv) { throw "OpenCV contents differ from $VisionProfile profile: $abi" }
        foreach ($libraryName in @('libonnxruntime.so', 'libonnxruntime4j_jni.so')) {
            $optionalLibrary = $apk.GetEntry("lib/$abi/$libraryName")
            if (($null -ne $optionalLibrary) -ne $withOnnx) { throw "ONNX contents differ from $VisionProfile profile: $abi" }
        }
        foreach ($library in @(
            @{ Name = 'libengine_jni.so'; Raw = Join-Path (Module-Output 'android\engine-jni') "generated\unstripped\$abi\libengine_jni.so" },
            @{ Name = 'libstudio_compiler_jni.so'; Raw = Join-Path (Module-Output 'android\studio-compiler') "generated\unstripped\$abi\libstudio_compiler_jni.so" },
            @{ Name = 'libautoscript_root_daemon.so'; Raw = Join-Path (Module-Output 'android\runtime-service') "generated\rootDaemon\unstripped\$abi\libautoscript_root_daemon.so" }
        )) {
            $entry = $apk.GetEntry("lib/$abi/$($library.Name)")
            if ($null -eq $entry) { throw "Missing $abi library: $($library.Name)" }
            $original = $library.Raw
            $packaged = Join-Path $audit "$abi-$($library.Name)"
            $input = $entry.Open(); $output = [IO.File]::Create($packaged)
            try { $input.CopyTo($output) } finally { $output.Dispose(); $input.Dispose() }
            if (@((Elf '--sections' $original) -match '\.debug_info\s').Count -eq 0) { throw "Raw debug info missing: $original" }
            if (@((Elf '--sections' $packaged) -match '\.debug_info\s').Count -gt 0) { throw "Packaged debug info retained: $packaged" }
            $before = @(Elf '--dyn-syms' $original | Where-Object { $_ -match '^\s+\d+:' })
            $after = @(Elf '--dyn-syms' $packaged | Where-Object { $_ -match '^\s+\d+:' })
            if (@(Compare-Object $before $after).Count -ne 0) { throw "Dynamic exports changed: $packaged" }
            $summary.Add("$abi/$($library.Name): $((Get-Item -LiteralPath $original).Length) -> $($entry.Length) bytes; dynamic symbols preserved; no debug_info")
        }
    }
    $entry = $apk.GetEntry('assets/vision/en_PP-OCRv4_rec_mobile.onnx')
    if (-not $withOnnx) {
        if ($null -ne $entry) { throw 'Non-ONNX profile must not contain the OCR model' }
        $summary.Add('OCR model: omitted for selected vision profile')
    } else {
    if ($null -eq $entry) { throw 'OCR model missing' }
    $input = $entry.Open()
    try { $modelHash = Hash-Stream $input } finally { $input.Dispose() }
    $input = [IO.File]::OpenRead((Join-Path $root 'android\modules\runtime-service\src\onnx\assets\vision\en_PP-OCRv4_rec_mobile.onnx'))
    try { $originalHash = Hash-Stream $input } finally { $input.Dispose() }
    if ($modelHash -ne $originalHash) { throw 'Packaged OCR model changed' }
    $summary.Add("OCR model: $($entry.Length) bytes; original SHA-256 preserved ($modelHash)")
    }
    $template = $apk.GetEntry('assets/packaging/runner-template.apk')
    $templateMetadata = $apk.GetEntry('assets/packaging/runner-template.json')
    if (-not $EmbeddedRunner) {
        if ($null -ne $template -or $null -ne $templateMetadata) { throw 'Base Studio must not embed a Runner APK or its metadata' }
        $summary.Add('Independent Runner: not embedded; no download or device packaging component installed')
    } else {
    if ($null -eq $template -or $null -eq $templateMetadata) { throw 'Explicit offline Runner template missing' }
    $input = $template.Open()
    try { $templateHash = Hash-Stream $input } finally { $input.Dispose() }
    $templateFile = Join-Path (Module-Output 'apps\runner-template-android') 'outputs\apk\release\runner-template-android-release-unsigned.apk'
    $input = [IO.File]::OpenRead($templateFile)
    try { $originalHash = Hash-Stream $input } finally { $input.Dispose() }
    if ($templateHash -ne $originalHash) { throw 'Embedded Runner template differs from compiled Runner' }
    $runnerZip = [IO.Compression.ZipFile]::OpenRead($templateFile)
    try {
        $runnerAbis = @($runnerZip.Entries | Where-Object { $_.FullName -match '^lib/[^/]+/[^/]+\.so$' } | ForEach-Object { $_.FullName.Split('/')[1] } | Sort-Object -Unique)
        if (@(Compare-Object $expectedAbis $runnerAbis).Count -ne 0) { throw 'Embedded Runner ABI profile mismatch' }
    } finally { $runnerZip.Dispose() }
    $summary.Add("Embedded Runner: $($template.Length) bytes; original SHA-256 preserved")
    }
    foreach ($entry in @($apk.Entries | Sort-Object Length -Descending | Select-Object -First 12)) {
        $summary.Add("Top entry: $($entry.FullName) $($entry.Length) bytes (ZIP $($entry.CompressedLength))")
    }
} finally { $apk.Dispose() }
$summary | Set-Content -LiteralPath (Join-Path $audit 'verification.txt') -Encoding UTF8
$summary
