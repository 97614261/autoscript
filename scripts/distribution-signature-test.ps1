$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
. (Join-Path $PSScriptRoot 'distribution-signature.ps1')
$digest = '0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef'
$header = @('Verifies', 'Verified using v2 scheme (APK Signature Scheme v2): true', 'Number of signers: 1')
foreach ($prefix in @('Signer #1', 'V2 Signer:')) {
    $output = $header + "$prefix certificate SHA-256 digest: $digest"
    if ((Get-DistributionCertificateDigest $output) -ne $digest) { throw 'Certificate digest differs' }
    if ((Get-DistributionCertificateDigest ($output | ForEach-Object { "  $_`r" })) -ne $digest) {
        throw 'Native output whitespace must not change certificate identity'
    }
}
$badCases = @(
    ,@($header + "V2 Signer: public key SHA-256 digest: $digest"),
    ,@($header + "V2 Signer: certificate SHA-256 digest: truncated"),
    ,@($header + "V2 Signer: certificate SHA-256 digest: $digest" + "V2 Signer: certificate SHA-256 digest: $digest"),
    ,@('Verified using v2 scheme (APK Signature Scheme v2): false', 'Number of signers: 1', "V2 Signer: certificate SHA-256 digest: $digest"),
    ,@('Verified using v2 scheme (APK Signature Scheme v2): true', 'Number of signers: 2', "V2 Signer: certificate SHA-256 digest: $digest")
)
foreach ($bad in $badCases) {
    $refused = $false
    try { Get-DistributionCertificateDigest $bad | Out-Null } catch { $refused = $true }
    if (-not $refused) { throw 'Invalid or ambiguous signature output accepted' }
}
Write-Output 'PASS: 9 signature cases (old/new format, whitespace, malformed/duplicate/non-v2/multiple signers)'
$manifest = @("package: name='com.zhigou.kaifaban' versionCode='1'", "targetSdkVersion:'36'")
Assert-DistributionBadging ($manifest + "sdkVersion:'24'")
Assert-DistributionBadging ($manifest + "minSdkVersion:'24'")
foreach ($bad in @(
    ,@($manifest + "minSdkVersion:'25'"),
    ,@($manifest + "minSdkVersion:'24'" + 'application-debuggable'),
    ,@("package: name='other.app' versionCode='1'", "targetSdkVersion:'36'", "sdkVersion:'24'"),
    ,@($manifest + "minSdkVersion:'24'" + "sdkVersion:'24'")
)) {
    $refused = $false
    try { Assert-DistributionBadging $bad } catch { $refused = $true }
    if (-not $refused) { throw 'Invalid candidate manifest accepted' }
}
Write-Output 'PASS: 6 manifest cases (old/new minSdk label, wrong SDK/package, debuggable/ambiguous)'
