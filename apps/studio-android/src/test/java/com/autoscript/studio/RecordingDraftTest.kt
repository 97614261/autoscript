package com.autoscript.studio

import com.autoscript.project.store.ProjectDesign
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.*
import org.junit.Test

class RecordingDraftTest {
    private fun wire(actions: List<RecordedAction>) = ByteBuffer.allocate(actions.size*32).order(ByteOrder.LITTLE_ENDIAN).apply {
        actions.forEach { action -> listOf(action.kind,action.startMs,action.durationMs,action.x1,action.y1,action.x2,action.y2,action.keyCode).forEach(::putInt) }
    }.array()
    private fun tap(start: Int, x: Int = 100, y: Int = 200) = RecordedAction(1,start,10,x,y,x,y,0)
    @Test fun tapLongSwipeKeyAndIntervalsProduceOnlyExistingTypedBlocks() {
        val actions=listOf(tap(100),RecordedAction(2,500,700,100,200,100,200,0),RecordedAction(3,1300,400,100,200,500,600,0),RecordedAction(4,2000,0,0,0,0,0,4))
        assertEquals(actions,decodeRecordedActions(wire(actions),1080,1920,2100))
        val snippet=recordedSnippet(actions,1080,1920,ProjectDesign(720,1280,"stretch"),true)
        assertNull(snippet.rejection)
        assertEquals(listOf("input.tap","task.sleep","input.swipe","task.sleep","input.swipe","task.sleep","input.keyevent"),snippet.flowBlocks.map { it.kind })
        assertEquals(67,snippet.flowBlocks.first().arguments["x"])
        assertEquals(4,recordedSnippet(actions,1080,1920,ProjectDesign(720,1280,"stretch"),false).flowBlocks.size)
    }
    @Test fun blackBarsAndOverlappingOrMalformedActionsRejectWholeDraft() {
        assertThrows(IllegalArgumentException::class.java) { recordedSnippet(listOf(tap(0,10,100)),1000,1000,ProjectDesign(500,1000,"letterbox"),false) }
        assertThrows(IllegalArgumentException::class.java) { decodeRecordedActions(wire(listOf(tap(50),tap(55))),1080,1920,100) }
        assertThrows(IllegalArgumentException::class.java) { decodeRecordedActions(ByteArray(33),1080,1920,100) }
        assertThrows(IllegalArgumentException::class.java) { decodeRecordedActions(wire(listOf(tap(0,1080,200))),1080,1920,100) }
    }
    @Test fun insertionHasExplicit256BlockBudgetWithoutTruncation() {
        val actions=(0..256).map { tap(it*20) }
        assertThrows(IllegalArgumentException::class.java) { recordedSnippet(actions,1080,1920,ProjectDesign(1080,1920,"stretch"),false) }
        assertEquals(256,recordedSnippet(actions.take(256),1080,1920,ProjectDesign(1080,1920,"stretch"),false).flowBlocks.size)
        assertThrows(IllegalArgumentException::class.java) { recordedSnippet(actions.take(200),1080,1920,ProjectDesign(1080,1920,"stretch"),true) }
    }
}
