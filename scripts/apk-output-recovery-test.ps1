$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
. (Join-Path $PSScriptRoot 'apk-output-recovery.ps1')
$repo = [IO.Path]::GetFullPath((Split-Path -Parent $PSScriptRoot))
$testRoot = Join-Path $repo ('build/apk-output-test-'+[Guid]::NewGuid().ToString('N'))
[IO.Directory]::CreateDirectory($testRoot) | Out-Null
Add-Type -AssemblyName System.IO.Compression.FileSystem
Add-Type -AssemblyName System.IO.Compression
function Assert-True([bool]$value, [string]$message) { if(-not $value) { throw $message } }
$path = Join-Path $testRoot 'android/apps/studio-android/build/outputs/apk/debug/studio-android-debug.apk'
$phone = Join-Path $testRoot 'build/profiles/arm64/apps/studio-android/outputs/apk/debug/studio-android-debug.apk'
try {
    Repair-StudioApkOutput -ProjectRoot $testRoot
    Assert-True (-not (Test-Path -LiteralPath $path)) 'Missing APK must not create output'
    [IO.Directory]::CreateDirectory((Split-Path -Parent $path)) | Out-Null
    $zip = [IO.Compression.ZipFile]::Open($path, [IO.Compression.ZipArchiveMode]::Create)
    try {
        $entry=$zip.CreateEntry('classes.dex'); $stream=$entry.Open()
        try { $stream.WriteByte(1) } finally { $stream.Dispose() }
    } finally { $zip.Dispose() }
    $validHash=(Get-FileHash -LiteralPath $path).Hash
    Repair-StudioApkOutput -ProjectRoot $testRoot
    Assert-True ((Get-FileHash -LiteralPath $path).Hash -eq $validHash) 'Healthy APK must remain unchanged'
    $stream=[IO.File]::Open($path,[IO.FileMode]::Append)
    try { $padding=[byte[]]::new(8192); $stream.Write($padding,0,$padding.Length) } finally { $stream.Dispose() }
    [IO.Directory]::CreateDirectory((Split-Path -Parent $phone)) | Out-Null
    [IO.File]::Copy($path,$phone,$false)
    $phoneHash=(Get-FileHash -LiteralPath $phone).Hash
    $validationFailed=$false
    try { Repair-StudioApkOutput -ProjectRoot $testRoot -MaximumLayoutBytes 512 -ValidateOnly } catch { $validationFailed=$true }
    Assert-True ($validationFailed -and (Get-FileHash -LiteralPath $path).Hash -eq $phoneHash) 'Final validation must reject bloated output without changing bytes'
    Repair-StudioApkOutput -ProjectRoot $testRoot -MaximumLayoutBytes 512
    Assert-True (-not (Test-Path -LiteralPath $path)) 'Bloated APK output must be removed only after backup'
    $backup=@(Get-ChildItem -LiteralPath (Join-Path $testRoot 'build/apk-output-backups/development') -File)
    Assert-True ($backup.Count -eq 1 -and (Get-FileHash -LiteralPath $backup[0].FullName).Hash -eq $phoneHash) 'Backup must preserve exact bytes'
    Assert-True ((Get-FileHash -LiteralPath $phone).Hash -eq $phoneHash) 'Development refresh must not affect phone candidate'
    Repair-StudioApkOutput -ProjectRoot $testRoot -MaximumLayoutBytes 512
    # Backup exhaustion must retain the candidate and still fail the final install gate.
    $backupDirectory = Join-Path $testRoot 'build/apk-output-backups/development'
    [IO.File]::Copy($phone, (Join-Path $backupDirectory 'previous-second.apk'), $false)
    [IO.File]::Copy($phone, (Join-Path $backupDirectory 'previous-third.apk'), $false)
    [IO.File]::Copy($phone, $path, $false)
    Repair-StudioApkOutput -ProjectRoot $testRoot -MaximumLayoutBytes 512
    $blocked = $false
    try { Repair-StudioApkOutput -ProjectRoot $testRoot -MaximumLayoutBytes 512 -ValidateOnly } catch { $blocked = $true }
    Assert-True ($blocked -and (Get-FileHash -LiteralPath $path).Hash -eq $phoneHash) 'Exhausted backups must not bypass final validation or change the candidate'
    [IO.File]::WriteAllBytes($path,[byte[]](1,2,3))
    $failed=$false
    try { Repair-StudioApkOutput -ProjectRoot $testRoot } catch { $failed=$true }
    Assert-True ($failed -and (Test-Path -LiteralPath $path)) 'Invalid ZIP must be retained, not removed'
    Write-Output 'PASS: absent/healthy/inflated/invalid APK, exact backup, repeat request, isolated ABI output, exhausted-backup final gate'
} finally {
    $resolved=[IO.Path]::GetFullPath($testRoot)
    if(-not $resolved.StartsWith((Join-Path $repo 'build')+[IO.Path]::DirectorySeparatorChar) -or
        (Split-Path -Leaf $resolved) -notmatch '^apk-output-test-[a-f0-9]{32}$') { throw 'Unsafe fixture cleanup target' }
    Remove-Item -LiteralPath $resolved -Recurse -Force
}
