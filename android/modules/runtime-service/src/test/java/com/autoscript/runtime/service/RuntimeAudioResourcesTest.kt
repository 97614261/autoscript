package com.autoscript.runtime.service

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class RuntimeAudioResourcesTest {
    @get:Rule val temporary = TemporaryFolder()
    private val wav = "RIFF1234WAVEfmt ".toByteArray()

    @Test fun frozenAudioCanBeReadAndIsRemovedOnlyFromOwnedCache() {
        val store = RuntimeAudioResources(temporary.newFolder("audio"))
        store.register("assets/audio/a.wav", wav.inputStream()) { true }
        assertArrayEquals(wav, store.open("assets/audio/a.wav").use { it.readBytes() })
        assertThrows(IllegalArgumentException::class.java) { store.register("assets/audio/a.wav", wav.inputStream()) { true } }
        store.clear()
        assertThrows(ProjectDataFailure::class.java) { store.open("assets/audio/a.wav") }
        assertFalse(RuntimeAudioResources.validPath("assets/audio/../a.wav"))
    }

    @Test fun staleRegistrationCannotPublishAndInvalidAudioIsRejected() {
        val directory = temporary.newFolder("stale")
        val store = RuntimeAudioResources(directory)
        assertThrows(IllegalStateException::class.java) { store.register("assets/audio/a.wav", wav.inputStream()) { false } }
        assertThrows(IllegalArgumentException::class.java) { store.register("assets/audio/a.wav", "plain text".byteInputStream()) { true } }
        assertTrue(directory.listFiles()!!.isEmpty())
    }

    @Test fun aliasedCacheParentCanRegisterWithoutRelaxingFinalDirectoryChecks() {
        val parent = temporary.newFolder("cache")
        java.io.File(parent, "child").mkdir()
        val aliased = java.io.File(parent, "child/../audio")
        aliased.mkdirs()
        val store = RuntimeAudioResources(aliased)
        store.register("assets/audio/a.wav", wav.inputStream()) { true }
        assertArrayEquals(wav, store.open("assets/audio/a.wav").use { it.readBytes() })
        store.clear()
        assertTrue(java.io.File(parent, "audio").listFiles()!!.isEmpty())
    }
}
