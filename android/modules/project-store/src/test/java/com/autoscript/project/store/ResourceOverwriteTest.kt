package com.autoscript.project.store

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.RandomAccessFile
import java.nio.file.Files
import java.util.Base64
import org.junit.After
import org.junit.Assert.*
import org.junit.Test

class ResourceOverwriteTest {
    private val root = Files.createTempDirectory("resource-overwrite").toFile()
    private var ticks = 1L
    private var failAt = Long.MAX_VALUE
    private val store = ProjectStore(root, clock = { if (++ticks == failAt) error("injected state failure") else ticks })
    private val png = Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAusB9Y9Z7ZgAAAAASUVORK5CYII=")
    private val incoming get() = png + byteArrayOf(1, 2, 3)
    private val project = store.createFixtureProject("覆盖测试", ProjectSourceMode.VISUAL)
    private val id get() = project.manifest.projectId

    @After fun cleanup() { root.deleteRecursively() }

    private fun paths(snapshot: ProjectSnapshot) = snapshot.manifest.resources.map { it.get("path").asString }.toSet()
    private fun save(snapshot: ProjectSnapshot, bytes: ByteArray = png, mode: ResourceImportMode = ResourceImportMode.Numbered,
        name: String = "button.png", folder: String = "") = bytes.inputStream().use {
        store.importResource(id, ProjectResourceKind.IMAGE, name, it, paths(snapshot), folder, mode)
    }
    private fun ask(snapshot: ProjectSnapshot, bytes: ByteArray = incoming): ResourceReplacement =
        assertThrows(ResourceImportConflictException::class.java) { save(snapshot, bytes, ResourceImportMode.Ask) }.replacement

    @Test fun askingDoesNotChangeBytesManifestOrGeneratedArtifact() {
        val saved = save(project)
        val target = File(saved.directory, paths(saved).single())
        val manifest = File(saved.directory, "project.json").readBytes()
        val generation = File(saved.directory, "generated/generation.json").apply { parentFile.mkdirs(); writeText("frozen") }
        val proof = ask(saved)
        assertEquals("assets/images/button.png", proof.path)
        assertNotEquals(proof.previousSha256, proof.incomingSha256)
        assertArrayEquals(png, target.readBytes())
        assertArrayEquals(manifest, File(saved.directory, "project.json").readBytes())
        assertEquals("frozen", generation.readText())
        assertTrue(File(saved.directory, ".studio/import").listFiles().orEmpty().isEmpty())
    }

    @Test fun replacementKeepsReferencesAndDeclarationAndRefreshesRevision() {
        var saved = save(project)
        val path = paths(saved).single()
        val original = saved.flowSources.getValue("main")
        val referencing = JsonParser.parseString(original.trim()).asJsonObject.apply {
            add("args", JsonObject().apply { addProperty("imagePath", path) })
        }.toString() + "\n"
        saved = store.saveFlow(id, "main", referencing, original)
        val recipe = ProjectImageRecipe("按钮", 8, 8, emptyList(), ProjectImageRegion(0, 0, 8, 8),
            ProjectImageRecipeTail("vision.findimage", """{"template":"$path","tolerance":0,"similarityPermille":900,"direction":0}"""))
        store.updateImageRecipes(id, listOf(recipe), emptyList())
        val manifest = File(saved.directory, "project.json").readBytes()
        val generation = File(saved.directory, "generated/generation.json").apply { parentFile.mkdirs(); writeText("old") }
        val mode = ResourceImportMode.Replace(ask(saved))
        val updated = save(saved, incoming, mode)
        assertEquals(saved.manifest.resources, updated.manifest.resources)
        assertEquals(referencing, updated.flowSources.getValue("main"))
        assertEquals(listOf(recipe), store.readImageRecipes(id))
        assertArrayEquals(manifest, File(updated.directory, "project.json").readBytes())
        assertArrayEquals(incoming, File(updated.directory, path).readBytes())
        assertFalse(generation.exists())
        assertTrue(updated.resourceRevision.isNotEmpty())
        assertEquals(path, importedResourcePath(updated, paths(saved), mode))
        val backup = ByteArrayOutputStream().also { store.exportProjectBackup(id, it) }
        val restored = store.importProjectBackup(backup.toByteArray().inputStream())
        assertArrayEquals(incoming, File(restored.directory, path).readBytes())
        assertEquals(listOf(recipe), store.readImageRecipes(restored.manifest.projectId))
    }

    @Test fun numberedAlternativeStillPreservesOldContent() {
        val saved = save(project)
        ask(saved)
        val updated = save(saved, incoming)
        assertEquals(setOf("assets/images/button.png", "assets/images/button-2.png"), paths(updated))
        assertArrayEquals(png, File(saved.directory, "assets/images/button.png").readBytes())
        assertArrayEquals(incoming, File(saved.directory, "assets/images/button-2.png").readBytes())
        assertEquals("assets/images/button-2.png", importedResourcePath(updated, paths(saved), ResourceImportMode.Numbered))
    }

    @Test fun glyphReplacementReturnsExistingPathInsteadOfLookingForANewDeclaration() {
        val dictionary = byteArrayOf(0x41, 0x53, 0x47, 0x4C, 0x59, 0x50, 0x48, 0,
            1, 0, 0, 0, 1, 0, 0, 0, 1, 0, 1, 0, 1, 0, 0, 0, 1, 0, 0, 0, 0x41, 0x80.toByte())
        val old = dictionary.inputStream().use { store.importResource(id, ProjectResourceKind.GLYPH_DICTIONARY, "digits.asglyph", it, emptySet()) }
        val replacement = dictionary.copyOf().apply { this[28] = 0x42 }
        val proof = assertThrows(ResourceImportConflictException::class.java) {
            replacement.inputStream().use { store.importResource(id, ProjectResourceKind.GLYPH_DICTIONARY,
                "digits.asglyph", it, paths(old), mode = ResourceImportMode.Ask) }
        }.replacement
        val mode = ResourceImportMode.Replace(proof)
        val updated = replacement.inputStream().use { store.importResource(id, ProjectResourceKind.GLYPH_DICTIONARY,
            "digits.asglyph", it, paths(old), mode = mode) }
        assertEquals("dictionaries/digits.asglyph", importedResourcePath(updated, paths(old), mode))
        assertEquals(old.manifest.resources, updated.manifest.resources)
        assertArrayEquals(replacement, File(updated.directory, proof.path).readBytes())
    }

    @Test fun changedOldBytesCannotBeOverwrittenWithStaleConfirmation() {
        val saved = save(project)
        val mode = ResourceImportMode.Replace(ask(saved))
        val changed = png + byteArrayOf(9)
        val target = File(saved.directory, "assets/images/button.png").apply { writeBytes(changed) }
        assertThrows(IllegalArgumentException::class.java) { save(saved, incoming, mode) }
        assertArrayEquals(changed, target.readBytes())
        assertEquals(1, store.openProject(id).manifest.resources.size)
    }

    @Test fun confirmationCannotBeReusedForOtherContentNameOrDirectory() {
        val saved = save(project)
        val mode = ResourceImportMode.Replace(ask(saved))
        assertThrows(IllegalArgumentException::class.java) { save(saved, png + byteArrayOf(8), mode) }
        assertThrows(IllegalArgumentException::class.java) { save(saved, incoming, mode, name = "other.png") }
        assertThrows(IllegalArgumentException::class.java) { save(saved, incoming, mode, folder = "other") }
        assertArrayEquals(png, File(saved.directory, "assets/images/button.png").readBytes())
        assertEquals(setOf("assets/images/button.png"), paths(store.openProject(id)))
    }

    @Test fun unknownFilesAreNotReplacementTargets() {
        val target = File(project.directory, "assets/images/button.png").apply { parentFile.mkdirs(); writeBytes(png) }
        assertThrows(IllegalArgumentException::class.java) { save(project, incoming, ResourceImportMode.Ask) }
        assertArrayEquals(png, target.readBytes())
        assertTrue(store.openProject(id).manifest.resources.isEmpty())
    }

    @Test fun invalidIncomingDataAndChangedDeclarationRefuseBeforeMutation() {
        val saved = save(project)
        val mode = ResourceImportMode.Replace(ask(saved))
        assertThrows(IllegalArgumentException::class.java) { save(saved, "not an image".toByteArray(), mode) }
        val manifest = File(saved.directory, "project.json")
        val json = JsonParser.parseString(manifest.readText()).asJsonObject
        json.getAsJsonArray("resources").single().asJsonObject.addProperty("kind", "audio")
        manifest.writeText(json.toString())
        assertThrows(ProjectManifestConflictException::class.java) { save(saved, incoming, mode) }
        assertArrayEquals(png, File(saved.directory, "assets/images/button.png").readBytes())
    }

    @Test fun replacementAtResourceLimitDoesNotRequireAnExtraSlot() {
        val saved = save(project)
        val manifest = File(saved.directory, "project.json")
        val json = JsonParser.parseString(manifest.readText()).asJsonObject
        val resources = json.getAsJsonArray("resources")
        val limit = 256
        repeat(limit - 1) { index ->
            val path = "assets/images/other-$index.png"
            File(saved.directory, path).writeBytes(png)
            resources.add(JsonObject().apply { addProperty("kind", "image"); addProperty("path", path) })
        }
        manifest.writeText(json.toString())
        val full = store.openProject(id)
        val updated = save(full, incoming, ResourceImportMode.Replace(ask(full)))
        assertEquals(limit, updated.manifest.resources.size)
        assertThrows(IllegalArgumentException::class.java) { save(updated, incoming) }
    }

    @Test fun audioReplacementSubtractsOldSizeAndChecksQuotaBeforeAsking() {
        val wav = "RIFF1234WAVEfmt ".toByteArray()
        var current = project
        repeat(4) { index ->
            current = wav.inputStream().use { store.importResource(id, ProjectResourceKind.AUDIO, "sound-$index.wav", it, paths(current)) }
            RandomAccessFile(File(project.directory, "assets/audio/sound-$index.wav"), "rw").use { it.setLength(16L * 1024 * 1024) }
        }
        val expected = paths(current)
        val candidate = wav + ByteArray(1024 * 1024)
        val proof = assertThrows(ResourceImportConflictException::class.java) {
            candidate.inputStream().use { store.importResource(id, ProjectResourceKind.AUDIO, "sound-0.wav", it, expected, mode = ResourceImportMode.Ask) }
        }.replacement
        val updated = candidate.inputStream().use { store.importResource(id, ProjectResourceKind.AUDIO, "sound-0.wav", it, expected, mode = ResourceImportMode.Replace(proof)) }
        assertEquals(expected, paths(updated))
        assertArrayEquals(candidate, File(project.directory, proof.path).readBytes())
        // Three remaining 16MiB files + an oversized 17MiB replacement exceed 64MiB.
        val tooLarge = wav + ByteArray(17 * 1024 * 1024)
        val failure = assertThrows(IllegalArgumentException::class.java) {
            tooLarge.inputStream().use { store.importResource(id, ProjectResourceKind.AUDIO, "sound-0.wav", it, expected, mode = ResourceImportMode.Ask) }
        }
        assertFalse(failure is ResourceImportConflictException)
        assertArrayEquals(candidate, File(project.directory, proof.path).readBytes())
    }

    @Test fun postCommitFailureRestoresOldContent() {
        val saved = save(project)
        val mode = ResourceImportMode.Replace(ask(saved))
        // import opens once; the next clock read belongs to the post-commit state write.
        failAt = ticks + 2
        assertThrows(IllegalStateException::class.java) { save(saved, incoming, mode) }
        failAt = Long.MAX_VALUE
        assertArrayEquals(png, File(saved.directory, "assets/images/button.png").readBytes())
        assertEquals(paths(saved), paths(store.openProject(id)))
        assertTrue(File(saved.directory, ".studio/import").listFiles().orEmpty().isEmpty())
    }

    @Test fun recipeAndFilterReplacementStillRejectStaleLists() {
        val oldRecipes = listOf(ProjectImageRecipe("方案", 8, 8, emptyList()))
        store.updateImageRecipes(id, oldRecipes, emptyList())
        val newRecipes = listOf(oldRecipes.single().copy(steps = listOf(ProjectImageStep("gray"))))
        store.updateImageRecipes(id, newRecipes, oldRecipes)
        assertThrows(ProjectManifestConflictException::class.java) { store.updateImageRecipes(id, oldRecipes, oldRecipes) }
        assertEquals(newRecipes, store.readImageRecipes(id))
        val oldFilters = listOf(ProjectImageFilterPreset("方案", ProjectImageFilter()))
        store.updateImageFilterPresets(id, oldFilters, emptyList())
        val newFilters = listOf(oldFilters.single().copy(filter = ProjectImageFilter(invert = true)))
        store.updateImageFilterPresets(id, newFilters, oldFilters)
        assertThrows(ProjectManifestConflictException::class.java) { store.updateImageFilterPresets(id, oldFilters, oldFilters) }
        assertEquals(newFilters, store.readImageFilterPresets(id))
    }
}
