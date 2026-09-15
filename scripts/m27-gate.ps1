[CmdletBinding()]
param(
    [string]$ArtifactRoot = "",
    [switch]$SkipGradle
)

$ErrorActionPreference = "Stop"
Set-StrictMode -Version Latest
$repo = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
if ([string]::IsNullOrWhiteSpace($ArtifactRoot)) {
    $ArtifactRoot = Join-Path $repo ("build\m27-" + (Get-Date -Format "yyyyMMdd-HHmmss"))
}
$artifact = [IO.Path]::GetFullPath($ArtifactRoot)
[IO.Directory]::CreateDirectory($artifact) | Out-Null
$results = [System.Collections.Generic.List[object]]::new()

function Invoke-GateStep {
    param([string]$Name, [scriptblock]$Action)
    $watch = [Diagnostics.Stopwatch]::StartNew()
    Write-Host "`n[M27] $Name" -ForegroundColor Cyan
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

Push-Location $repo
try {
    if (-not $SkipGradle) {
        Invoke-GateStep "Studio and Runner tests and APKs" {
            & .\gradlew.bat `
                :apps:studio-android:testDebugUnitTest `
                :apps:studio-android:assembleDebug `
                :apps:runner-template-android:testDebugUnitTest `
                :apps:runner-template-android:assembleDebug `
                --console=plain
            if ($LASTEXITCODE -ne 0) { throw "Gradle门禁失败" }
        }
    }

    Invoke-GateStep "Diff whitespace" {
        & git diff --check
        if ($LASTEXITCODE -ne 0) { throw "git diff --check失败" }
    }

    Invoke-GateStep "Collect APKs" {
        $apks = @{
            "studio-debug.apk" = "apps\studio-android\build\outputs\apk\debug\studio-android-debug.apk"
            "runner-template-debug.apk" = "apps\runner-template-android\build\outputs\apk\debug\runner-template-android-debug.apk"
        }
        foreach ($entry in $apks.GetEnumerator()) {
            $source = Join-Path $repo $entry.Value
            if (-not (Test-Path -LiteralPath $source -PathType Leaf)) { throw "APK不存在：$source" }
            Copy-Item -LiteralPath $source -Destination (Join-Path $artifact $entry.Key) -Force
        }
    }

    $files = Get-ChildItem -LiteralPath $artifact -File -Filter "*.apk" | Sort-Object Name | ForEach-Object {
        [pscustomobject]@{
            name = $_.Name
            bytes = $_.Length
            sha256 = (Get-FileHash -LiteralPath $_.FullName -Algorithm SHA256).Hash.ToLowerInvariant()
        }
    }
    $reportPath = Join-Path $artifact "m27-host-report.json"
    [pscustomobject]@{
        generatedAt = (Get-Date).ToString("o")
        artifactRoot = $artifact
        steps = @($results)
        artifacts = @($files)
    } | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath $reportPath -Encoding utf8
    $results | Format-Table -AutoSize
    Write-Host "`nM27 host gate PASS: $reportPath" -ForegroundColor Green
} finally {
    Pop-Location
}
