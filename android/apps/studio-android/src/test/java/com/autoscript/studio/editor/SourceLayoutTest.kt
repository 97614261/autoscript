package com.autoscript.studio

import java.io.File
import com.google.gson.JsonParser
import org.junit.Assert.*
import org.junit.Test

class SourceLayoutTest {
    @Test fun everyMappedSourceExistsOnceAndKeepsItsOriginalPackage() {
        val repository = File(requireNotNull(System.getProperty("autoscript.projectRoot"))).canonicalFile
        val map = JsonParser.parseString(File(repository, "docs/source-layout-map.json").readText()).asJsonObject
        assertEquals(1, map["version"].asInt)
        val targets = HashSet<String>()
        for (entry in map.getAsJsonArray("moves")) {
            val move = entry.asJsonObject
            val from = File(repository, move["from"].asString)
            val to = File(repository, move["to"].asString).canonicalFile
            assertTrue(to.toPath().startsWith(File(repository, "android/apps/studio-android/src").toPath()))
            assertTrue(targets.add(to.path))
            assertFalse(from.exists())
            assertTrue(to.isFile)
            assertEquals(from.name, to.name)
            assertTrue(to.readText().startsWith("package com.autoscript.studio"))
        }
        assertEquals(186, targets.size)
    }
}
