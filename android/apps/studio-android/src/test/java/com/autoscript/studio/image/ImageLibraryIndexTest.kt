package com.autoscript.studio

import com.google.gson.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ImageLibraryIndexTest {
    private val paths = listOf("assets/images/first/a.png", "assets/images/second/b.png", "assets/images/first/nested/c.png")

    @Test fun defaultShowsImagesFromEveryFolderEvenWhenRootIsEmpty() {
        assertEquals(paths.toSet(), imageLibraryPaths(paths, null, "").toSet())
        assertTrue(imageLibraryPaths(paths, "", "").isEmpty())
    }

    @Test fun explicitFolderUsesItsOwnImagesInsteadOfPretendingToBeAllImages() {
        assertEquals(listOf(paths[0]), imageLibraryPaths(paths, "first", ""))
        assertEquals(listOf(paths[2]), imageLibraryPaths(paths, "first/nested", ""))
    }

    @Test fun searchMatchesNamesAndFoldersWithoutLosingTheChosenScope() {
        assertEquals(listOf(paths[1]), imageLibraryPaths(paths, null, " B.PNG "))
        assertEquals(setOf(paths[0], paths[2]), imageLibraryPaths(paths, null, "FIRST").toSet())
        assertTrue(imageLibraryPaths(paths, "second", "first").isEmpty())
    }

    @Test fun onlyDeclaredImagesAreIndexedAndDuplicatePathsAreRemoved() {
        fun resource(kind: String, path: String) = JsonObject().apply { addProperty("kind", kind); addProperty("path", path) }
        assertEquals(listOf(paths[0]), projectImageLibraryPaths(listOf(
            resource("image", paths[0]), resource("image", paths[0]), resource("audio", "assets/audio/a.wav"),
            resource("glyphDictionary", "dictionaries/digits.glyph"), resource("image", "other/a.png"))))
    }
}
