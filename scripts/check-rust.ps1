[CmdletBinding()]
param([switch]$Offline)
$ErrorActionPreference = 'Stop'
$taskRepository = Split-Path -Parent $PSScriptRoot
Push-Location (Join-Path $taskRepository 'rust')
try {
    $taskCargoFlags = @()
    if ($Offline) { $taskCargoFlags += '--offline' }
    & cargo fmt --all --check
    if ($LASTEXITCODE -ne 0) { throw 'Rust formatting failed' }
    & cargo clippy --workspace --all-targets @taskCargoFlags -- -D warnings
    if ($LASTEXITCODE -ne 0) { throw 'Strict Rust Clippy failed' }
    & cargo test --workspace @taskCargoFlags
    if ($LASTEXITCODE -ne 0) { throw 'Rust workspace tests failed' }
    & cargo run @taskCargoFlags -p schema-check -- ..
    if ($LASTEXITCODE -ne 0) { throw 'Schema validation failed' }
    & cargo run @taskCargoFlags -p api-codegen -- check ..
    if ($LASTEXITCODE -ne 0) { throw 'Generated contracts are stale' }
} finally { Pop-Location }
