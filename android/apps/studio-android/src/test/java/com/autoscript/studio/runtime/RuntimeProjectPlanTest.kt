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
    fun nodeTestArtifactIsSeparateAndRejectsStaleSourceAndIdentity() {
        val root = temporaryFolder.newFolder("node-test")
        val savedSource = """{"flowSchemaVersion":1,"nodeId":"selected","blockId":"root","parentId":null,"orderKey":"a0","kind":"task.noop","nodeVersion":1,"depth":0,"args":{}}""" + "\n"
        val snap = visualSnapshot(root).copy(flowSources = mapOf("main" to savedSource),
            manifest = visualSnapshot(root).manifest.copy(debugSettings = ProjectDebugSettings(runDelayMs = 5000,
                runPromptFilters = RunPromptFilters(flowStart = true,flowReturn = true,variables = true))))
        File(root, "project.json").writeText("saved-manifest")
        val generated = File(root, "generated-node-test").apply { mkdirs() }
        val generation = "gen-node-test"
        val lua = "-- generationId: $generation\nreturn function() end\n".toByteArray()
        val sourceMap = "{\"generationId\":\"$generation\"}".toByteArray()
        File(generated, "main.lua").writeBytes(lua)
        File(generated, "source-map.json").writeBytes(sourceMap)
        File(generated, "generation.json").writeText("""{"generationId":"$generation","luaDigest":"${sha256(lua)}","sourceMapDigest":"${sha256(sourceMap)}"}""")
        File(generated, "test-source.json").writeText("""{"flowId":"main","nodeId":"selected","manifestDigest":"${sha256("saved-manifest".toByteArray())}","flowDigests":{"main":"${sha256(savedSource.toByteArray())}"}}""")
        val plan = RuntimeProjectPlan.fromVisualSnapshot(snap, generation, "main", "selected")
        assertTrue(plan.luaSource.toString(Charsets.UTF_8).contains("__ui_debug_skip_events = true"))
        assertFalse("Idle single-step must not wait through the project's startup delay",
            plan.luaSource.toString(Charsets.UTF_8).contains("-- @autoscript debug run delay"))
        assertTrue(plan.luaSource.toString(Charsets.UTF_8).startsWith("-- @autoscript selected-node prompt preview\n"))
        assertTrue(plan.luaSource.toString(Charsets.UTF_8).contains("Prompt.toast(message, styleJson or"))
        assertTrue(plan.luaSource.toString(Charsets.UTF_8).contains("flowStart=false, flowReturn=false"))
        assertTrue(snap.manifest.debugSettings.runPromptFilters.flowStart)
        assertArrayEquals(lua, File(generated, "main.lua").readBytes())
        assertFalse(plan.luaSource.toString(Charsets.UTF_8).contains("__autoscript_debug_target_node"))
        val hidden = snap.copy(manifest = snap.manifest.copy(debugSettings = snap.manifest.debugSettings.copy(showRunPrompts = false)))
        val hiddenPlan = RuntimeProjectPlan.fromVisualSnapshot(hidden, generation, "main", "selected")
        assertFalse(hiddenPlan.luaSource.toString(Charsets.UTF_8).contains("selected-node prompt preview"))
        assertTrue(hiddenPlan.luaSource.toString(Charsets.UTF_8).contains("Prompt.show = function(_) end"))
        assertFalse(File(root, "generated").exists())
        org.junit.Assert.assertThrows(IllegalArgumentException::class.java) { RuntimeProjectPlan.fromVisualSnapshot(snap, generation, "main", "other") }
        org.junit.Assert.assertThrows(IllegalArgumentException::class.java) { RuntimeProjectPlan.fromVisualSnapshot(snap.copy(flowSources = mapOf("main" to "new source")), generation, "main", "selected") }
        File(root, "project.json").writeText("changed-manifest")
        org.junit.Assert.assertThrows(IllegalArgumentException::class.java) { RuntimeProjectPlan.fromVisualSnapshot(snap, generation, "main", "selected") }
    }

    @Test
    fun configurationSeedsTypedValuesAndEditorRunSkipsUiEvents() {
        val root = temporaryFolder.newFolder("ui-plan")
        val model = com.autoscript.script.ui.ScriptUiDefinition(version = 2, fields = listOf(
            com.autoscript.script.ui.UiField("count", "次数", "integer", false, "3", ui = com.autoscript.script.ui.UiPresentation(control = "NUMBER")),
        ))
        val snap = snapshot(root, emptyList()).let { it.copy(manifest = it.manifest.copy(runnerUi = model.toJson())) }
        val plan = RuntimeProjectPlan.fromSnapshot(snap, "return function() end\n")
        assertEquals(model.toJson(), com.google.gson.JsonParser.parseString(requireNotNull(plan.scriptUiJson)))
        assertEquals(plan.scriptUiJson, plan.forEditorRun(snap).scriptUiJson)
        assertEquals(null, RuntimeProjectPlan.fromSnapshot(snap.copy(manifest = snap.manifest.copy(runnerUi = null)), "return function() end\n").scriptUiJson)
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
    }

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun freezesPixelPositionForBothPromptChannelsWithoutChangingOriginalSource() {
        val root = temporaryFolder.newFolder("prompt-pixels")
        val source = "return function() Prompt.show('status'); Prompt.toast('tip') end\n"
        val initial = snapshot(root, emptyList())
        val settings = ProjectDebugSettings(popupStyle = com.autoscript.project.store.PopupStyle(
            widthPx = 100, heightPx = 30, xPx = 20, yPx = 80,
        ))
        val plan = RuntimeProjectPlan.fromSnapshot(initial.copy(manifest = initial.manifest.copy(debugSettings = settings)), source)
            .luaSource.toString(Charsets.UTF_8)
        assertTrue(plan.contains("Prompt.show = function(message, styleJson)"))
        assertTrue(plan.contains("Prompt.toast = function(message, styleJson)"))
        assertTrue(plan.contains("\\\"xPx\\\":20"))
        assertTrue(plan.contains("\\\"yPx\\\":80"))
        assertTrue(plan.endsWith(source))
    }

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
        val sourceMap = """{"schemaVersion":1,"generationId":"$generationId","entries":[],"visionRequirements":{"version":1,"components":["opencv"]}}
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
        assertEquals(setOf("opencv"), plan.requiredVisionComponents)
        assertEquals(setOf("opencv"), plan.forEditorRun(snapshot).requiredVisionComponents)
        File(generated, "source-map.json").writeText(sourceMap.toString(Charsets.UTF_8).replace("opencv", "onnx"))
        org.junit.Assert.assertThrows(IllegalArgumentException::class.java) {
            RuntimeProjectPlan.fromVisualSnapshot(snapshot, generationId)
        }
    }

    @Test
    fun pointerPreflightUsesDigestVerifiedEmittedIdentitiesInsteadOfEverySavedNode() {
        val root = temporaryFolder.newFolder("emitted-input")
        val generated = File(root, "generated").apply { mkdirs() }
        val generation = "gen-emitted-input"
        val lua = "-- generationId: $generation\nreturn function() end\n".toByteArray()
        val source = """{"nodeId":"selected","kind":"task.prompt","args":{}}
{"nodeId":"not-tested","kind":"input.pointerdown","args":{}}
"""
        val snap = visualSnapshot(root).copy(flowSources = mapOf("main" to source))
        File(generated, "main.lua").writeBytes(lua)
        fun artifact(id: String) {
            val positions = """{"generationId":"$generation","entries":[{"flowId":"main","nodeId":"$id"}]}""".toByteArray()
            File(generated, "source-map.json").writeBytes(positions)
            File(generated, "generation.json").writeText(
                """{"generationId":"$generation","luaDigest":"${sha256(lua)}","sourceMapDigest":"${sha256(positions)}"}""")
        }
        artifact("selected")
        assertFalse(RuntimeProjectPlan.fromVisualSnapshot(snap, generation).requiresPointerInput)
        artifact("not-tested")
        assertTrue(RuntimeProjectPlan.fromVisualSnapshot(snap, generation).requiresPointerInput)
        File(generated, "source-map.json").writeText("""{"generationId":"$generation","entries":[]}""")
        org.junit.Assert.assertThrows(IllegalArgumentException::class.java) {
            RuntimeProjectPlan.fromVisualSnapshot(snap, generation)
        }
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
