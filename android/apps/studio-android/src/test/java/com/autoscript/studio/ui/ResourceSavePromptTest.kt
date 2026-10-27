package com.autoscript.studio

import com.autoscript.project.store.ResourceImportConflictException
import com.autoscript.project.store.ResourceImportMode
import com.autoscript.project.store.ResourceReplacement
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class ResourceSavePromptTest {
    private val proof = ResourceReplacement("assets/images/button.png", "old", "new")

    @Test fun noCollisionSavesWithoutOpeningPrompt() = runBlocking {
        val prompt = ResourceSavePrompt()
        assertEquals("saved", prompt.run { mode -> assertEquals(ResourceImportMode.Ask, mode); "saved" })
        assertNull(prompt.pending)
    }

    @Test fun confirmedReplacementUsesTheExactProofAndRetriesOnce() = runBlocking {
        val prompt = ResourceSavePrompt()
        val calls = mutableListOf<ResourceImportMode>()
        val job = async(start = CoroutineStart.UNDISPATCHED) {
            prompt.run { mode ->
                calls += mode
                if (mode == ResourceImportMode.Ask) throw ResourceImportConflictException(proof)
                "saved"
            }
        }
        assertEquals(listOf(ResourceImportMode.Ask), calls)
        val request = requireNotNull(prompt.pending)
        assertEquals(proof.path, request.name)
        prompt.finish(request, SaveConflictChoice.REPLACE)
        assertEquals("saved", job.await())
        assertEquals(listOf(ResourceImportMode.Ask, ResourceImportMode.Replace(proof)), calls)
        assertNull(prompt.pending)
    }

    @Test fun numberedAlternativeDoesNotAuthorizeReplacement() = runBlocking {
        val prompt = ResourceSavePrompt()
        var actual: ResourceImportMode? = null
        val job = async(start = CoroutineStart.UNDISPATCHED) {
            prompt.run { mode ->
                if (mode == ResourceImportMode.Ask) throw ResourceImportConflictException(proof)
                actual = mode
            }
        }
        prompt.finish(requireNotNull(prompt.pending), SaveConflictChoice.NUMBERED)
        job.await()
        assertEquals(ResourceImportMode.Numbered, actual)
    }

    @Test fun cancellationNeverRetriesAndAllowsAnotherSave() = runBlocking {
        val prompt = ResourceSavePrompt()
        var attempts = 0
        val job = async(start = CoroutineStart.UNDISPATCHED) {
            prompt.run { attempts++; throw ResourceImportConflictException(proof) }
        }
        prompt.finish(requireNotNull(prompt.pending), null)
        try { job.await(); fail("expected cancellation") } catch (_: CancellationException) { }
        assertEquals(1, attempts)
        assertNull(prompt.pending)
        assertEquals(42, prompt.run { 42 })
    }

    @Test fun disposedFormCannotConfirmALateReplacement() = runBlocking {
        val prompt = ResourceSavePrompt()
        val job = async(start = CoroutineStart.UNDISPATCHED) { prompt.choose("方案", "detail") }
        val request = requireNotNull(prompt.pending)
        prompt.dispose()
        prompt.finish(request, SaveConflictChoice.REPLACE)
        try { job.await(); fail("expected cancellation") } catch (_: CancellationException) { }
        assertNull(prompt.pending)
    }

    @Test fun oldPromptActionCannotAffectANewerSave() = runBlocking {
        val prompt = ResourceSavePrompt()
        val old = async(start = CoroutineStart.UNDISPATCHED) { prompt.choose("old", "detail") }
        val oldRequest = requireNotNull(prompt.pending)
        prompt.finish(oldRequest, SaveConflictChoice.NUMBERED)
        old.await()
        val next = async(start = CoroutineStart.UNDISPATCHED) { prompt.choose("new", "detail") }
        val nextRequest = requireNotNull(prompt.pending)
        prompt.finish(oldRequest, SaveConflictChoice.REPLACE)
        assertFalse(next.isCompleted)
        prompt.finish(nextRequest, SaveConflictChoice.NUMBERED)
        assertEquals(SaveConflictChoice.NUMBERED, next.await())
    }

    @Test fun numberedNamesAreStableAndRespectUnicodeBoundaries() {
        assertEquals("按钮", numberedSaveName("按钮", emptyList()))
        assertEquals("按钮-3", numberedSaveName("按钮", listOf("按钮", "按钮-2")))
        val base = "\uD83D\uDE00".repeat(32)
        assertEquals("\uD83D\uDE00".repeat(24) + "-2", numberedSaveName(base, listOf(base)))
    }
}
