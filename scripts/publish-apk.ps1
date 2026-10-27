[CmdletBinding()]
param(
    [string]$Apk = '',
    [string]$Description = '',
    [string]$Config = '',
    [switch]$Candidate,
    [switch]$Alternative,
    [switch]$CheckOnly,
    [switch]$CheckConnection
)
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
$repo = Split-Path $PSScriptRoot -Parent
. (Join-Path $PSScriptRoot 'distribution-signature.ps1')
. (Join-Path $PSScriptRoot 'publish-protocol.ps1')

$temporary = $null
if (-not $Config) { $Config = Join-Path $repo 'publish-apk.local.json' }
if ($CheckConnection) {
    if ($Apk -or $CheckOnly -or $Candidate -or $Alternative) { throw 'CHECK_CONNECTION_CANNOT_PUBLISH' }
    $configuration = Read-PublishConfiguration $Config
    $current = Invoke-SignedPublishApi -Endpoint $configuration.Endpoint -Token $configuration.Token
    if ($current.revision -cnotmatch '^"[a-f0-9]{64}"$') { throw 'PUBLISH_REVISION_INVALID' }
    Write-Host 'CONNECT_OK: signed server manifest verified; no APK uploaded or App installed'
    return
}
if (-not $Apk) { throw 'Specify -Apk or use -CheckConnection' }
try {
    $source = (Resolve-Path -LiteralPath $Apk).Path
    if ((Get-Item -LiteralPath $source).Length -gt 448MB) { throw 'APK_EXCEEDS_PUBLISH_BUDGET' }
    $temporary = Join-Path ([IO.Path]::GetTempPath()) ('zhigou-publish-' + [Guid]::NewGuid().ToString('N'))
    New-Item -ItemType Directory -Path $temporary | Out-Null
    $frozen = Join-Path $temporary ([IO.Path]::GetFileName($source))
    Copy-Item -LiteralPath $source -Destination $frozen
    Copy-Item -LiteralPath (Join-Path (Split-Path $source -Parent) 'output-metadata.json') -Destination (Join-Path $temporary 'output-metadata.json')
    $sdkLine = Get-Content -LiteralPath (Join-Path $repo 'android/local.properties') | Where-Object { $_ -match '^sdk\.dir=' } | Select-Object -First 1
    if (-not $sdkLine) { throw 'Configure sdk.dir in android/local.properties first' }
    $sdk = ($sdkLine -replace '^sdk\.dir=', '').Replace('\:', ':').Replace('\\', '\')
    $tools = Get-ChildItem -LiteralPath (Join-Path $sdk 'build-tools') -Directory | Where-Object {
        (Test-Path -LiteralPath (Join-Path $_.FullName 'lib/apksigner.jar')) -and (Test-Path -LiteralPath (Join-Path $_.FullName 'aapt2.exe'))
    } | Sort-Object Name -Descending | Select-Object -First 1
    if (-not $tools) { throw 'Android apksigner and aapt2 are required' }
    $signature = @(& java -jar (Join-Path $tools.FullName 'lib/apksigner.jar') verify --verbose --print-certs $frozen 2>&1)
    if ($LASTEXITCODE -ne 0) { throw 'APK_SIGNATURE_INVALID' }
    $certificate = Get-DistributionCertificateDigest -OutputLines $signature
    if ($certificate -cne '8e48ea72d827f96ea57c6b7bf4d566bac7581fa23106324bf3296fa9e2024f6c') { throw 'APK_CERTIFICATE_MISMATCH' }
    $badging = @(& (Join-Path $tools.FullName 'aapt2.exe') dump badging $frozen 2>&1)
    if ($LASTEXITCODE -ne 0) { throw 'APK_MANIFEST_INVALID' }
    $descriptionFile = Join-Path $temporary 'description.txt'
    if ($Description) { [IO.File]::WriteAllText($descriptionFile, $Description, (New-Object Text.UTF8Encoding $false)) }
    $nodeArgs = @((Join-Path $repo 'docs-site/scripts/publish-metadata.mjs'), '--apk', $frozen)
    if ($Description) { $nodeArgs += @('--descriptionFile', $descriptionFile) }
    if ($Candidate) { $nodeArgs += '--candidate' }
    $metadataOutput = @(& node @nodeArgs 2>&1)
    if ($LASTEXITCODE -ne 0) { throw 'APK_METADATA_OR_PROFILE_INVALID' }
    $release = ConvertFrom-PublishJson ($metadataOutput -join "`n")
    $package = @($badging | Where-Object { $_ -match '^package: ' })
    if ($package.Count -ne 1 -or $package[0] -notmatch "^package: name='com.zhigou.kaifaban' versionCode='([0-9]+)' versionName='([^']+)'" -or
        [int]$Matches[1] -ne $release.versionCode -or $Matches[2] -cne $release.versionName -or
        -not ($badging -match "^(?:minSdkVersion|sdkVersion):'24'$")) { throw 'ACTUAL_APK_IDENTITY_MISMATCH' }
    if ($Candidate) { Assert-DistributionBadging -OutputLines $badging }
    Write-Host ('Verified Studio {0} / {1}; {2}; SHA-256 {3}' -f $release.versionName, $release.versionCode, ($release.abis -join ','), $release.sha256)
    if ($CheckOnly) { Write-Host 'CHECK_OK: no connection, upload or publication performed'; return }
    $configuration = Read-PublishConfiguration $Config
    $endpoint, $token = $configuration.Endpoint, $configuration.Token
    $before = Invoke-SignedPublishApi -Endpoint $endpoint -Token $token
    if ($before.revision -cnotmatch '^"[a-f0-9]{64}"$') { throw 'PUBLISH_REVISION_INVALID' }
    $known = @()
    if ($before.release.available) {
        $known = @($before.release)
        foreach ($collection in @('alternatives', 'history')) {
            if ($before.release.PSObject.Properties[$collection]) { $known += @($before.release.$collection) }
        }
    }
    if (-not @($known | Where-Object { $_.url -ceq $release.url }).Count) { $known += @($release) }
    $downloadBytes = 0L
    foreach ($item in $known) { $downloadBytes += [long]$item.bytes }
    if ($known.Count -gt 8 -or $downloadBytes -gt 448MB) { throw 'HISTORY_BUDGET_EXCEEDED; preview and explicitly confirm selected history with archive-apk.cmd first; no APK upload was attempted' }
    $envelope = @{ release = $release; alternative = [bool]$Alternative; certificateSha256 = $certificate } | ConvertTo-Json -Depth 8 -Compress
    $header = [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes($envelope))
    $result = Invoke-SignedPublishApi -Endpoint $endpoint -Token $token -File $frozen -Metadata $header -Revision $before.revision -BodySha $release.sha256 -Length $release.bytes
    if (-not $result.ok) { throw 'PUBLISH_RESULT_INVALID' }
    $after = Invoke-SignedPublishApi -Endpoint $endpoint -Token $token
    $active = @($after.release)
    if ($after.release.PSObject.Properties['alternatives']) { $active += @($after.release.alternatives) }
    if (-not ($active | Where-Object { $_.sha256 -ceq $release.sha256 })) { throw 'PUBLISH_RESULT_CHANGED; inspect active release before retrying' }
    Write-Host 'PUBLISH_OK: server manifest confirms this APK; no App installation was performed'
} finally {
    if ($temporary) {
        $resolvedTemporary = [IO.Path]::GetFullPath($temporary)
        $expectedParent = [IO.Path]::GetFullPath([IO.Path]::GetTempPath()).TrimEnd('\')
        if ([IO.Path]::GetDirectoryName($resolvedTemporary) -ceq $expectedParent -and [IO.Path]::GetFileName($resolvedTemporary) -cmatch '^zhigou-publish-[a-f0-9]{32}$') {
            Remove-Item -LiteralPath $resolvedTemporary -Recurse -Force
        }
    }
}
