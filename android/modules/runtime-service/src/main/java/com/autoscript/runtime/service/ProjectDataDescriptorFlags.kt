package com.autoscript.runtime.service

/** Atomic open-time protection; do not replace with open followed by a racy fcntl call. */
internal object ProjectDataDescriptorFlags {
    // Linux/Bionic O_CLOEXEC = 02000000 (octal), shared by arm64-v8a and x86_64.
    // Checked against NDK 30 sysroot asm-generic/fcntl.h and both ABI asm/fcntl.h.
    // The kernel flag is supported on API24; only the public OsConstants field needs API27.
    // Keep this ABI constant rather than reading that newer Java field on Android 7.
    internal const val CLOSE_ON_EXEC = 0x80000

    fun closeOnExec(flags: Int): Int = flags or CLOSE_ON_EXEC
}
