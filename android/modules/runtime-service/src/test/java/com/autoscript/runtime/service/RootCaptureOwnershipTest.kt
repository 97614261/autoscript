package com.autoscript.runtime.service

import org.junit.Assert.*
import org.junit.Test

class RootCaptureOwnershipTest {
    @Test fun successfulCopyRecyclesOnlyTheSourceAndClosesHardware() {
        val source = Any(); val result = Any(); val recycled = mutableListOf<Any>(); var closed = 0
        assertSame(result, ownedRootCaptureReadback({ source }, { result }, { recycled += it }, { closed++ }))
        assertEquals(listOf(source), recycled); assertEquals(1, closed)
    }
    @Test fun alreadySoftwareBitmapTransfersWithoutCopyOrPrematureRecycle() {
        val source = Any(); val recycled = mutableListOf<Any>(); var closed = 0
        assertSame(source, ownedRootCaptureReadback({ source }, { it }, { recycled += it }, { closed++ }))
        assertTrue(recycled.isEmpty()); assertEquals(1, closed)
    }
    @Test fun invalidGeometryOrCopyFailureStillReleasesSourceAndHardware() {
        val source = Any(); val recycled = mutableListOf<Any>(); var closed = 0
        val failure = IllegalArgumentException("synthetic geometry")
        val thrown = assertThrows(IllegalArgumentException::class.java) {
            ownedRootCaptureReadback({ source }, { throw failure }, { recycled += it }, { closed++ })
        }
        assertSame(failure, thrown); assertEquals(listOf(source), recycled); assertEquals(1, closed)
    }
    @Test fun hardwareCloseFailureAlsoRecyclesTheUnreturnedCopy() {
        val source = Any(); val result = Any(); val recycled = mutableListOf<Any>()
        assertThrows(IllegalStateException::class.java) {
            ownedRootCaptureReadback({ source }, { result }, { recycled += it }, { error("synthetic close") })
        }
        assertEquals(listOf(source, result), recycled)
    }
    @Test fun softwareResultIsRecycledExactlyOnceOnCloseFailure() {
        val source = Any(); var recycled = 0
        assertThrows(IllegalStateException::class.java) {
            ownedRootCaptureReadback({ source }, { it }, { assertSame(source, it); recycled++ }, { error("synthetic close") })
        }
        assertEquals(1, recycled)
    }
    @Test fun acquisitionFailureClosesHardwareAndCleanupFailureIsSuppressed() {
        val primary = IllegalArgumentException("synthetic acquire"); val cleanup = IllegalStateException("synthetic close")
        val thrown = assertThrows(IllegalArgumentException::class.java) {
            ownedRootCaptureReadback<Any>({ throw primary }, { it }, { fail("no bitmap acquired") }, { throw cleanup })
        }
        assertSame(primary, thrown); assertSame(cleanup, thrown.suppressed.single())
    }
    @Test fun sourceRecycleFailureStillClosesHardwareAndRecyclesCopy() {
        val source = Any(); val result = Any(); var closed = 0; val recycled = mutableListOf<Any>()
        assertThrows(IllegalStateException::class.java) {
            ownedRootCaptureReadback({ source }, { result }, { recycled += it; if (it === source) error("synthetic recycle") }, { closed++ })
        }
        assertEquals(listOf(source, result), recycled); assertEquals(1, closed)
    }
    @Test fun nullBitmapOrNullCopyDoesNotLeak() {
        var closed = 0; var recycled = 0
        assertNull(ownedRootCaptureReadback<Any>({ null }, { it }, { recycled++ }, { closed++ }))
        assertNull(ownedRootCaptureReadback({ Any() }, { null }, { recycled++ }, { closed++ }))
        assertEquals(1, recycled); assertEquals(2, closed)
    }
}
