package com.autoscript.studio

import org.junit.Assert.*
import org.junit.Test

class FunctionLibraryUsageTest {
    @Test fun favoritesToggleWithoutChangingRecency() {
        val state = FunctionLibraryUsage().used("task.sleep").toggle("input.tap")
        assertEquals(listOf("input.tap"), state.favorites)
        assertEquals(listOf("task.sleep"), state.recent)
        assertTrue(state.toggle("input.tap").favorites.isEmpty())
    }

    @Test fun favoritesAreBoundedAndExistingFavoritesCanAlwaysBeRemoved() {
        val full = FunctionLibraryUsage(favorites = (1..64).map { "kind$it" })
        assertEquals(full, full.toggle("extra"))
        assertEquals(63, full.toggle("kind1").favorites.size)
        assertEquals(64, full.toggle("kind1").toggle("extra").favorites.size)
    }

    @Test fun recentsAreUniqueMostRecentFirstAndBounded() {
        var state = FunctionLibraryUsage()
        repeat(40) { state = state.used("kind$it") }
        assertEquals(24, state.recent.size)
        assertEquals("kind39", state.recent.first())
        state = state.used("kind20")
        assertEquals("kind20", state.recent.first())
        assertEquals(1, state.recent.count { it == "kind20" })
    }

    @Test fun persistedDataIsBoundedValidatedAndContainsOnlyKinds() {
        val state = FunctionLibraryUsage(listOf("input.tap"), listOf("task.sleep"))
        assertEquals(state, FunctionLibraryUsage.decode(state.encode()) { BlockCatalog.find(it) != null })
        assertEquals(FunctionLibraryUsage(), FunctionLibraryUsage.decode("not-json") { true })
        assertEquals(FunctionLibraryUsage(), FunctionLibraryUsage.decode("x".repeat(16_385)) { true })
        val dirty = """{"favorites":["input.tap","missing","input.tap",42],"recent":["task.sleep",null]}"""
        assertEquals(state, FunctionLibraryUsage.decode(dirty) { BlockCatalog.find(it) != null })
    }

    @Test fun recencyIsRecordedOnlyFromActualCompletionNotOpenOrCancel() {
        val source = java.io.File("src/main/java/com/autoscript/studio/editor/FunctionLibraryDialog.kt").readText()
        assertTrue(source.contains("submit(entry.snippet)"))
        assertTrue(source.contains("if (!recorded)"))
        assertTrue(source.contains("it.used(kind)"))
        val dock = editorDockTestSources()
        assertTrue(dock.contains("onInsertWithCompletion = { snippet, committed -> submitSnippet(snippet, committed) }"))
    }
}
