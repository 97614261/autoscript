package com.autoscript.runtime.service

import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream

internal object AndroidProjectDataFileOps : ProjectDataFileOps {
    override fun kind(file: File): ProjectDataKind? = try {
        val stat = Os.lstat(file.path)
        when {
            OsConstants.S_ISDIR(stat.st_mode) -> ProjectDataKind.DIRECTORY
            OsConstants.S_ISREG(stat.st_mode) && stat.st_nlink == 1L -> ProjectDataKind.FILE
            else -> ProjectDataKind.UNSAFE
        }
    } catch (error: ErrnoException) {
        if (error.errno == OsConstants.ENOENT) null else throw ProjectDataFailure("FILE_UNAVAILABLE")
    }

    override fun input(file: File): InputStream {
        val descriptor = Os.open(file.path, ProjectDataDescriptorFlags.closeOnExec(OsConstants.O_RDONLY or OsConstants.O_NOFOLLOW), 0)
        try {
            val stat = Os.fstat(descriptor)
            if (!OsConstants.S_ISREG(stat.st_mode) || stat.st_nlink != 1L) throw ProjectDataFailure("FILE_PATH_INVALID")
            return FileInputStream(descriptor)
        } catch (error: Throwable) { Os.close(descriptor); throw error }
    }

    override fun outputExclusive(file: File): OutputStream = FileOutputStream(
        Os.open(file.path, ProjectDataDescriptorFlags.closeOnExec(OsConstants.O_WRONLY or OsConstants.O_CREAT or OsConstants.O_EXCL or OsConstants.O_NOFOLLOW), 384),
    )

    override fun sync(output: OutputStream) { (output as FileOutputStream).fd.sync() }

    override fun moveAtomic(source: File, target: File) { Os.rename(source.path, target.path) }
    override fun withQuotaLock(directory: File, action: () -> Unit) {
        com.autoscript.runtime.api.RuntimeDataQuotaLock.withLock(directory, action)
    }
}
