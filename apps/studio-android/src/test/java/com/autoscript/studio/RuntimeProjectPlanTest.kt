package com.autoscript.studio

import com.autoscript.project.store.ProjectDesign
import com.autoscript.project.store.ProjectDebugSettings
import com.autoscript.project.store.ProjectManifestDocument
import com.autoscript.project.store.ProjectFlow
import com.autoscript.project.store.ProjectSnapshot
import com.autoscript.project.store.ProjectSourceMode
import com.autoscript.runtime.api.RuntimeProtocol
import com.autoscript.runtime.client.RuntimeProjectResourceKind
import com.google.gson.JsonObject
import java.io.File
import java.security.MessageDigest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class RuntimeProjectPlanTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun mapsImagesDictionariesDesignAndStableResourceOrder() {
        val root = temporaryFolder.newFolder("project-1")
        File(root, "assets/images").mkdirs()
        File(root, "dictionaries").mkdirs()
        File(root, "assets/images/button.png").writeBytes(byteArrayOf(1))
        File(root, "dictionaries/chinese.asglyph").writeBytes(byteArrayOf(2))
        val snapshot = snapshot(
            root,
            resources = listOf(
                resource("glyphDictionary", "dictionaries/chinese.asglyph"),
                resource("image", "assets/images/button.png"),
            ),
            design = ProjectDesign(1080, 1920, "crop", "portrait"),
        )

        val plan = RuntimeProjectPlan.fromSnapshot(snapshot, "return 1")

        assertArrayEquals("return 1".toByteArray(), plan.luaSource)
        assertEquals(1080, plan.designWidth)
        assertEquals(1920, plan.designHeight)
        assertEquals(RuntimeProtocol.SCALE_CROP, plan.scaleMode)
        assertEquals(listOf("core.task"), plan.capabilities)
        assertEquals(
            listOf("assets/images/button.png", "dictionaries/chinese.asglyph"),
            plan.resources.map { it.path },
        )
        assertEquals(RuntimeProjectResourceKind.IMAGE, plan.resources[0].kind)
        assertEquals(RuntimeProjectResourceKind.GLYPH_DICTIONARY, plan.resources[1].kind)
    }

    @Test
    fun appliesProjectDebugDelayInsideTheCancellableLuaEntry() {
        val root = temporaryFolder.newFolder("project-delay")
        val source = "return function() Log.info('ready') end\n"
        val initial = snapshot(root, resources = emptyList())
        val snapshot = initial.copy(
            manifest = initial.manifest.copy(debugSettings = ProjectDebugSettings(runDelayMs = 500)),
            luaSource = source,
            luaSources = mapOf("main.lua" to source),
        )

        val plan = RuntimeProjectPlan.fromSnapshot(snapshot, source).luaSource.toString(Charsets.UTF_8)

        assertTrue(plan.contains("Task.sleep(500)"))
        assertTrue(plan.contains("__autoscript_entry()"))
        assertTrue(plan.contains("Log.info('ready')"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsTraversalResourcePath() {
        val root = temporaryFolder.newFolder("project-2")
        RuntimeProjectPlan.fromSnapshot(
            snapshot(root, listOf(resource("image", "assets/images/../../outside.png"))),
            "return 1",
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsMissingDeclaredResource() {
        val root = temporaryFolder.newFolder("project-3")
        RuntimeProjectPlan.fromSnapshot(
            snapshot(root, listOf(resource("image", "assets/images/missing.png"))),
            "return 1",
        )
    }

    @Test
    fun acceptsDigestBoundVisualGeneration() {
        val root = temporaryFolder.newFolder("project-4")
        val generated = File(root, "generated").apply { mkdirs() }
        val generationId = "gen-0123456789abcdef"
        val lua = "-- @generated\n-- generationId: $generationId\nreturn function() end\n"
            .toByteArray()
        val sourceMap = """{"schemaVersion":1,"generationId":"$generationId","entries":[]}
""".toByteArray()
        File(generated, "main.lua").writeBytes(lua)
        File(generated, "source-map.json").writeBytes(sourceMap)
        File(generated, "generation.json").writeText(
            """{"generationId":"$generationId","luaDigest":"${sha256(lua)}","sourceMapDigest":"${sha256(sourceMap)}"}""",
        )
        val snapshot = visualSnapshot(root)

        val plan = RuntimeProjectPlan.fromVisualSnapshot(snapshot, generationId)

        assertArrayEquals(lua, plan.luaSource)
        assertEquals(RuntimeProtocol.SCALE_LETTERBOX, plan.scaleMode)
    }

    @Test
    fun freezesDeclaredLuaModulesBehindRestrictedRequire() {
        val root = temporaryFolder.newFolder("project-modules")
        val entry = "local math = require(\"tools.math\")\nreturn function() return math.answer end\n"
        val snapshot = ProjectSnapshot(
            directory = root,
            manifest = ProjectManifestDocument(
                projectId = root.name,
                name = "modules",
                sourceMode = ProjectSourceMode.LUA,
                entryPoint = "main.lua",
                luaFiles = listOf("lua/tools/math.lua", "main.lua"),
                luaDirectories = listOf("lua", "lua/tools"),
            ),
            luaSource = entry,
            luaSources = mapOf(
                "main.lua" to entry,
                "lua/tools/math.lua" to "return { answer = 42 }\n",
            ),
            flowSources = emptyMap(),
        )

        val source = RuntimeProjectPlan.fromSnapshot(snapshot, entry).luaSource.toString(Charsets.UTF_8)

        assertTrue(source.contains("local __autoscript_modules"))
        assertTrue(source.contains("[\"tools.math\"]"))
        assertTrue(source.contains("local function require(name)"))
        assertTrue(source.contains("Lua 模块不存在"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsTamperedVisualGeneration() {
        val root = temporaryFolder.newFolder("project-5")
        val generated = File(root, "generated").apply { mkdirs() }
        val generationId = "gen-0123456789abcdef"
        val original = "-- generationId: $generationId\nreturn function() end\n".toByteArray()
        val tampered = "-- generationId: $generationId\nreturn function() return 1 end\n".toByteArray()
        val sourceMap = """{"generationId":"$generationId"}""".toByteArray()
        File(generated, "main.lua").writeBytes(tampered)
        File(generated, "source-map.json").writeBytes(sourceMap)
        File(generated, "generation.json").writeText(
            """{"generationId":"$generationId","luaDigest":"${sha256(original)}","sourceMapDigest":"${sha256(sourceMap)}"}""",
        )

        RuntimeProjectPlan.fromVisualSnapshot(visualSnapshot(root), generationId)
    }

    private fun snapshot(
        root: File,
        resources: List<JsonObject>,
        design: ProjectDesign = ProjectDesign(),
    ) = ProjectSnapshot(
        directory = root,
        manifest = ProjectManifestDocument(
            projectId = root.name,
            name = "test",
            sourceMode = ProjectSourceMode.LUA,
            entryPoint = "main.lua",
            resources = resources,
            design = design,
        ),
        luaSource = "return 1",
        flowSources = emptyMap(),
    )

    private fun resource(kind: String, path: String) = JsonObject().apply {
        addProperty("kind", kind)
        addProperty("path", path)
    }

    private fun visualSnapshot(root: File) = ProjectSnapshot(
        directory = root,
        manifest = ProjectManifestDocument(
            projectId = root.name,
            name = "visual",
            sourceMode = ProjectSourceMode.VISUAL,
            entryFlowId = "main",
            flows = listOf(
                ProjectFlow("main", "visual/flows/main.jsonl", "block-main"),
            ),
        ),
        luaSource = null,
        flowSources = mapOf("main" to ""),
    )

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") {
            (it.toInt() and 0xff).toString(16).padStart(2, '0')
        }
}
