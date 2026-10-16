package com.autoscript.runtime.service

import java.nio.ByteBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.*
import org.junit.Test

class RuntimeVisionResolverTest {
    @Test fun preparationPublishesChangedSelectionAndBlocksAlreadyCachedBackend() {
        val selected = AtomicReference<String?>("selected")
        RuntimeVisionResolver(emptySet(), { _, _ -> VisionResolvedBackend("selected", Backend()) },
            { _, _ -> inspection(selected.get()) }).use { resolver ->
            resolver.resolve(5108, NativeVisionWork(1))
            selected.set("replacement")
            assertEquals(NativeVisionAvailabilityState.RESTART_REQUIRED,
                assertThrows(VisionResolutionFailure::class.java) { resolver.prepare("opencv", NativeVisionWork(2)) }.state)
            assertEquals(NativeVisionAvailabilityState.RESTART_REQUIRED,
                assertThrows(VisionResolutionFailure::class.java) { resolver.resolve(5108, NativeVisionWork(3)) }.state)
            selected.set(null)
            assertEquals(NativeVisionAvailabilityState.MISSING,
                assertThrows(VisionResolutionFailure::class.java) { resolver.prepare("opencv", NativeVisionWork(4)) }.state)
            assertEquals(NativeVisionAvailabilityState.MISSING,
                assertThrows(VisionResolutionFailure::class.java) { resolver.resolve(5108, NativeVisionWork(5)) }.state)
        }
    }

    @Test fun cancelledInspectionCannotReplaceCachedReadiness() {
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        val calls = AtomicInteger()
        val expected = AtomicReference<NativeVisionBackend>()
        lateinit var resolver: RuntimeVisionResolver
        resolver = RuntimeVisionResolver(emptySet(), { _, _ -> VisionResolvedBackend("selected", Backend()) },
            { _, _ ->
                if (calls.incrementAndGet() == 1) {
                    entered.countDown()
                    while (release.count > 0) try { release.await() } catch (_: InterruptedException) { }
                    inspection(null)
                } else {
                    assertSame(expected.get(), resolver.resolve(5108, NativeVisionWork(99)))
                    inspection()
                }
            })
        resolver.use {
            val backend = resolver.resolve(5108, NativeVisionWork(1))
            expected.set(backend)
            val work = NativeVisionWork(2)
            val failure = AtomicReference<Throwable?>()
            val caller = Thread { try { resolver.prepare("opencv", work) } catch (error: Throwable) { failure.set(error) } }
            try {
                caller.start(); assertTrue(entered.await(2, TimeUnit.SECONDS))
                work.cancel(); release.countDown(); caller.join(2000)
                assertFalse(caller.isAlive); assertNotNull(failure.get())
                // A fresh inspection runs behind the cancelled one on the same IO lane.
                assertEquals("selected", resolver.prepare("opencv", NativeVisionWork(3)))
                assertSame(backend, resolver.resolve(5108, NativeVisionWork(4)))
                val cancelled = NativeVisionWork(5).apply { cancel() }
                assertThrows(IllegalStateException::class.java) { resolver.resolve(5108, cancelled) }
            } finally { release.countDown(); caller.join(2000) }
        }
    }

    @Test fun failedUnpublishedLoadCanRetryButInspectionFailureRemainsExplicit() {
        val loads = AtomicInteger()
        val corrupt = java.util.concurrent.atomic.AtomicBoolean(true)
        RuntimeVisionResolver(emptySet(), { _, _ ->
            if (loads.incrementAndGet() == 1) throw VisionResolutionFailure(NativeVisionAvailabilityState.LOAD_FAILED)
            VisionResolvedBackend("selected", Backend())
        }, { _, _ -> if (corrupt.get()) throw VisionResolutionFailure(NativeVisionAvailabilityState.CORRUPT) else inspection() }).use { resolver ->
            assertEquals(NativeVisionAvailabilityState.CORRUPT,
                assertThrows(VisionResolutionFailure::class.java) { resolver.prepare("opencv", NativeVisionWork(1)) }.state)
            assertEquals(0, loads.get())
            corrupt.set(false)
            assertEquals(NativeVisionAvailabilityState.LOAD_FAILED,
                assertThrows(VisionResolutionFailure::class.java) { resolver.prepare("opencv", NativeVisionWork(2)) }.state)
            assertEquals("selected", resolver.prepare("opencv", NativeVisionWork(3)))
            assertEquals(2, loads.get())
        }
    }

    @Test fun failedSyntheticInitializationIsNotAvailableReadiness() {
        val backend = object : NativeVisionBackend {
            override fun execute(work: NativeVisionWork, pixels: ByteBuffer, metadata: IntArray,
                templatePixels: ByteBuffer?, templateMetadata: IntArray, options: IntArray): ByteArray = error("private-sdk-text")
            override fun close() = Unit
        }
        RuntimeVisionResolver(emptySet(), { _, _ -> VisionResolvedBackend("selected", backend) }, { _, _ -> inspection() }).use { resolver ->
            assertEquals(NativeVisionAvailabilityState.LOAD_FAILED,
                assertThrows(VisionResolutionFailure::class.java) { resolver.prepare("onnx", NativeVisionWork(1)) }.state)
            assertEquals(NativeVisionAvailabilityState.LOAD_FAILED,
                assertThrows(VisionResolutionFailure::class.java) { resolver.resolve(6101, NativeVisionWork(2)) }.state)
        }
    }
    @Test fun startupPreparationUsesOwnedSyntheticPixelsAndRevalidatesSelection() {
        val executed = AtomicInteger()
        val selected = AtomicReference("selected")
        val backend = object : NativeVisionBackend {
            override fun execute(work: NativeVisionWork, pixels: ByteBuffer, metadata: IntArray,
                templatePixels: ByteBuffer?, templateMetadata: IntArray, options: IntArray): ByteArray {
                assertTrue(pixels.isDirect); assertArrayEquals(intArrayOf(8,8,32,1), metadata)
                assertEquals(256, pixels.remaining()); assertNotNull(templatePixels)
                assertArrayEquals(intArrayOf(0,0,8,8,1000), options); executed.incrementAndGet()
                return byteArrayOf()
            }
            override fun close() = Unit
        }
        RuntimeVisionResolver(emptySet(), { _, _ -> VisionResolvedBackend("selected", backend) },
            { _, _ -> inspection(selected.get()) }, selectedIdentity = { selected.get() }).use { resolver ->
            assertEquals("selected", resolver.prepare("opencv", NativeVisionWork(1)))
            assertEquals(1, executed.get()); assertTrue(resolver.selectionsMatch(mapOf("opencv" to "selected")))
            assertEquals("selected", resolver.prepare("opencv", NativeVisionWork(3)))
            assertEquals("An initialized session must not redo synthetic inference", 1, executed.get())
            selected.set("replacement")
            assertFalse(resolver.selectionsMatch(mapOf("opencv" to "selected")))
            assertEquals(NativeVisionAvailabilityState.RESTART_REQUIRED,
                assertThrows(VisionResolutionFailure::class.java) { resolver.prepare("opencv", NativeVisionWork(2)) }.state)
            assertEquals(1, executed.get())
        }
    }
    private class Backend : NativeVisionBackend {
        var closes = 0
        override fun execute(work: NativeVisionWork, pixels: ByteBuffer, metadata: IntArray,
            templatePixels: ByteBuffer?, templateMetadata: IntArray, options: IntArray) = byteArrayOf(7)
        override fun close() { closes++ }
    }
    private fun inspection(id: String? = "selected") = VisionRuntimeInspection(id,
        if (id == null) NativeVisionAvailabilityState.MISSING else NativeVisionAvailabilityState.INSTALLED)

    @Test fun sdkInitializationFailurePoisonsReadinessButParameterErrorsDoNot() {
        for (initialization in listOf(true, false)) {
            val backend = object : NativeVisionBackend {
                override fun execute(work: NativeVisionWork, pixels: ByteBuffer, metadata: IntArray,
                    templatePixels: ByteBuffer?, templateMetadata: IntArray, options: IntArray): ByteArray {
                    if (initialization) throw NativeVisionInitializationFailure() else throw IllegalArgumentException()
                }
                override fun close() = Unit
            }
            val resolver = RuntimeVisionResolver(emptySet(), { _, _ -> VisionResolvedBackend("selected", backend) }, { _, _ -> inspection() })
            try {
                val selected = resolver.resolve(5108, NativeVisionWork(1))
                assertThrows(IllegalStateException::class.java.takeIf { initialization } ?: IllegalArgumentException::class.java) {
                    selected.execute(NativeVisionWork(2), ByteBuffer.allocateDirect(4), intArrayOf(), null, intArrayOf(), intArrayOf())
                }
                if (initialization) {
                    awaitStatus(resolver, "LOAD_FAILED")
                    assertEquals(NativeVisionAvailabilityState.LOAD_FAILED,
                        assertThrows(VisionResolutionFailure::class.java) { resolver.resolve(5108, NativeVisionWork(3)) }.state)
                } else { awaitStatus(resolver, "AVAILABLE"); assertSame(selected, resolver.resolve(5108, NativeVisionWork(3))) }
            } finally { resolver.close() }
        }
    }

    private fun awaitStatus(resolver: RuntimeVisionResolver, expected: String) {
        val deadline = System.nanoTime() + 2_000_000_000L
        while (System.nanoTime() < deadline) {
            if (resolver.availability().first().startsWith("opencv=$expected,")) return
            Thread.sleep(2)
        }
        fail("Expected $expected: ${resolver.availability().toList()}")
    }

    @Test fun adapterUsesResolverFailureWithoutClosingSharedOwnerOrSubstitutingBackend() {
        var closes = 0
        val adapter = NativeVisionAdapter(object : NativeVisionBackendSource {
            override fun resolve(operation: Int, work: NativeVisionWork): NativeVisionBackend {
                assertEquals(6101, operation)
                throw VisionResolutionFailure(NativeVisionAvailabilityState.INCOMPATIBLE)
            }
            override fun close() { closes++ }
        }, ownsSource = false)
        try {
            val result = adapter.dispatch(1, 6101, ByteBuffer.allocateDirect(4), intArrayOf(1, 1, 4, 1),
                null, intArrayOf(), intArrayOf(0, 0, 1, 1, 500))
            assertEquals(1, result[0].toInt())
            assertEquals("VISION_COMPONENT_INCOMPATIBLE", result.copyOfRange(1, result.size).toString(Charsets.US_ASCII))
        } finally { adapter.close() }
        assertEquals(0, closes)
    }

    @Test fun changedOrRemovedExactSelectionBlocksCachedBackendInsteadOfFallback() {
        val selected = AtomicReference<String?>("selected"); val clock = AtomicLong(0)
        val calls = AtomicInteger(); val backend = Backend()
        val resolver = RuntimeVisionResolver(emptySet(), { _, _ -> calls.incrementAndGet(); VisionResolvedBackend("selected", backend) },
            { _, _ -> inspection(selected.get()) }, clock::get)
        try {
            resolver.resolve(5108, NativeVisionWork(1))
            selected.set("different"); awaitStatus(resolver, "RESTART_REQUIRED")
            assertEquals(NativeVisionAvailabilityState.RESTART_REQUIRED,
                assertThrows(VisionResolutionFailure::class.java) { resolver.resolve(5108, NativeVisionWork(2)) }.state)
            selected.set(null); clock.addAndGet(6_000_000_000L); awaitStatus(resolver, "MISSING")
            assertEquals(NativeVisionAvailabilityState.MISSING,
                assertThrows(VisionResolutionFailure::class.java) { resolver.resolve(5108, NativeVisionWork(3)) }.state)
            selected.set("selected"); clock.addAndGet(6_000_000_000L); awaitStatus(resolver, "AVAILABLE")
            resolver.resolve(5108, NativeVisionWork(4)); assertEquals(1, calls.get())
        } finally { resolver.close() }
        assertEquals(1, backend.closes)
    }

    @Test fun demandsOnlyRequestedKindReusesSessionAndKeepsInspectionOffCallerThread() {
        val backend = Backend(); val calls = mutableListOf<String>(); val thread = AtomicReference<String>()
        val inspected = CountDownLatch(2)
        val resolver = RuntimeVisionResolver(emptySet(), load = { kind, checkpoint ->
            checkpoint(); calls.add(kind); VisionResolvedBackend("selected", backend)
        }, inspect = { _, checkpoint -> checkpoint(); thread.set(Thread.currentThread().name); inspected.countDown(); inspection() })
        try {
            assertSame(resolver.resolve(5108, NativeVisionWork(1)), resolver.resolve(5108, NativeVisionWork(2)))
            assertEquals(listOf("opencv"), calls)
            resolver.availability()
            assertTrue(inspected.await(2, TimeUnit.SECONDS))
            assertEquals("vision-provider-io", thread.get())
            assertTrue(resolver.availability().first().contains("AVAILABLE,source=provider,loaded=true"))
        } finally { resolver.close() }
        resolver.close(); assertEquals(1, backend.closes)
        assertThrows(IllegalStateException::class.java) { resolver.resolve(5108, NativeVisionWork(3)) }
    }

    @Test fun missingAndCorruptAreExplicitAndCanRetryOnlyExactRequestedLoader() {
        val tries = AtomicInteger()
        val resolver = RuntimeVisionResolver(emptySet(), load = { _, _ ->
            if (tries.incrementAndGet() == 1) throw VisionResolutionFailure(NativeVisionAvailabilityState.MISSING)
            throw VisionResolutionFailure(NativeVisionAvailabilityState.CORRUPT)
        }, inspect = { _, _ -> inspection(null) })
        try {
            assertEquals(NativeVisionAvailabilityState.MISSING,
                assertThrows(VisionResolutionFailure::class.java) { resolver.resolve(6101, NativeVisionWork(1)) }.state)
            assertEquals(NativeVisionAvailabilityState.CORRUPT,
                assertThrows(VisionResolutionFailure::class.java) { resolver.resolve(6101, NativeVisionWork(2)) }.state)
            assertEquals(2, tries.get())
        } finally { resolver.close() }
    }

    @Test fun cancelledUnpublishedSessionIsClosedAndNeverAddedToCache() {
        val entered = CountDownLatch(1); val release = CountDownLatch(1); val closed = CountDownLatch(1)
        val backend = object : NativeVisionBackend {
            override fun execute(work: NativeVisionWork, pixels: ByteBuffer, metadata: IntArray,
                templatePixels: ByteBuffer?, templateMetadata: IntArray, options: IntArray) = byteArrayOf()
            override fun close() { closed.countDown() }
        }
        val resolver = RuntimeVisionResolver(emptySet(), load = { _, _ ->
            entered.countDown()
            // Mimic native initialization completing despite an interruption.
            try { release.await(2, TimeUnit.SECONDS) } catch (_: InterruptedException) { }
            VisionResolvedBackend("selected", backend)
        }, inspect = { _, _ -> inspection() })
        val work = NativeVisionWork(1)
        val failure = AtomicReference<Throwable?>()
        val caller = Thread { try { resolver.resolve(5108, work) } catch (error: Throwable) { failure.set(error) } }
        try {
            caller.start(); assertTrue(entered.await(2, TimeUnit.SECONDS)); work.cancel(); release.countDown()
            caller.join(2000); assertFalse(caller.isAlive); assertNotNull(failure.get())
            assertTrue(closed.await(2, TimeUnit.SECONDS))
            assertTrue(resolver.availability().first().contains("loaded=false"))
        } finally { release.countDown(); resolver.close(); caller.join(2000) }
    }

    @Test fun sessionCloseCancelsActiveWorkAndDefersReleaseWithoutQueuingAnotherCall() {
        val entered = CountDownLatch(1); val release = CountDownLatch(1); val closes = AtomicInteger()
        val session = VisionRuntimeSession(object : NativeVisionBackend {
            override fun execute(work: NativeVisionWork, pixels: ByteBuffer, metadata: IntArray,
                templatePixels: ByteBuffer?, templateMetadata: IntArray, options: IntArray): ByteArray {
                entered.countDown(); assertTrue(release.await(2, TimeUnit.SECONDS)); return byteArrayOf()
            }
            override fun close() { closes.incrementAndGet() }
        })
        val work = NativeVisionWork(1); val buffer = ByteBuffer.allocateDirect(1)
        val caller = Thread { session.execute(work, buffer, intArrayOf(), null, intArrayOf(), intArrayOf()) }
        try {
            caller.start(); assertTrue(entered.await(2, TimeUnit.SECONDS))
            assertThrows(IllegalStateException::class.java) { session.execute(NativeVisionWork(2), buffer, intArrayOf(), null, intArrayOf(), intArrayOf()) }
            session.close(); assertTrue(work.isCancelled()); assertEquals(0, closes.get())
            release.countDown(); caller.join(2000); assertEquals(1, closes.get()); session.close(); assertEquals(1, closes.get())
        } finally { release.countDown(); caller.join(2000); session.close() }
    }

    @Test fun processAbiIsActualArchitectureNotJustFirstSupportedAbi() {
        assertEquals("arm64-v8a", visionProcessAbi(true, "aarch64", listOf("arm64-v8a", "x86_64")))
        assertEquals("x86_64", visionProcessAbi(true, "amd64", listOf("arm64-v8a", "x86_64")))
        assertNull(visionProcessAbi(false, "aarch64", listOf("arm64-v8a")))
        assertNull(visionProcessAbi(true, "unknown", listOf("arm64-v8a")))
        assertNull(visionProcessAbi(true, "x86_64", listOf("arm64-v8a")))
    }
}
