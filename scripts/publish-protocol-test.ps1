[CmdletBinding()]
param([Parameter(Mandatory)][string]$Endpoint, [Parameter(Mandatory)][string]$Fixture,
    [ValidateSet('Publish', 'RejectTamper')][string]$Mode = 'Publish')
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
. (Join-Path $PSScriptRoot 'publish-protocol.ps1')
# Public synthetic test key only; never point this test at a real publishing service.
$testKey = 'a' * 64
if (([Uri]$Endpoint).Host -ne '127.0.0.1') { throw 'Tests only allow their own loopback server' }
foreach ($invalid in @('http://user:password@example.test/api/publisher/v1/releases',
    'http://example.test/api/publisher/v1/releases?token=x', 'http://example.test/api/publisher/v1/releases#x',
    'ftp://example.test/api/publisher/v1/releases', 'http://example.test/api/admin/releases')) {
    $rejected = $false
    try { [void](Get-PublishEndpoint $invalid) } catch { $rejected = $true }
    if (-not $rejected) { throw 'Unsafe endpoint accepted' }
}
if ($Mode -eq 'RejectTamper') {
    $rejected = $false
    try { [void](Invoke-SignedPublishApi -Endpoint $Endpoint -Token $testKey) }
    catch { if ($_.Exception.Message -like '*PUBLISH_RESPONSE_SIGNATURE_INVALID*') { $rejected = $true } else { throw } }
    if (-not $rejected) { throw 'Forged server response accepted' }
} else {
    $before = Invoke-SignedPublishApi -Endpoint $Endpoint -Token $testKey
    $inputFixture = ConvertFrom-PublishJson (Get-Content -LiteralPath $Fixture -Raw -Encoding UTF8)
    $envelope = @{ release = $inputFixture.release; alternative = $false; certificateSha256 = '8e48ea72d827f96ea57c6b7bf4d566bac7581fa23106324bf3296fa9e2024f6c' } | ConvertTo-Json -Depth 8 -Compress
    $header = [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes($envelope))
    $result = Invoke-SignedPublishApi -Endpoint $Endpoint -Token $testKey -File $inputFixture.apk -Metadata $header -Revision $before.revision -BodySha $inputFixture.release.sha256 -Length $inputFixture.release.bytes
    if (-not $result.ok) { throw 'Synthetic publication failed' }
    $after = Invoke-SignedPublishApi -Endpoint $Endpoint -Token $testKey
    if ($after.release.sha256 -cne $inputFixture.release.sha256) { throw 'Readback mismatch' }
    $repeat = Invoke-SignedPublishApi -Endpoint $Endpoint -Token $testKey -File $inputFixture.apk -Metadata $header -Revision $after.revision -BodySha $inputFixture.release.sha256 -Length $inputFixture.release.bytes
    if (-not $repeat.ok) { throw 'Idempotent publication failed' }
}
Write-Output 'PROTOCOL_OK: loopback synthetic fixture only'
