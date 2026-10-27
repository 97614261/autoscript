# Dot-source only. Verification output comes from a successful apksigner process.
function Get-DistributionCertificateDigest {
    param([Parameter(Mandatory)][string[]]$OutputLines)
    $lines = @($OutputLines | ForEach-Object { $_.Trim() })
    if (-not ($lines -match '^Verified using v2 scheme .*: true$') -or
        -not ($lines -contains 'Number of signers: 1')) {
        throw 'A verified APK v2 signature with exactly one signer is required'
    }
    # Build Tools 37 renamed the pre-37 "Signer #1" label to "V2 Signer:".
    $certificates = @($lines | Where-Object {
        $_ -match '^(?:Signer #1|V2 Signer:) certificate SHA-256 digest: [a-fA-F0-9]{64}$'
    })
    if ($certificates.Count -ne 1) { throw 'Expected exactly one APK v2 certificate digest' }
    return (($certificates[0] -split ' ')[-1]).ToLowerInvariant()
}

function Assert-DistributionBadging {
    param([Parameter(Mandatory)][string[]]$OutputLines)
    $lines = @($OutputLines | ForEach-Object { $_.Trim() })
    $minimum = @($lines | Where-Object { $_ -match '^(?:minSdkVersion|sdkVersion):' })
    if (-not ($lines -match "^package: name='com.zhigou.kaifaban' ") -or
        $minimum.Count -ne 1 -or $minimum[0] -notmatch "^(?:minSdkVersion|sdkVersion):'24'$" -or
        -not ($lines -contains "targetSdkVersion:'36'") -or ($lines -contains 'application-debuggable')) {
        throw 'Candidate identity/minSdk/targetSdk/non-debuggable manifest differs'
    }
}
