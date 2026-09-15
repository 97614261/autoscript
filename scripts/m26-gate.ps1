[CmdletBinding()]
param(
    [string]$ArtifactRoot = "",
    [switch]$SkipRustQuality
)

$ErrorActionPreference = "Stop"
Set-StrictMode -Version Latest

$repo = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
Set-Location $repo

if ([string]::IsNullOrWhiteSpace($ArtifactRoot)) {
    $stamp = Get-Date -Format "yyyyMMdd-HHmmss"
    $ArtifactRoot = Join-Path $repo "build\m26-$stamp"
}
$artifact = [System.IO.Path]::GetFullPath($ArtifactRoot)
$buildRoot = [System.IO.Path]::GetFullPath((Join-Path $repo "build"))
if (-not $artifact.StartsWith($buildRoot + [System.IO.Path]::DirectorySeparatorChar, [StringComparison]::OrdinalIgnoreCase)) {
    throw "ArtifactRoot必须位于 $buildRoot 内"
}
New-Item -ItemType Directory -Path $artifact -Force | Out-Null

$results = [System.Collections.Generic.List[object]]::new()
function Invoke-GateStep {
    param([string]$Name, [scriptblock]$Action)
    $watch = [Diagnostics.Stopwatch]::StartNew()
    Write-Host "`n[M26] $Name" -ForegroundColor Cyan
    try {
        & $Action
        if ($LASTEXITCODE -ne 0) { throw "exit code $LASTEXITCODE" }
        $watch.Stop()
        $results.Add([pscustomobject]@{ Step = $Name; Result = "PASS"; Seconds = [math]::Round($watch.Elapsed.TotalSeconds, 1) })
    } catch {
        $watch.Stop()
        $results.Add([pscustomobject]@{ Step = $Name; Result = "FAIL"; Seconds = [math]::Round($watch.Elapsed.TotalSeconds, 1) })
        $results | Format-Table -AutoSize
        throw
    }
}

function Copy-RunnerArtifact {
    param([string]$Name)
    $source = Join-Path $repo "apps\runner-template-android\build\outputs\apk\debug\runner-template-android-debug.apk"
    if (-not (Test-Path -LiteralPath $source -PathType Leaf)) { throw "Runner APK不存在：$source" }
    $target = Join-Path $artifact $Name
    Copy-Item -LiteralPath $source -Destination $target -Force
    return $target
}

if (-not $SkipRustQuality) {
    Invoke-GateStep "Rust fmt" { cargo fmt --all -- --check }
    Invoke-GateStep "Rust workspace tests" { cargo test --workspace }
    Invoke-GateStep "Rust clippy" { cargo clippy --workspace --all-targets -- -D warnings }
}
Invoke-GateStep "API generated artifacts" { cargo run -p api-codegen -- check . }
Invoke-GateStep "JSON Schema gate" { cargo run -p schema-check -- . }
Invoke-GateStep "Android unit tests and base APKs" {
    .\gradlew.bat testDebugUnitTest :apps:studio-android:assembleDebug :apps:runner-template-android:assembleDebug
}

$studioSource = Join-Path $repo "apps\studio-android\build\outputs\apk\debug\studio-android-debug.apk"
$studioApk = Join-Path $artifact "studio-debug.apk"
Copy-Item -LiteralPath $studioSource -Destination $studioApk -Force
$baseRunnerApk = Copy-RunnerArtifact "runner-template-debug.apk"
Invoke-GateStep "Studio package probe" { cargo run -p package-probe -- $studioApk }
Invoke-GateStep "Base Runner package probe" { cargo run -p package-probe -- $baseRunnerApk }

$helloRelease = Join-Path $artifact "release-hello"
Invoke-GateStep "Prepare Hello Runner release" {
    cargo run -p release-packager -- prepare examples/hello-project $helloRelease com.autoscript.hello 1 1.0.0
    cargo run -p release-packager -- verify $helloRelease
}
Invoke-GateStep "Build Hello Runner" {
    .\gradlew.bat :apps:runner-template-android:assembleDebug "-Pautoscript.releaseDir=$helloRelease"
}
$helloApk = Copy-RunnerArtifact "hello-runner-debug.apk"
Invoke-GateStep "Hello Runner embedded-release probe" {
    cargo run -p package-probe -- --require-embedded-release $helloApk
}

$visualRelease = Join-Path $artifact "release-visual"
Invoke-GateStep "Prepare visual source-map release" {
    cargo run -p release-packager -- prepare examples/visual-smoke-project $visualRelease com.autoscript.visualsmoke 1 1.0.0
    cargo run -p release-packager -- verify $visualRelease
}
Invoke-GateStep "Build visual Runner" {
    .\gradlew.bat :apps:runner-template-android:assembleDebug "-Pautoscript.releaseDir=$visualRelease"
}
$visualApk = Copy-RunnerArtifact "visual-runner-debug.apk"
Invoke-GateStep "Visual Runner embedded source-map probe" {
    cargo run -p package-probe -- --require-embedded-release $visualApk
}

$errorRelease = Join-Path $artifact "release-visual-error"
Invoke-GateStep "Prepare source-map error release" {
    cargo run -p release-packager -- prepare examples/visual-error-project $errorRelease com.autoscript.visualerror 1 1.0.0
    cargo run -p release-packager -- verify $errorRelease
}
Invoke-GateStep "Build source-map error Runner" {
    .\gradlew.bat :apps:runner-template-android:assembleDebug "-Pautoscript.releaseDir=$errorRelease"
}
$errorApk = Copy-RunnerArtifact "visual-error-runner-debug.apk"
Invoke-GateStep "Error Runner embedded source-map probe" {
    cargo run -p package-probe -- --require-embedded-release $errorApk
}

$report = [pscustomobject]@{
    generatedAt = (Get-Date).ToString("o")
    artifactRoot = $artifact
    apks = @(
        @{ kind = "studio"; path = $studioApk; sha256 = (Get-FileHash -Algorithm SHA256 $studioApk).Hash.ToLowerInvariant() },
        @{ kind = "runner-template"; path = $baseRunnerApk; sha256 = (Get-FileHash -Algorithm SHA256 $baseRunnerApk).Hash.ToLowerInvariant() },
        @{ kind = "hello-runner"; path = $helloApk; sha256 = (Get-FileHash -Algorithm SHA256 $helloApk).Hash.ToLowerInvariant() },
        @{ kind = "visual-runner"; path = $visualApk; sha256 = (Get-FileHash -Algorithm SHA256 $visualApk).Hash.ToLowerInvariant() },
        @{ kind = "visual-error-runner"; path = $errorApk; sha256 = (Get-FileHash -Algorithm SHA256 $errorApk).Hash.ToLowerInvariant() }
    )
    steps = @($results)
}
$reportPath = Join-Path $artifact "gate-report.json"
$report | ConvertTo-Json -Depth 6 | Set-Content -LiteralPath $reportPath -Encoding utf8
$results | Format-Table -AutoSize
Write-Host "`nM26 host gate PASS: $reportPath" -ForegroundColor Green
