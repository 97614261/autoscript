package com.autoscript.runtime.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class RuntimePumpWakeTest {
    @Test fun serviceUsesProtectedOrderWithoutDeletingCompletionsAfterPump() {
        val body = File("src/main/java/com/autoscript/runtime/service/AutomationRuntimeService.kt").readText()
            .substringAfter("private val pump = object").substringBefore("private val stopStateObserver")
        assertTrue(body.contains("runCoalescedRuntimePump({ engineHandler.removeCallbacks(this) })"))
        assertTrue(body.indexOf("removeCallbacks(this)") < body.indexOf("nativePump(handle, now)"))
        assertTrue(!body.substringAfter("nativePump(handle, now)").contains("removeCallbacks"))
    }

    @Test fun completionWakeDuringDispatchSurvivesAnIdleNativeSnapshot() {
        val queued = mutableListOf("old-wake", "old-deadline")
        runCoalescedRuntimePump(queued::clear) {
            assertTrue(queued.isEmpty())
            queued += "host-completed"
            // The preceding native pump can still report host-wait/idle here.
        }
        assertEquals(listOf("host-completed"), queued)
    }

    @Test fun nextDispatchCoalescesOldCopiesButRetainsNewNotifications() {
        val queued = mutableListOf("host-completed", "deadline")
        repeat(3) {
            runCoalescedRuntimePump(queued::clear) {
                assertTrue(queued.isEmpty())
                queued += "new-host-completed"
                queued += "new-deadline"
            }
            assertEquals(listOf("new-host-completed", "new-deadline"), queued)
        }
    }

    @Test fun idleDispatchDoesNotIntroducePolling() {
        val queued = mutableListOf("old-wake")
        runCoalescedRuntimePump(queued::clear) { }
        assertTrue(queued.isEmpty())
    }
}
