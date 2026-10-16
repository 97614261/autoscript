[CmdletBinding()]
param(
    [ValidateRange(10, 1000)][int]$Iterations = 100,
    [string]$ArtifactPath = ""
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
Add-Type -AssemblyName System.IO.Compression.FileSystem
$repo = [IO.Path]::GetFullPath((Split-Path -Parent $PSScriptRoot))
if ([string]::IsNullOrWhiteSpace($ArtifactPath)) {
    $ArtifactPath = Join-Path $repo 'build\performance-2026-10-16-project-data'
}
$root = [IO.Path]::GetFullPath($ArtifactPath)
if (-not $root.StartsWith((Join-Path $repo 'build') + [IO.Path]::DirectorySeparatorChar, [StringComparison]::OrdinalIgnoreCase)) {
    throw 'ArtifactPath must remain under build/'
}
if (Test-Path -LiteralPath $root) { Remove-Item -LiteralPath $root -Recurse -Force }
New-Item -ItemType Directory -Force -Path $root | Out-Null
$data = Join-Path $root 'data'
New-Item -ItemType Directory -Force -Path $data | Out-Null
$payload = [byte[]]::new(256 * 1024)
for ($i = 0; $i -lt $payload.Length; $i++) { $payload[$i] = [byte]($i % 251) }
$source = Join-Path $data 'source.bin'
$append = Join-Path $data 'append.log'
$copy = Join-Path $data 'copy.bin'
$archive = Join-Path $root 'project.zip'
[IO.File]::WriteAllBytes($source, $payload)
[IO.File]::WriteAllText($append, "seed`n", [Text.Encoding]::UTF8)

function Measure-Operation([string]$Name, [scriptblock]$Operation) {
    $samples = [Collections.Generic.List[double]]::new()
    for ($i = 0; $i -lt $Iterations + 10; $i++) {
        $watch = [Diagnostics.Stopwatch]::StartNew()
        & $Operation
        $watch.Stop()
        if ($i -ge 10) { $samples.Add($watch.Elapsed.TotalMilliseconds) }
    }
    $ordered = @($samples | Sort-Object)
    [pscustomobject]@{
        operation = $Name
        iterations = $Iterations
        p50Ms = [math]::Round($ordered[[int](($ordered.Count - 1) * 0.50)], 4)
        p95Ms = [math]::Round($ordered[[int](($ordered.Count - 1) * 0.95)], 4)
        p99Ms = [math]::Round($ordered[[int](($ordered.Count - 1) * 0.99)], 4)
    }
}

$results = @(
    Measure-Operation 'write-256KiB' { [IO.File]::WriteAllBytes($source, $payload) }
    Measure-Operation 'read-256KiB' { [void][IO.File]::ReadAllBytes($source) }
    Measure-Operation 'append-4KiB' { [IO.File]::AppendAllText($append, ('x' * 4095) + "`n", [Text.Encoding]::UTF8) }
    Measure-Operation 'copy-256KiB' { [IO.File]::Copy($source, $copy, $true) }
    Measure-Operation 'zip-project' {
        if (Test-Path -LiteralPath $archive) { Remove-Item -LiteralPath $archive -Force }
        [IO.Compression.ZipFile]::CreateFromDirectory($data, $archive, [IO.Compression.CompressionLevel]::Fastest, $false)
    }
)
if (([IO.File]::ReadAllBytes($source)).Length -ne $payload.Length) { throw 'read/write validation failed' }
$results | ForEach-Object { "PERF_IO $($_ | ConvertTo-Json -Compress)" }
Write-Output "PASS: project-private IO benchmark; artifacts=$root"
