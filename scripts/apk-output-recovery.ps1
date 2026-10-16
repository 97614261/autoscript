# Dot-source only. Never starts a device and never deletes a module/build directory.
function Repair-StudioApkOutput {
    param(
        [Parameter(Mandatory)][string]$ProjectRoot,
        [ValidateSet('development','arm64')][string]$Profile = 'development',
        [long]$MaximumLayoutBytes = 16MB,
        [switch]$ValidateOnly
    )
    if ($MaximumLayoutBytes -lt 1) { throw 'Invalid APK layout budget' }
    $root = [IO.Path]::GetFullPath($ProjectRoot)
    $relative = if ($Profile -eq 'arm64') {
        'build/profiles/arm64/apps/studio-android/outputs/apk/debug/studio-android-debug.apk'
    } else { 'android/apps/studio-android/build/outputs/apk/debug/studio-android-debug.apk' }
    $apk = [IO.Path]::GetFullPath((Join-Path $root $relative))
    if (-not $apk.StartsWith($root+[IO.Path]::DirectorySeparatorChar, [StringComparison]::OrdinalIgnoreCase)) { throw 'APK outside workspace' }
    if (-not (Test-Path -LiteralPath $apk -PathType Leaf)) { return }
    $cursor = $apk
    while ($cursor.Length -ge $root.Length) {
        if ((Get-Item -LiteralPath $cursor -Force).Attributes -band [IO.FileAttributes]::ReparsePoint) { throw 'Redirected APK path refused' }
        if ($cursor -eq $root) { break }
        $cursor = Split-Path -Parent $cursor
    }
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $zip = [IO.Compression.ZipFile]::OpenRead($apk)
    try {
        if ($zip.Entries.Count -gt 10000) { throw 'APK entry budget exceeded' }
        $payload = 0L
        foreach ($entry in $zip.Entries) {
            if ($entry.CompressedLength -lt 0) { throw 'Invalid ZIP payload size' }
            $payload += $entry.CompressedLength
            if ($payload -gt 1GB) { throw 'APK payload budget exceeded' }
        }
    } finally { $zip.Dispose() }
    $length = (Get-Item -LiteralPath $apk).Length
    if ($length -gt 1GB -or $payload -gt $length) { throw 'Invalid APK size' }
    if ($length - $payload -le $MaximumLayoutBytes) { return }
    if ($ValidateOnly) { throw "APK ZIP layout budget exceeded: $($length-$payload) bytes. Candidate is retained, not silently accepted." }
    $backupDirectory = Join-Path $root "build/apk-output-backups/$Profile"
    $cursor = $backupDirectory
    while ($cursor.Length -ge $root.Length) {
        if ((Test-Path -LiteralPath $cursor) -and
            ((Get-Item -LiteralPath $cursor -Force).Attributes -band [IO.FileAttributes]::ReparsePoint)) { throw 'Redirected backup path refused' }
        if ($cursor -eq $root) { break }
        $cursor = Split-Path -Parent $cursor
    }
    if ((Test-Path -LiteralPath $backupDirectory) -and
        @(Get-ChildItem -LiteralPath $backupDirectory -File -Filter 'previous-*.apk').Count -ge 3) {
        Write-Host 'Three previous APK outputs are retained; clear confirmed old backups before another automatic refresh.'
        return
    }
    [IO.Directory]::CreateDirectory($backupDirectory) | Out-Null
    $backup = Join-Path $backupDirectory ("previous-"+[Guid]::NewGuid().ToString('N')+".apk")
    if (Test-Path -LiteralPath $backup) { throw 'APK backup already exists' }
    $before = (Get-FileHash -LiteralPath $apk -Algorithm SHA256).Hash
    [IO.File]::Copy($apk, $backup, $false)
    if ((Get-FileHash -LiteralPath $backup -Algorithm SHA256).Hash -ne $before -or
        (Get-FileHash -LiteralPath $apk -Algorithm SHA256).Hash -ne $before) { throw 'APK changed while preparing rebuild' }
    Remove-Item -LiteralPath $apk
    Write-Host "APK ZIP layout overhead $($length-$payload) bytes; verified previous APK backed up; next Gradle build will recreate only the APK output."
}
