package com.autoscript.studio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StudioUpdatePromptTest {
    private val idle = StudioUpdateState(source = "", automatic = true)

    @Test fun actionsDescribeTheCurrentStage() {
        assertEquals("下载更新", studioUpdateActionLabel(idle))
        assertEquals("下载中…", studioUpdateActionLabel(idle.copy(downloading = true)))
        assertEquals("校验中…", studioUpdateActionLabel(idle.copy(verifying = true)))
        assertEquals("去授权", studioUpdateActionLabel(idle.copy(ready = true, needsPermission = true)))
        assertEquals("确认安装", studioUpdateActionLabel(idle.copy(ready = true)))
    }

    @Test fun verificationRemainsBusyAndTakesPriorityOverReadyActions() {
        val verifying = idle.copy(downloading = false, verifying = true, ready = true)
        assertTrue(verifying.busy)
        assertEquals("校验中…", studioUpdateActionLabel(verifying))
    }
}
