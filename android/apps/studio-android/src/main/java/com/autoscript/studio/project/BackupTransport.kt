package com.autoscript.studio

import java.io.Closeable
import java.io.File

internal interface BackupTransport : Closeable {
    val supportsReplacement: Boolean
    fun check()
    fun list(): List<ServerBackupRecord>
    fun upload(file: File, id: String, label: String, replacing: ServerBackupRecord? = null): ServerBackupRecord
    fun download(record: ServerBackupRecord, file: File)
    fun delete(record: ServerBackupRecord)
}
