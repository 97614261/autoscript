package com.autoscript.studio

internal enum class ServerBackupUploadMode { REPLACE, NEW }

// Keep associations local and scoped to the server, key and exact project ID; names are not identities.
internal fun serverBackupAssociationKey(origin: String, key: String, projectId: String): String =
    "latest." + ServerBackupCrypto.sha("$origin\n$key\n$projectId".toByteArray(Charsets.UTF_8))

internal fun serverBackupUploadTarget(
    mode: ServerBackupUploadMode,
    selectedId: String?,
    records: List<ServerBackupRecord>,
    supportsReplacement: Boolean,
): ServerBackupRecord? {
    if (mode == ServerBackupUploadMode.NEW) return null
    check(supportsReplacement) { "BACKUP_REPLACE_UNSUPPORTED" }
    return records.singleOrNull { it.id == selectedId } ?: error("BACKUP_REPLACE_TARGET_REQUIRED")
}
