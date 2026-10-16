package com.autoscript.runtime.service

import org.junit.Assert.assertEquals
import org.junit.Test

class ProjectDataDescriptorFlagsTest {
    @Test fun closeOnExecMatchesBothSupportedNdkLinuxAbis() {
        assertEquals("2000000".toInt(8), ProjectDataDescriptorFlags.CLOSE_ON_EXEC)
        assertEquals(0x80000, ProjectDataDescriptorFlags.closeOnExec(0))
    }

    @Test fun readAndExclusiveWriteKeepNoFollowAndAllOriginalFlagsForBothAbis() {
        // arm64 overrides O_NOFOLLOW=0100000; x86_64 uses generic 00400000.
        for (noFollow in listOf("100000".toInt(8), "400000".toInt(8))) {
            for (base in listOf(noFollow, 1 or "100".toInt(8) or "200".toInt(8) or noFollow)) {
                val guardedFlags = ProjectDataDescriptorFlags.closeOnExec(base)
                assertEquals(base, guardedFlags and ProjectDataDescriptorFlags.CLOSE_ON_EXEC.inv())
                assertEquals(ProjectDataDescriptorFlags.CLOSE_ON_EXEC, guardedFlags and ProjectDataDescriptorFlags.CLOSE_ON_EXEC)
                assertEquals(noFollow, guardedFlags and noFollow)
            }
        }
    }

    @Test fun protectionIsIdempotentAndDoesNotModifyOtherOpenFlags() {
        val flags = 0x13579
        val guardedFlags = ProjectDataDescriptorFlags.closeOnExec(flags)
        assertEquals(guardedFlags, ProjectDataDescriptorFlags.closeOnExec(guardedFlags))
        assertEquals(flags, guardedFlags and ProjectDataDescriptorFlags.CLOSE_ON_EXEC.inv())
    }
}
