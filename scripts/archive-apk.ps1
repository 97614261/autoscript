[CmdletBinding()]
param(
    [string]$Sha256 = '',
    [string]$Revision = '',
    [string]$Config = '',
    [switch]$ConfirmArchive,
    [switch]$ListArchives
)
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
. (Join-Path $PSScriptRoot 'publish-protocol.ps1')
if (-not $Config) { $Config = Join-Path (Split-Path $PSScriptRoot -Parent) 'publish-apk.local.json' }
if ($ListArchives -and ($Sha256 -or $Revision -or $ConfirmArchive)) { throw 'LIST_ARCHIVES_CANNOT_MUTATE' }
if ($ConfirmArchive -and (-not $Sha256 -or $Revision -cnotmatch '^[a-f0-9]{64}$')) { throw 'CONFIRM_ARCHIVE_REQUIRES_EXACT_HASHES_AND_PREVIEW_REVISION' }
$selected = @()
if ($Sha256) {
    $selected = @($Sha256.Split(','))
    if ($selected.Count -gt 8 -or @($selected | Where-Object { $_ -cnotmatch '^[a-f0-9]{64}$' }).Count -gt 0 -or
        @($selected | Select-Object -Unique).Count -ne $selected.Count) { throw 'INVALID_ARCHIVE_HASHES; supply comma-separated full SHA-256 identities' }
}
$configuration = Read-PublishConfiguration $Config
$before = Invoke-SignedPublishApi -Endpoint $configuration.Endpoint -Token $configuration.Token
if ($before.revision -cnotmatch '^"[a-f0-9]{64}"$') { throw 'PUBLISH_REVISION_INVALID' }
if (-not $before.PSObject.Properties['capabilities'] -or 'archive-history-v1' -cnotin @($before.capabilities)) { throw 'PUBLISHER_ARCHIVE_UPGRADE_REQUIRED; no archive was requested' }
if ($ListArchives) {
    $inventory = Invoke-SignedPublishApi -Endpoint $configuration.Endpoint -Token $configuration.Token -Archives
    $inventory | ConvertTo-Json -Depth 8
    return
}
$history = @()
if ($before.release.PSObject.Properties['history']) { $history = @($before.release.history) }
$chosen = @($history | Where-Object { $_.sha256 -cin $selected })
if ($selected.Count -gt 0 -and ($chosen.Count -ne $selected.Count -or @($chosen.sha256 | Select-Object -Unique).Count -ne $selected.Count)) { throw 'ONLY_EXACT_HISTORY_IDENTITIES_CAN_BE_ARCHIVED; current packages are protected' }
Write-Host ('Preview revision: ' + $before.revision.Trim('"'))
foreach ($item in $(if ($selected.Count) { $chosen } else { $history })) {
    Write-Host ('{0} / {1}; {2}; {3:N2} MiB; SHA256 {4}' -f $item.versionName, $item.versionCode, ($item.abis -join ','), ([long]$item.bytes / 1MB), $item.sha256)
}
if (-not $ConfirmArchive) {
    Write-Host 'ARCHIVE_PREVIEW: read-only; repeat the selected full hashes and this revision with -ConfirmArchive only after approval'
    return
}
if ($before.revision -cne ('"' + $Revision + '"')) { throw 'REVISION_CONFLICT; refresh the preview and obtain confirmation again' }
$expected = ConvertFrom-PublishJson ($before.release | ConvertTo-Json -Depth 16 -Compress)
$expected.history = @($history | Where-Object { $_.sha256 -cnotin $selected })
$result = Invoke-SignedPublishApi -Endpoint $configuration.Endpoint -Token $configuration.Token -Archives -ArchiveSha256 $selected -Revision $before.revision
if (-not $result.ok -or -not $result.archive.complete -or $result.archive.archiveId -cnotmatch '^archive-[0-9]{8}T[0-9]{6}Z-[a-f0-9]{16}$' -or
    $result.archive.revisionBefore -cne $before.revision -or @($result.archive.sha256).Count -ne $selected.Count -or
    @($result.archive.sha256 | Where-Object { $_ -cnotin $selected }).Count -ne 0 -or
    ($result.release | ConvertTo-Json -Depth 16 -Compress) -cne ($expected | ConvertTo-Json -Depth 16 -Compress)) { throw 'ARCHIVE_RESULT_INVALID; inspect signed state before retrying' }
$after = Invoke-SignedPublishApi -Endpoint $configuration.Endpoint -Token $configuration.Token
if ($after.revision -cne $result.archive.revisionAfter -or ($after.release | ConvertTo-Json -Depth 16 -Compress) -cne ($expected | ConvertTo-Json -Depth 16 -Compress)) { throw 'ARCHIVE_RESULT_CHANGED; inspect signed state before retrying' }
Write-Host ('ARCHIVE_OK: recoverable private archive ' + $result.archive.archiveId + '; current packages and retained history are unchanged')
