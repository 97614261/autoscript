package com.autoscript.studio

import com.autoscript.project.store.ProjectDesign
import com.autoscript.project.store.ProjectDebugSettings
import com.autoscript.project.store.ProjectManifestDocument
import com.autoscript.project.store.ProjectFlow
import com.autoscript.project.store.ProjectSnapshot
import com.autoscript.project.store.ProjectSourceMode
import com.autoscript.project.store.ProjectVariable
import com.autoscript.project.store.ProjectVariableScope
import com.autoscript.project.store.ProjectVariableType
import com.autoscript.project.store.RunPromptFilters
import com.autoscript.runtime.api.RuntimeProtocol
import com.autoscript.runtime.client.RuntimeProjectResourceKind
import com.google.gson.JsonObject
import java.io.File
import java.security.MessageDigest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class RuntimeProjectPlanTest {
    @Test
    fun configurationSeedsTypedValuesAndSingleStepSkipsUiEvents() {
        val root = temporaryFolder.newFolder("ui-plan")
        val model = com.autoscript.script.ui.ScriptUiDefinition(version = 2, fields = listOf(
            com.autoscript.script.ui.UiField("count", "次数", "integer", false, "3", ui = com.autoscript.script.ui.UiPresentation(control = "NUMBER")),
        ))
        val snap = snapshot(root, emptyList()).let { it.copy(manifest = it.manifest.copy(runnerUi = model.toJson())) }
        val plan = RuntimeProjectPlan.fromSnapshot(snap, "return function() end\n")
        assertTrue(plan.luaSource.toString(Charsets.UTF_8).contains("if RunnerConfig == nil then"))
        assertTrue(plan.withInterface(snap, mapOf("count" to "12")).luaSource.toString(Charsets.UTF_8).startsWith("RunnerConfig={[\"count\"]=12}"))
        val visual = visualSnapshot(root).let { it.copy(manifest = it.manifest.copy(runnerUi = model.toJson())) }
        val direct = plan.forEditorRun(visual).luaSource.toString(Charsets.UTF_8)
        assertTrue(direct.startsWith("__ui_debug_skip_events = true\n"))
        assertTrue(direct.contains("RunnerConfig={[\"count\"]=3}"))
        assertFalse(direct.contains("__autoscript_debug_step = true"))
        assertTrue(plan.withInterface(visual, mapOf("count" to "12")).luaSource.toString(Charsets.UTF_8).contains("__ui_debug_skip_events = false\n"))
        assertArrayEquals(plan.luaSource, plan.forEditorRun(snap).luaSource)
        assertArrayEquals(plan.luaSource, plan.forEditorRun(visual.copy(manifest = visual.manifest.copy(runnerUi = null))).luaSource)
        val debug = RuntimeProjectPlan("__autoscript_debug_capture = true\n__autoscript_debug_step = false\n".toByteArray(), emptyList(), emptyList(),720,1280,0)
        assertTrue(debug.forSingleStep().luaSource.toString(Charsets.UTF_8).startsWith("__ui_debug_skip_events = true\n"))
    }

    @Test
    fun selectedStepUsesTargetCheckpointWithoutChangingOriginalArtifact() {
        val original = ("__autoscript_debug_capture = true\n__autoscript_debug_step = false\n" +
            "local function __checkpoint(flow,node) if __autoscript_debug_execute_target then end end\n" +
            "__checkpoint(\"main\", \"node-2\", {}, {})\n").toByteArray()
        val plan = RuntimeProjectPlan(original, emptyList(), emptyList(), 720, 1280, 0)
        val selected = plan.forSingleStep("main", "node-2").luaSource.toString(Charsets.UTF_8)
        assertTrue(selected.contains("__autoscript_debug_target_flow = \"main\""))
        assertTrue(selected.contains("__autoscript_debug_target_node = \"node-2\""))
        assertTrue(selected.contains("__autoscript_debug_step = false"))
        assertTrue(selected.contains("__autoscript_debug_execute_target = true"))
        assertTrue(plan.forSingleStep("main", "node-2", executeTarget = false).luaSource.toString(Charsets.UTF_8)
            .contains("__autoscript_debug_execute_target = false"))
        assertArrayEquals(original, plan.luaSource)
        assertTrue(runCatching { plan.forSingleStep("main", "missing") }.isFailure)
    }

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

        assertTrue(plan.luaSource.toString(Charsets.UTF_8).endsWith("return 1"))
        assertTrue(plan.luaSource.toString(Charsets.UTF_8).contains("Prompt.toast = function(message, styleJson)"))
        assertTrue(plan.luaSource.toString(Charsets.UTF_8).contains("widthPx"))
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

    @Test
    fun hidesOnlyRuntimePromptsWhenProjectSettingIsDisabled() {
        val root = temporaryFolder.newFolder("project-hide-prompts")
        val source = "return function() Prompt.show('visible only when enabled'); Log.info('still logged') end\n"
        val initial = snapshot(root, resources = emptyList())
        val snapshot = initial.copy(
            manifest = initial.manifest.copy(
                debugSettings = ProjectDebugSettings(showRunPrompts = false),
            ),
            luaSource = source,
            luaSources = mapOf("main.lua" to source),
        )

        val plan = RuntimeProjectPlan.fromSnapshot(snapshot, source).luaSource.toString(Charsets.UTF_8)

        assertTrue(plan.contains("Prompt.show = function(_) end"))
        assertTrue(plan.contains("Prompt.toast = function(_) end"))
        assertTrue(plan.contains("Prompt.show('visible only when enabled')"))
        assertTrue(plan.contains("Log.info('still logged')"))
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

        assertTrue(plan.luaSource.toString(Charsets.UTF_8).contains("__autoscript_debug_event = function() end\n"))
        // Frozen visual Lua already owns its style; Studio must not add a second wrapper.
        assertFalse(plan.luaSource.toString(Charsets.UTF_8).contains("Prompt.toast = function(message, styleJson)"))
        assertTrue(plan.luaSource.toString(Charsets.UTF_8).endsWith(lua.toString(Charsets.UTF_8)))
        assertEquals(RuntimeProtocol.SCALE_LETTERBOX, plan.scaleMode)
    }

    @Test
    fun freezesVisualPromptCategoriesAndTypedVariableFilter() {
        val root = temporaryFolder.newFolder("project-prompt-filters")
        val generated = File(root, "generated").apply { mkdirs() }
        val generationId = "gen-prompt-filters"
        val lua = "-- generationId: $generationId\nreturn function() end\n".toByteArray()
        val sourceMap = """{"generationId":"$generationId"}""".toByteArray()
        File(generated, "main.lua").writeBytes(lua)
        File(generated, "source-map.json").writeBytes(sourceMap)
        File(generated, "generation.json").writeText(
            """{"generationId":"$generationId","luaDigest":"${sha256(lua)}","sourceMapDigest":"${sha256(sourceMap)}"}""",
        )
        val initial = visualSnapshot(root)
        val snapshot = initial.copy(manifest = initial.manifest.copy(
            variables = listOf(ProjectVariable("score", ProjectVariableScope.FLOW, "main", ProjectVariableType.INTEGER)),
            debugSettings = ProjectDebugSettings(runPromptFilters = RunPromptFilters(
                loops = true, variables = true, variableScope = "flow:main",
                variableType = "integer", variableName = "score",
            )),
        ))

        val source = RuntimeProjectPlan.fromVisualSnapshot(snapshot, generationId).luaSource.toString(Charsets.UTF_8)

        assertTrue(source.contains("loop=true"))
        assertTrue(source.contains("variable=true"))
        assertTrue(source.contains("__autoscript_variable_meta[\"main:score\"]"))
        assertTrue(source.contains("__autoscript_scope = \"flow:main\""))
        assertTrue(source.contains("__autoscript_name = \"score\""))
        assertArrayEquals(lua, File(generated, "main.lua").readBytes())
    }

    @Test
    fun floatingRunUsesSelectedFlowWithoutChangingGeneratedArtifact() {
        val root = temporaryFolder.newFolder("project-current-flow")
        val generated = File(root, "generated").apply { mkdirs() }
        // The Rust compiler test verifies these exact bytes; do not invent an entry shape here.
        val lua = requireNotNull(javaClass.getResourceAsStream("/compiler-plugin-v2.lua")).use { it.readBytes() }
        val generationId = lua.toString(Charsets.UTF_8).lineSequence()
            .first { it.startsWith("-- generationId: ") }.removePrefix("-- generationId: ")
        val sourceMap = """{"generationId":"$generationId"}""".toByteArray()
        File(generated, "main.lua").writeBytes(lua)
        File(generated, "source-map.json").writeBytes(sourceMap)
        File(generated, "generation.json").writeText(
            """{"generationId":"$generationId","luaDigest":"${sha256(lua)}","sourceMapDigest":"${sha256(sourceMap)}"}""",
        )
        val initial = visualSnapshot(root)
        val snapshot = initial.copy(
            manifest = initial.manifest.copy(
                flows = initial.manifest.flows + ProjectFlow("child", "visual/flows/child.jsonl", "block-child"),
            ),
        )

        val plan = RuntimeProjectPlan.fromVisualSnapshot(snapshot, generationId, "child")

        assertTrue(plan.luaSource.toString(Charsets.UTF_8).endsWith(
            "  __flows[\"child\"](__entry_args or {})\n  return nil\nend\n",
        ))
        assertArrayEquals(lua, File(generated, "main.lua").readBytes())
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
        assertTrue(source.contains("local require\n"))
        assertTrue(source.contains("require = function(name)"))
        assertTrue(source.indexOf("local require\n") < source.indexOf("local __autoscript_modules"))
        assertTrue(source.contains("Lua 模块循环依赖"))
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
