[CmdletBinding()]
param(
    [ValidateSet('arm64', 'x86_64', 'development')][string]$Profile = 'arm64',
    [switch]$Verify,
    [switch]$SkipBuild
)
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
. (Join-Path $PSScriptRoot 'distribution-signature.ps1')
if ($SkipBuild -and $Verify) { throw '-Verify runs tests and cannot be combined with -SkipBuild' }
$repo = Split-Path -Parent $PSScriptRoot
$profileRoot = Join-Path $repo "build/profiles/distribution/$Profile"
$cursor = $profileRoot
while ($cursor.Length -ge $repo.Length) {
    if ((Test-Path -LiteralPath $cursor) -and
        ((Get-Item -LiteralPath $cursor -Force).Attributes -band [IO.FileAttributes]::ReparsePoint)) {
        throw 'Redirected distribution output refused'
    }
    if ($cursor -eq $repo) { break }
    $cursor = Split-Path -Parent $cursor
}
$properties = @("-Pautoscript.abiProfile=$Profile", '-Pautoscript.nativeProfile=distribution',
    '-Pautoscript.signDevelopmentRelease=true', '-Pautoscript.embedRunnerTemplate=false',
    '--project-cache-dir', (Join-Path $profileRoot '.gradle'), '--offline', '--console=plain')
$tasks = @()
if ($Verify) {
    $tasks += @(':apps:studio-android:testReleaseUnitTest', ':android:script-ui:testReleaseUnitTest',
        ':android:project-store:testReleaseUnitTest', ':android:runtime-service:testReleaseUnitTest',
        ':android:runtime-client:testReleaseUnitTest', ':android:brand-ui:testReleaseUnitTest',
        ':android:studio-compiler:testReleaseUnitTest',
        ':android:vision-spi:testReleaseUnitTest', ':android:vision-components:testReleaseUnitTest',
        ':apps:studio-android:lintRelease')
}
$tasks += ':apps:studio-android:assembleRelease'
Push-Location -LiteralPath $repo
try {
    if (-not $SkipBuild) {
        & (Join-Path $repo 'gradlew.bat') @tasks @properties
        if ($LASTEXITCODE -ne 0) { throw 'Distribution candidate build failed; no device or website was modified.' }
        # Recreate the final ZIP, without cleaning modules or touching previous packages.
        & (Join-Path $repo 'gradlew.bat') ':apps:studio-android:packageRelease' '--rerun' @properties
        if ($LASTEXITCODE -ne 0) { throw 'Compact Release packaging failed.' }
    }
    & (Join-Path $PSScriptRoot 'verify-native-packaging.ps1') -Profile $Profile -Distribution
    if (-not $?) { throw 'Native/model verification failed.' }

    $sdkLine = Get-Content -LiteralPath (Join-Path $repo 'android/local.properties') |
        Where-Object { $_ -match '^sdk\.dir=' } | Select-Object -First 1
    if (-not $sdkLine) { throw 'android/local.properties must configure sdk.dir' }
    $sdk = $sdkLine.Substring(8).Replace('\\', '\').Replace('\:', ':')
    $tools = Get-ChildItem -LiteralPath (Join-Path $sdk 'build-tools') -Directory |
        Where-Object { $_.Name -match '^\d+\.\d+\.\d+$' -and
            (Test-Path -LiteralPath (Join-Path $_.FullName 'lib/apksigner.jar')) -and
            (Test-Path -LiteralPath (Join-Path $_.FullName 'aapt2.exe')) -and
            (Test-Path -LiteralPath (Join-Path $_.FullName 'zipalign.exe')) } |
        Sort-Object { [Version]$_.Name } -Descending | Select-Object -First 1
    if (-not $tools) { throw 'Complete Android Build Tools are required' }
    $apk = Join-Path $profileRoot 'apps/studio-android/outputs/apk/release/studio-android-release.apk'
    & (Join-Path $tools.FullName 'zipalign.exe') -c -P 16 4 $apk
    if ($LASTEXITCODE -ne 0) { throw 'APK native alignment verification failed' }
    $signature = @(& java -jar (Join-Path $tools.FullName 'lib/apksigner.jar') verify --verbose --print-certs $apk)
    if ($LASTEXITCODE -ne 0) { throw 'APK signature verification failed' }
    $certificateDigest = Get-DistributionCertificateDigest $signature
    $signature | Set-Content -LiteralPath (Join-Path $profileRoot 'signature.txt') -Encoding UTF8
    $badging = @(& (Join-Path $tools.FullName 'aapt2.exe') dump badging $apk)
    if ($LASTEXITCODE -ne 0) { throw 'Candidate manifest inspection failed' }
    Assert-DistributionBadging $badging
    $badging | Set-Content -LiteralPath (Join-Path $profileRoot 'badging.txt') -Encoding UTF8
    $abis = switch ($Profile) { 'arm64' { 'arm64-v8a' } 'x86_64' { 'x86_64' } default { 'arm64-v8a,x86_64' } }
    $baseline = Join-Path $repo 'android/apps/studio-android/build/outputs/apk/debug/studio-android-debug.apk'
    $reportArgs = @((Join-Path $PSScriptRoot 'apk-size-report.py'), '--apk', $apk, '--abis', $abis,
        '--output', (Join-Path $profileRoot 'size-report.json'),
        '--mapping', (Join-Path $profileRoot 'apps/studio-android/outputs/mapping/release/mapping.txt'),
        '--seeds', (Join-Path $profileRoot 'apps/studio-android/outputs/mapping/release/seeds.txt'))
    if (Test-Path -LiteralPath $baseline -PathType Leaf) {
        $oldSignature = @(& java -jar (Join-Path $tools.FullName 'lib/apksigner.jar') verify --verbose --print-certs $baseline)
        if ($LASTEXITCODE -ne 0 -or (Get-DistributionCertificateDigest $oldSignature) -ne $certificateDigest) {
            throw 'Candidate signer differs from the retained development APK'
        }
        $reportArgs += @('--baseline', $baseline)
    }
    & python @reportArgs
    if ($LASTEXITCODE -ne 0) { throw 'Distribution size/inventory gate failed' }
    Write-Host "Development-signed Release candidate: $apk"
    Write-Host 'No installation, production signing, website update or project-data change was performed.'
} finally { Pop-Location }
