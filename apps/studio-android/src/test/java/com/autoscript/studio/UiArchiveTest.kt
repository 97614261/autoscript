package com.autoscript.studio

import com.google.gson.JsonParser
import java.io.File
import java.security.MessageDigest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

class UiArchiveTest {
    private val repository = File("../..").canonicalFile
    private val archive = File(repository, "archive/studio-ui/2026-10-02")
    private val source = File("src/main/java/com/autoscript/studio")

    private fun manifest(): com.google.gson.JsonObject {
        // Removing the whole archive later must not introduce a production/build dependency.
        assumeTrue("本批归档已整目录移除", archive.exists())
        return JsonParser.parseString(File(archive, "manifest.json").readText()).asJsonObject
    }

    @Test fun archivedFilesAreIntactAndOutsideSourceSets() {
        val manifest = manifest()
        for (record in manifest.getAsJsonArray("files")) {
            val entry = record.asJsonObject
            val file = File(repository, entry.get("archive").asString).canonicalFile
            assertTrue(file.toPath().startsWith(archive.toPath()))
            assertFalse(file.toPath().startsWith(File("src").canonicalFile.toPath()))
            assertTrue("归档丢失：$file", file.isFile)
            val digest = MessageDigest.getInstance("SHA-256").digest(file.readBytes())
                .joinToString("") { "%02x".format(it.toInt() and 0xff) }
            assertEquals(entry.get("sha256").asString, digest)
            if (entry.get("kind").asString == "fragment") assertTrue(file.name.endsWith(".kt.txt"))
            else assertFalse("归档资源仍在生产目录", File(repository, entry.get("source").asString).exists())
        }
    }

    @Test fun unusedDeclarationsAreNoLongerInProduction() {
        val manifest = manifest()
        val production = source.listFiles().orEmpty().filter { it.extension == "kt" }
            .joinToString("\n") { it.readText() }
        for (record in manifest.getAsJsonArray("declarations")) {
            val entry = record.asJsonObject
            val symbol = Regex.escape(entry.get("symbol").asString)
            val declaration = if (entry.get("kind").asString == "constant") "\\bval\\s+$symbol\\b"
                else "\\bfun\\s+$symbol\\s*\\("
            assertFalse("废弃声明仍在生产源码：$symbol", Regex(declaration).containsMatchIn(production))
        }
        assertEquals(16, manifest.getAsJsonArray("declarations").count { it.asJsonObject.get("kind").asString == "declaration" })
        assertEquals(5, manifest.getAsJsonArray("declarations").count { it.asJsonObject.get("kind").asString == "constant" })
    }

    @Test fun activeUiFilesUseTheirCurrentNames() {
        val manifest = manifest()
        for ((old, current) in manifest.getAsJsonObject("renamedFiles").entrySet()) {
            assertFalse(File(source, old).exists())
            assertTrue(File(source, current.asString).isFile)
        }
        val catalog = File(source, "FunctionLibraryDialog.kt").readText()
        assertTrue(catalog.contains("fun FunctionLibraryDialog("))
    }
}
