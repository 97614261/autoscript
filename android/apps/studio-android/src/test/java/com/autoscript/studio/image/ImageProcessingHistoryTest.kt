package com.autoscript.studio

import org.junit.Assert.*
import org.junit.Test

class ImageProcessingHistoryTest {
    private class Image(val size: Long)

    @Test fun boundedHistoryKeepsOriginalAndReleasesOnlyOwnedEvictions() {
        val original = Image(10)
        val released = mutableListOf<Image>()
        val history = ImageProcessingHistory(original, { it.size }, { released.add(it) }, 8, 40)
        val values = List(9) { Image(10) }
        values.forEach { assertTrue(history.publish(it, history.revision)) }
        assertSame(values.last(), history.current)
        assertEquals(3, history.editCount)
        repeat(2) { assertTrue(history.undo()) }
        assertSame(values[6], history.current)
        assertSame(original, history.original)
        assertFalse(history.undo())
        history.close(); history.close()
        assertEquals(9, released.size)
        assertEquals(9, released.toSet().size)
        assertFalse(original in released)
    }

    @Test fun applyAfterUndoDropsRedoAndStaleResultsCannotReplaceCurrent() {
        val original = Image(1)
        val released = mutableListOf<Image>()
        val history = ImageProcessingHistory(original, { it.size }, { released.add(it) })
        val a = Image(1); val b = Image(1); val c = Image(1)
        history.publish(a, history.revision); history.publish(b, history.revision)
        val staleRevision = history.revision
        history.undo()
        assertFalse(history.publish(c, staleRevision))
        assertSame(a, history.current)
        assertTrue(history.canRedo)
        val d = Image(1)
        history.publish(d, history.revision)
        assertFalse(history.canRedo)
        assertTrue(b in released && c in released)
        history.close()
        val late = Image(1)
        assertFalse(history.publish(late, history.revision))
        assertTrue(late in released)
    }

    @Test fun invalidResultLeavesCurrentHistoryUntouched() {
        val original = Image(10)
        val released = mutableListOf<Image>()
        val history = ImageProcessingHistory(original, { it.size }, { released.add(it) }, 8, 20)
        val tooLarge = Image(11)
        assertThrows(IllegalArgumentException::class.java) { history.publish(tooLarge, 0) }
        assertSame(original, history.current)
        assertEquals(0L, history.revision)
        assertEquals(listOf(tooLarge), released)
        assertThrows(IllegalArgumentException::class.java) { history.validateResultBytes(Long.MAX_VALUE) }
    }
}
