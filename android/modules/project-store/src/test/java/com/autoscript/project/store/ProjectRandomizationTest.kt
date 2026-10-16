package com.autoscript.project.store

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import com.google.gson.Gson
import com.google.gson.JsonParser
import org.junit.Assert.*
import org.junit.Test

class ProjectRandomizationTest {
    @Test fun exactNumbersRemainStrictWithoutTheApi31BigIntegerMethod() {
        val valid = Gson().toJsonTree(ProjectRandomization()).asJsonObject
        for (number in listOf("0", "5.000", "1e3", "4096")) {
            val copy = valid.deepCopy()
            copy.getAsJsonObject("touch").add("xPx", JsonParser.parseString(number))
            validateRandomizationJson(copy)
        }
        for (number in listOf("0.5", "-1", "4097", "2147483648", "-2147483649", "1e200", "1e-200", "\"5\"", "true")) {
            val copy = valid.deepCopy()
            copy.getAsJsonObject("touch").add("xPx", JsonParser.parseString(number))
            assertThrows(number, IllegalArgumentException::class.java) { validateRandomizationJson(copy) }
        }
        val boundary = valid.deepCopy()
        boundary.getAsJsonObject("wait").addProperty("min", -60000)
        boundary.getAsJsonObject("wait").addProperty("max", 60000)
        validateRandomizationJson(boundary)
    }

    @Test fun bothAndroidValidatorsAvoidApi31BigIntegerExactConversion() {
        val root = java.io.File(requireNotNull(System.getProperty("autoscript.projectRoot")))
        for (path in listOf(
            "android/modules/project-store/src/main/java/com/autoscript/project/store/ProjectRandomization.kt",
            "android/modules/studio-compiler/src/main/java/com/autoscript/studio/compiler/NodeRandomizationSettings.kt",
        )) {
            val source = java.io.File(root, path).readText()
            assertFalse(source.contains("toBigIntegerExact()"))
            assertTrue(source.contains("asBigDecimal.intValueExact()"))
        }
    }

    @Test fun projectPolicyDefaultsOffAndPersistsAcrossBackupAndConflictChecks() {
        val root=Files.createTempDirectory("randomization-store-").toFile()
        try {
            var sequence=0; val store=ProjectStore(root,idFactory={"random-project-${++sequence}"})
            val original=store.createProject("随机策略",ProjectSourceMode.VISUAL)
            assertEquals(ProjectRandomization(),original.manifest.randomization)
            val policy=ProjectRandomization(PositionRandomization(true,5,7),PositionRandomization(true,2,3),WaitRandomization(true,"percent",-10,10))
            val saved=store.updateRandomization(original.manifest.projectId,policy,original.manifest.randomization)
            assertEquals("1.8",saved.manifest.runtimeApi)
            assertEquals(policy,store.openProject(saved.manifest.projectId).manifest.randomization)
            assertThrows(ProjectManifestConflictException::class.java) {store.updateRandomization(saved.manifest.projectId,ProjectRandomization(),original.manifest.randomization)}
            val backup=ByteArrayOutputStream();store.exportProjectBackup(saved.manifest.projectId,backup)
            assertEquals(policy,store.importProjectBackup(ByteArrayInputStream(backup.toByteArray())).manifest.randomization)
            val file=original.directory.resolve("project.json")
            val json=JsonParser.parseString(file.readText()).asJsonObject;json.remove("randomization");file.writeText(json.toString())
            assertEquals(ProjectRandomization(),store.openProject(original.manifest.projectId).manifest.randomization)
        } finally {root.deleteRecursively()}
    }
    @Test fun persistedShapeRejectsCoercionsAndInvalidRanges() {
        val valid=Gson().toJsonTree(ProjectRandomization()).asJsonObject
        validateRandomizationJson(valid)
        for (key in listOf("xPx","yPx")) {
            val invalid=valid.deepCopy();invalid.getAsJsonObject("touch").addProperty(key,"5")
            assertThrows(IllegalArgumentException::class.java) {validateRandomizationJson(invalid)}
        }
        val invalid=valid.deepCopy();invalid.getAsJsonObject("wait").addProperty("unit","percent");invalid.getAsJsonObject("wait").addProperty("max",101)
        assertThrows(IllegalArgumentException::class.java) {validateRandomizationJson(invalid)}
    }
}
