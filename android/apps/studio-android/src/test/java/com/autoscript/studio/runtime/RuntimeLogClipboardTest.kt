package com.autoscript.studio

import org.junit.Assert.*
import org.junit.Test

class RuntimeLogClipboardTest {
    @Test fun exactUtf8ContentIncludingLineBreaksIsVerifiedWithoutLoggingIt() {
        val report = "QA报告\nP50 1.00 → 0.80\n"
        var stored: String? = null
        assertEquals("已复制并校验完整报告", runtimeLogClipboardStatus(report, { stored = it }, { stored }))
        assertEquals(report, stored)
    }
    @Test fun truncationOrReplacementIsNotClaimedSuccessful() {
        val status = runtimeLogClipboardStatus("private-output", {}, { "private" })
        assertTrue(status.contains("不一致"))
        assertFalse(status.contains("private"))
    }
    @Test fun backgroundReadRestrictionIsExplicitInsteadOfInventingVerification() {
        assertTrue(runtimeLogClipboardStatus("QA", {}, { null }).contains("未允许回读"))
    }
    @Test fun writingOrReadingFailureDoesNotEscapeTheDialog() {
        assertTrue(runtimeLogClipboardStatus("QA", { error("synthetic") }, { "QA" }).contains("失败"))
        assertTrue(runtimeLogClipboardStatus("QA", {}, { error("synthetic") }).contains("失败"))
    }
}
