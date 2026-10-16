[CmdletBinding()]
param()
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
$repo = [IO.Path]::GetFullPath((Split-Path -Parent $PSScriptRoot))
$documents = @(Get-ChildItem -LiteralPath (Join-Path $repo 'docs'),(Join-Path $repo 'archive') -Filter '*.md' -Recurse -File)
$documents += @(Get-ChildItem -LiteralPath $repo -Filter '*.md' -File)
$documents += @(Get-Item -LiteralPath (Join-Path $repo 'android/README.md'),(Join-Path $repo 'rust/README.md'),(Join-Path $repo 'docs-site/README.md'))
$failures = [Collections.Generic.List[string]]::new()
$checked = 0
$localOnly = 0
$androidRoot = Join-Path $repo 'android'
foreach ($name in @('settings.gradle.kts','build.gradle.kts','gradle.properties','gradle')) {
    if (-not (Test-Path -LiteralPath (Join-Path $androidRoot $name))) { $failures.Add("Missing Android configuration: android/$name") }
    if (Test-Path -LiteralPath (Join-Path $repo $name)) { $failures.Add("Android configuration outside workspace: $name") }
}
foreach ($name in @('apps','.gradle','.kotlin','local.properties')) {
    if (Test-Path -LiteralPath (Join-Path $repo $name)) { $failures.Add("Obsolete Android root path: $name") }
}
$androidModules = @('core-model','core-designsystem','brand-ui','script-ui','auth-api','auth-local','runtime-api','engine-jni','studio-compiler','runtime-client','runtime-service','project-store','vision-spi','vision-components')
foreach ($name in $androidModules) {
    if (-not (Test-Path -LiteralPath (Join-Path $androidRoot "modules/$name/build.gradle.kts") -PathType Leaf)) { $failures.Add("Missing Android module: $name") }
    if (Test-Path -LiteralPath (Join-Path $androidRoot $name)) { $failures.Add("Obsolete Android module location: $name") }
}
foreach ($name in @('studio-android','runner-template-android','vision-opencv-component','vision-onnx-component')) {
    if (-not (Test-Path -LiteralPath (Join-Path $androidRoot "apps/$name/build.gradle.kts") -PathType Leaf)) { $failures.Add("Missing Android App: $name") }
}
$rustRoot = Join-Path $repo 'rust'
foreach ($name in @('Cargo.toml', 'Cargo.lock', 'rust-toolchain.toml', 'rustfmt.toml')) {
    if (-not (Test-Path -LiteralPath (Join-Path $rustRoot $name) -PathType Leaf)) {
        $failures.Add("Missing Rust workspace configuration: rust/$name")
    }
    if (Test-Path -LiteralPath (Join-Path $repo $name)) {
        $failures.Add("Rust configuration outside workspace: $name")
    }
}
$rustMembers = @()
$manifest = Join-Path $rustRoot 'Cargo.toml'
if (Test-Path -LiteralPath $manifest -PathType Leaf) {
    $content = Get-Content -LiteralPath $manifest -Raw -Encoding UTF8
    $memberBlock = [regex]::Match($content, '(?s)\bmembers\s*=\s*\[([^\]]*)\]')
    $rustMembers = @([regex]::Matches($memberBlock.Groups[1].Value, '"([^"]+)"') | ForEach-Object { $_.Groups[1].Value })
    if ($rustMembers.Count -eq 0) { $failures.Add('Rust workspace requires explicit members') }
    foreach ($member in $rustMembers) {
        if ($member -notmatch '^(crates|tools)/[a-z0-9-]+$') {
            $failures.Add("Rust member outside explicit crates/tools layout: $member")
            continue
        }
        if (-not (Test-Path -LiteralPath (Join-Path $rustRoot "$member/Cargo.toml") -PathType Leaf)) {
            $failures.Add("Missing Rust member manifest: $member")
        }
        $oldMember = if ($member.StartsWith('crates/')) { "engine/$member" } else { $member }
        if (Test-Path -LiteralPath (Join-Path $repo $oldMember)) {
            $failures.Add("Obsolete Rust member location: $oldMember")
        }
    }
}
foreach ($document in $documents) {
    $content = Get-Content -LiteralPath $document.FullName -Raw -Encoding UTF8
    foreach ($match in [regex]::Matches($content, '\]\(\s*<?([^\)\r\n]+?)>?\)')) {
        $target = $match.Groups[1].Value.Trim().TrimEnd('>')
        if ($target -match '^(https?:|mailto:|#)' -or $target.Contains('"')) { continue }
        if ($target -match '^(?:[A-Za-z]:|/)' ) { $localOnly++; continue }
        $target = [Uri]::UnescapeDataString(($target -split '#',2)[0])
        $path = [IO.Path]::GetFullPath((Join-Path $document.DirectoryName $target))
        if (-not $path.StartsWith($repo+[IO.Path]::DirectorySeparatorChar, [StringComparison]::OrdinalIgnoreCase)) {
            $failures.Add("Outside workspace: $($document.Name) -> $target")
            continue
        }
        # Local evidence intentionally excluded from Git and therefore absent in CI clones.
        if ($path.StartsWith((Join-Path $repo 'archive\local-evidence')+[IO.Path]::DirectorySeparatorChar, [StringComparison]::OrdinalIgnoreCase)) {
            $localOnly++; continue
        }
        $checked++
        if (-not (Test-Path -LiteralPath $path)) { $failures.Add("Missing link: $($document.FullName.Substring($repo.Length+1)) -> $target") }
    }
}
$gradleFiles = @(Get-Item -LiteralPath (Join-Path $repo 'android/settings.gradle.kts'))
$gradleFiles += @(Get-ChildItem -LiteralPath (Join-Path $repo 'android') -Filter 'build.gradle.kts' -Recurse -File |
    Where-Object { $_.FullName -notmatch '\\build\\' })
foreach ($file in $gradleFiles) {
    $content = Get-Content -LiteralPath $file.FullName -Raw
    if ($content -match '(sourceSets|include|srcDir)[^\r\n]*["''](?:[^"'']*/)?archive[/\\]') {
        $failures.Add("Archive enters build: $($file.Name)")
    }
    if ($content -match '"cargo"' -and $content -notmatch 'workingDir\(rootProject\.file\("\.\./rust"\)\)') {
        $failures.Add("Cargo task must use rust working directory: $($file.FullName.Substring($repo.Length+1))")
    }
    if ($content -match 'rootProject\.(file|fileTree)\("(?:engine/crates|target/|Cargo\.(toml|lock)|tools/(api-codegen|flow-compiler|studio-compiler-jni|glyph-maker|schema-check|package-probe|release-packager))') {
        $failures.Add("Obsolete Rust path in Gradle: $($file.FullName.Substring($repo.Length+1))")
    }
}
if ($failures.Count) { $failures | ForEach-Object { Write-Output $_ }; throw "$($failures.Count) repository layout errors" }
Write-Output "Repository layout OK: $checked local links; $localOnly local-only references; $($rustMembers.Count) contained Rust members; archive excluded from build."
