package com.autoscript.runtime.api

import android.system.Os
import android.system.OsConstants
import java.io.File
import java.io.FileOutputStream

/** Immediate cross-process admission; no indefinite wait behind file writers. */
object RuntimeDataQuotaLock {
    fun <T> withLock(projectDirectory: File, action: () -> T): T {
        require(projectDirectory.canonicalFile == projectDirectory.absoluteFile)
        val path = File(projectDirectory, ".data-quota.lock")
        // Linux/Bionic O_CLOEXEC is supported on API24; its Java constant is API27.
        val fd = Os.open(path.path, OsConstants.O_CREAT or OsConstants.O_RDWR or OsConstants.O_NOFOLLOW or 0x80000, 384)
        FileOutputStream(fd).use { stream ->
            val stat = Os.fstat(fd)
            require(OsConstants.S_ISREG(stat.st_mode) && stat.st_nlink == 1L)
            val lock = stream.channel.tryLock() ?: error("FILE_BUSY")
            lock.use { return action() }
        }
    }
}
