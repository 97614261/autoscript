param([ValidateSet('development', 'arm64')][string]$Profile = 'development',
    [string]$SdkDirectory = 'C:\Work\AndroidSDK',
    [string]$JavaPath = '')
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
$repo = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
if (-not $JavaPath) {
    $JavaPath = (Get-Command java -ErrorAction Stop).Source
}
$buildTools = Get-ChildItem -LiteralPath (Join-Path $SdkDirectory 'build-tools') -Directory -ErrorAction Stop |
    Where-Object { $_.Name -match '^\d+(\.\d+){1,3}$' -and
        (Test-Path -LiteralPath (Join-Path $_.FullName 'lib\apksigner.jar') -PathType Leaf) -and
        (Test-Path -LiteralPath (Join-Path $_.FullName 'aapt2.exe') -PathType Leaf) } |
    Sort-Object { [version]$_.Name } | Select-Object -Last 1
if ($null -eq $buildTools) { throw "No complete Android Build Tools installation was found under $(Join-Path $SdkDirectory 'build-tools')" }
$signer = Join-Path $buildTools.FullName 'lib\apksigner.jar'
$aapt = Join-Path $buildTools.FullName 'aapt2.exe'
Add-Type -AssemblyName System.IO.Compression.FileSystem
. (Join-Path $PSScriptRoot 'build-install-mumu.ps1')
$studio = if ($Profile -eq 'development') {
    Join-Path $repo 'android\apps\studio-android\build\outputs\apk\debug\studio-android-debug.apk'
} else {
    Join-Path $repo 'build\profiles\arm64\apps\studio-android\outputs\apk\debug\studio-android-debug.apk'
}
function Certificate([string]$Apk) {
    $output = Invoke-CheckedCli $JavaPath @('-jar', $signer, 'verify', '--verbose', '--print-certs', '--min-sdk-version', '24', $Apk) 120
    if ($output -notmatch 'Verified using v2 scheme.*: true') { throw 'APK v2 verification failed' }
    $certificateMatches = [regex]::Matches($output, 'Signer #([0-9]+) certificate SHA-256 digest: ([0-9a-f]{64})')
    if ($certificateMatches.Count -ne 1 -or $certificateMatches[0].Groups[1].Value -ne '1') { throw 'Single signing identity required' }
    return $certificateMatches[0].Groups[2].Value
}
function Hash-Entry($Zip, [string]$Name, [long]$Maximum) {
    $entry = $Zip.GetEntry($Name)
    if ($null -eq $entry -or $entry.Length -le 0 -or $entry.Length -gt $Maximum) { throw 'Missing or oversized component entry' }
    $input = $entry.Open()
    $sha = [Security.Cryptography.SHA256]::Create()
    try { return [BitConverter]::ToString($sha.ComputeHash($input)).Replace('-', '').ToLowerInvariant() }
    finally { $sha.Dispose(); $input.Dispose() }
}
$certificate = Certificate $studio
$protocolSource = Join-Path $repo 'android\modules\runtime-api\src\main\java\com\autoscript\runtime\api\RuntimeProtocol.kt'
$protocolMatch = [regex]::Match((Get-Content -LiteralPath $protocolSource -Raw), 'const val VERSION: Int = ([0-9]+)')
if (-not $protocolMatch.Success) { throw 'Runtime protocol version could not be read from the source contract' }
$runtimeProtocol = [int]$protocolMatch.Groups[1].Value
$componentVersionMatch = [regex]::Match((Get-Content -LiteralPath $protocolSource -Raw), 'const val RUNNER_TEMPLATE_COMPONENT_VERSION: Int = ([0-9]+)')
if (-not $componentVersionMatch.Success) { throw 'Runner template component version could not be read from the source contract' }
$componentVersion = [int]$componentVersionMatch.Groups[1].Value
$report = @()
foreach ($family in @('base', 'opencv', 'onnx', 'full')) {
    $directory = if ($family -eq 'full' -and $Profile -eq 'development') {
        Join-Path $repo 'android\apps\runner-template-android\build'
    } else { Join-Path $repo "build\profiles\components\$Profile\$family\apps\runner-template-android" }
    $apk = Join-Path $directory "outputs\components\runner-$family-$Profile.apk"
    if ((Certificate $apk) -ne $certificate) { throw "Component publisher differs from Studio: $family" }
    $badging = Invoke-CheckedCli $aapt @('dump', 'badging', $apk) 30
    if ($badging -notmatch "package: name='com.zhigou.yunxingban' versionCode='1' versionName='0.1.0'" -or
        $badging -notmatch "sdkVersion:'24'") { throw "Actual Android identity differs: $family" }
    $zip = [IO.Compression.ZipFile]::OpenRead($apk)
    try {
        $names = @($zip.Entries | ForEach-Object FullName)
        if (@($names | Sort-Object -Unique).Count -ne $names.Count -or
            @($names | Where-Object { $_ -match 'studio_compiler|^assets/autoscript-release/' }).Count -ne 0) { throw 'Invalid template contents' }
        $descriptorEntry = $zip.GetEntry('assets/packaging/runner-component.json')
        if ($null -eq $descriptorEntry -or $descriptorEntry.Length -gt 8192) { throw 'Missing bounded component descriptor' }
        $reader = [IO.StreamReader]::new($descriptorEntry.Open())
        try { $descriptor = $reader.ReadToEnd() | ConvertFrom-Json } finally { $reader.Dispose() }
        if ($descriptor.formatVersion -ne 1 -or $descriptor.kind -ne 'runner-template' -or $descriptor.componentVersion -ne $componentVersion -or
            $descriptor.runtimeProtocol -ne $runtimeProtocol -or $descriptor.runtimeApi -ne '1.8' -or $descriptor.family -ne $family -or
            $descriptor.minSdk -ne 24) { throw 'Descriptor compatibility mismatch' }
        $abis = if ($Profile -eq 'development') { @('arm64-v8a', 'x86_64') } else { @('arm64-v8a') }
        if (@(Compare-Object $abis @($descriptor.abis)).Count -ne 0) { throw 'Descriptor ABI mismatch' }
        $required = @('libengine_jni.so', 'libautoscript_root_daemon.so', 'libandroidx.graphics.path.so')
        $cv = $family -in @('opencv', 'full')
        $onnx = $family -in @('onnx', 'full')
        if ($cv) { $required += 'libc++_shared.so' }
        if ($cv) { $required += 'libopencv_java4.so' }
        if ($onnx) { $required += @('libonnxruntime.so', 'libonnxruntime4j_jni.so') }
        $expected = @($abis | ForEach-Object { $abi = $_; $required | ForEach-Object { "lib/$abi/$_" } })
        $actual = @($names | Where-Object { $_ -like 'lib/*' -and $_ -notlike '*/' })
        if (@(Compare-Object $expected $actual).Count -ne 0) { throw "Native library family mismatch: $family" }
        if ($cv) { $null = Hash-Entry $zip 'assets/vision/OpenCV-LICENSE.txt' 1048576 }
        $model = $zip.GetEntry('assets/vision/en_PP-OCRv4_rec_mobile.onnx')
        if (($null -ne $model) -ne $onnx) { throw 'Model presence mismatch' }
        if ($onnx) {
            if ($model.Length -ne 7653044 -or (Hash-Entry $zip $model.FullName 7653044) -ne
                'e8770c967605983d1570cdf5352041dfb68fa0c21664f49f47b155abd3e0e318') { throw 'Pinned model mismatch' }
            $null = Hash-Entry $zip 'assets/vision/ONNXRuntime-LICENSE.txt' 1048576
            $null = Hash-Entry $zip 'assets/vision/PaddleOCR-LICENSE.txt' 1048576
            $null = Hash-Entry $zip 'assets/vision/NOTICE.txt' 1048576
        }
        $report += [ordered]@{ family=$family; bytes=(Get-Item -LiteralPath $apk).Length;
            sha256=(Get-FileHash -LiteralPath $apk -Algorithm SHA256).Hash.ToLowerInvariant();
            publisher=$certificate; abis=$abis; nativeEntries=$actual; modelPresent=$onnx; status='passed' }
    } finally { $zip.Dispose() }
}
$outputDirectory = Join-Path $repo "build\component-audit\$Profile"
$null = New-Item -ItemType Directory -Path $outputDirectory -Force
$report | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath (Join-Path $outputDirectory 'runner-components.json') -Encoding UTF8
$report | ForEach-Object { "Runner component $($_.family): $($_.bytes) bytes; publisher/manifest/ABI/content verified" }
