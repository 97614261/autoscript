package com.autoscript.studio

import com.autoscript.project.store.ProjectSnapshot
import com.autoscript.project.store.ProjectSourceMode
import com.autoscript.project.store.MAX_LUA_SOURCE_BYTES
import com.autoscript.project.store.ProjectDebugSettings
import com.autoscript.project.store.ProjectVariableScope
import com.autoscript.runtime.api.RuntimeProtocol
import com.autoscript.runtime.client.RuntimeProjectResource
import com.autoscript.runtime.client.RuntimeProjectResourceKind
import java.io.File
import java.security.MessageDigest
import com.google.gson.JsonParser
import com.autoscript.script.ui.luaConfiguration

internal data class RuntimeProjectPlan(
    val luaSource: ByteArray,
    val resources: List<RuntimeProjectResource>,
    val capabilities: List<String>,
    val designWidth: Int,
    val designHeight: Int,
    val scaleMode: Int,
    val requiresPointerInput: Boolean = false,
) {
    fun withInterface(snapshot: ProjectSnapshot, values: Map<String, String>?): RuntimeProjectPlan {
        if (values == null) return this
        val model = com.autoscript.script.ui.ScriptUiDefinition.parse(requireNotNull(snapshot.manifest.runnerUi).toString())
        val eventMode = if (snapshot.manifest.sourceMode == ProjectSourceMode.VISUAL) "__ui_debug_skip_events = false\n" else ""
        return copy(luaSource = (model.luaConfiguration(values) + eventMode + luaSource.toString(Charsets.UTF_8)).toByteArray(Charsets.UTF_8))
    }
    /** Direct editor execution uses the seeded defaults, not a hidden UI event dispatcher. */
    fun forEditorRun(snapshot: ProjectSnapshot): RuntimeProjectPlan =
        if (snapshot.manifest.sourceMode == ProjectSourceMode.VISUAL && snapshot.manifest.runnerUi != null) {
            copy(luaSource = ("__ui_debug_skip_events = true\n" + luaSource.toString(Charsets.UTF_8)).toByteArray(Charsets.UTF_8))
        } else this
    fun forSingleStep(flowId: String? = null, nodeId: String? = null, executeTarget: Boolean = true): RuntimeProjectPlan {
        val source = "__ui_debug_skip_events = true\n" + luaSource.toString(Charsets.UTF_8)
        require(source.contains("__autoscript_debug_capture = true\n__autoscript_debug_step = false\n")) { "单步仅支持可视化插件" }
        if (nodeId != null) {
            require(!flowId.isNullOrBlank()) { "请选择当前插件中的积木" }
            require(flowId.length <= 128 && nodeId.length <= 128) { "插件或积木标识过长" }
            val flow = com.google.gson.JsonPrimitive(flowId).toString()
            val node = com.google.gson.JsonPrimitive(nodeId).toString()
            require(source.contains("__autoscript_debug_execute_target") && source.contains("__checkpoint($flow, $node,")) {
                "所选积木不可执行（可能已禁用），请重新编译并选择"
            }
            return copy(luaSource = source.replaceFirst("__autoscript_debug_step = false\n",
                "__autoscript_debug_step = false\n__autoscript_debug_target_flow = $flow\n__autoscript_debug_target_node = $node\n__autoscript_debug_execute_target = $executeTarget\n").toByteArray(Charsets.UTF_8))
        }
        return copy(luaSource = source.replaceFirst("__autoscript_debug_step = false\n", "__autoscript_debug_step = true\n").toByteArray(Charsets.UTF_8))
    }
    companion object {
        fun fromSnapshot(snapshot: ProjectSnapshot, source: String): RuntimeProjectPlan {
            require(snapshot.manifest.sourceMode == ProjectSourceMode.LUA) {
                "当前批次只能运行手写Lua项目"
            }
            val entryPath = requireNotNull(snapshot.manifest.entryPoint) { "Lua 项目缺少入口" }
            val files = snapshot.luaSources + (entryPath to source)
            return create(snapshot, bundleLuaProject(snapshot, files, entryPath).toByteArray(Charsets.UTF_8))
        }

        fun fromVisualSnapshot(
            snapshot: ProjectSnapshot,
            expectedGenerationId: String,
            selectedFlowId: String? = null,
        ): RuntimeProjectPlan {
            require(snapshot.manifest.sourceMode == ProjectSourceMode.VISUAL) {
                "当前项目不是可视化项目"
            }
            val generated = containedDirectory(snapshot.directory, "generated")
            val lua = readBounded(File(generated, "main.lua"), MAX_LUA_SOURCE_BYTES)
            val sourceMap = readBounded(File(generated, "source-map.json"), MAX_SOURCE_MAP_BYTES)
            val recordBytes = readBounded(File(generated, "generation.json"), MAX_RECORD_BYTES)
            val record = JsonParser.parseString(recordBytes.toString(Charsets.UTF_8)).asJsonObject
            val generationId = record.requiredString("generationId")
            require(generationId == expectedGenerationId) { "生成代次已变化，请重新编译" }
            require(record.requiredString("luaDigest") == sha256(lua)) { "生成Lua摘要不匹配" }
            require(record.requiredString("sourceMapDigest") == sha256(sourceMap)) {
                "source map摘要不匹配"
            }
            val sourceMapGeneration = JsonParser.parseString(
                sourceMap.toString(Charsets.UTF_8),
            ).asJsonObject.requiredString("generationId")
            require(sourceMapGeneration == generationId) { "source map代次不匹配" }
            val expectedHeader = "-- generationId: $generationId\n".toByteArray(Charsets.UTF_8)
            require(lua.indexOf(expectedHeader) >= 0) { "生成Lua代次不匹配" }
            return create(snapshot, withSelectedVisualEntry(snapshot, lua, selectedFlowId))
        }

        /** The generated artifact stays digest-bound on disk; the editor may select another Flow only for this run. */
        private fun withSelectedVisualEntry(
            snapshot: ProjectSnapshot,
            generatedLua: ByteArray,
            selectedFlowId: String?,
        ): ByteArray {
            if (selectedFlowId == null || selectedFlowId == snapshot.manifest.entryFlowId) return generatedLua
            val selected = snapshot.manifest.flows.singleOrNull { it.flowId == selectedFlowId }
                ?: throw IllegalArgumentException("当前插件不在项目中：$selectedFlowId")
            require(selected.params.none { it.get("required")?.asBoolean == true }) {
                "当前插件有必填参数，请从入口插件调用它"
            }
            val originalEntry = requireNotNull(snapshot.manifest.entryFlowId) { "项目缺少入口插件" }
            val eventUi = snapshot.manifest.runnerUi?.getAsJsonArray("fields")?.any { field ->
                val ui = field.asJsonObject.getAsJsonObject("ui")
                ui?.getAsJsonObject("events")?.entrySet()?.any { it.value.asString.startsWith("flow:") } == true || ui?.get("binding")?.takeUnless { it.isJsonNull }?.asString?.let { !it.startsWith("param:") } == true
            } == true
            val original = if (eventUi) "  Task.spawn(function() __flows[${com.google.gson.JsonPrimitive(originalEntry)}](__entry_args or {}) end)\n  __run_ui_events()\n  return nil\nend\n"
                else "  __flows[${com.google.gson.JsonPrimitive(originalEntry)}](__entry_args or {})\n  return nil\nend\n"
            val source = generatedLua.toString(Charsets.UTF_8)
            require(source.endsWith(original)) { "生成Lua入口结构已变化，请重新编译" }
            val replacement = original.replace("__flows[${com.google.gson.JsonPrimitive(originalEntry)}]", "__flows[${com.google.gson.JsonPrimitive(selectedFlowId)}]")
            val prefix = source.dropLast(original.length).replace(
                "if __ui_debug_skip_events then __flows[${com.google.gson.JsonPrimitive(originalEntry)}]",
                "if __ui_debug_skip_events then __flows[${com.google.gson.JsonPrimitive(selectedFlowId)}]",
            )
            return (prefix + replacement).toByteArray(Charsets.UTF_8)
        }

        private fun create(snapshot: ProjectSnapshot, luaSource: ByteArray): RuntimeProjectPlan {
            require(luaSource.size <= MAX_LUA_SOURCE_BYTES) { "Lua源码超过16 MiB" }
            val projectRoot = snapshot.directory.canonicalFile
            require(projectRoot.isDirectory) { "项目目录不存在" }
            val resources = snapshot.manifest.resources.map { declaration ->
                val kindText = declaration.get("kind")?.asString
                    ?: throw IllegalArgumentException("资源缺少kind")
                val path = declaration.get("path")?.asString
                    ?: throw IllegalArgumentException("资源缺少path")
                require(isCanonicalResourcePath(path)) { "资源路径无效：$path" }
                val file = File(projectRoot, path).canonicalFile
                require(file.path.startsWith(projectRoot.path + File.separator) && file != projectRoot) {
                    "资源路径越界：$path"
                }
                require(file.isFile && file.canRead()) { "资源不存在或不可读：$path" }
                RuntimeProjectResource(
                    kind = when (kindText) {
                        "image" -> RuntimeProjectResourceKind.IMAGE
                        "glyphDictionary" -> RuntimeProjectResourceKind.GLYPH_DICTIONARY
                        else -> throw IllegalArgumentException("不支持的资源类型：$kindText")
                    },
                    path = path,
                    file = file,
                )
            }.sortedBy(RuntimeProjectResource::path)
            val design = snapshot.manifest.design
            val capabilities = snapshot.manifest.capabilities.sorted()
            require(capabilities.size <= MAX_PROJECT_CAPABILITIES) { "项目能力数量超过64" }
            require(capabilities.distinct().size == capabilities.size) { "项目能力声明重复" }
            require(capabilities.all { it.length <= 128 && CAPABILITY.matches(it) }) {
                "项目能力声明格式无效"
            }
            val debugSettings = snapshot.manifest.debugSettings
            val withPrompts = withRunPromptVisibility(luaSource, debugSettings.showRunPrompts)
            // Visual compilation already freezes style and accounts for its source-map lines.
            val withToastStyle = if (snapshot.manifest.sourceMode == ProjectSourceMode.VISUAL) {
                withPrompts
            } else withPopupStyle(withPrompts, debugSettings)
            val baseSource = withRunDelay(
                    if (snapshot.manifest.sourceMode == ProjectSourceMode.VISUAL) {
                        "__autoscript_debug_capture = true\n__autoscript_debug_step = false\n".toByteArray() +
                            withVisualRunPromptEvents(withToastStyle, snapshot, debugSettings)
                    } else withToastStyle,
                    debugSettings.runDelayMs,
                )
            val ui = snapshot.manifest.runnerUi?.let { com.autoscript.script.ui.ScriptUiDefinition.parse(it.toString()) }
            val frozenSource = if (ui == null) baseSource else
                ("if RunnerConfig == nil then\n" + ui.luaConfiguration(ui.initialValues()) + "end\n").toByteArray(Charsets.UTF_8) + baseSource
            require(frozenSource.size <= MAX_LUA_SOURCE_BYTES) { "运行计划Lua源码超过16 MiB" }
            return RuntimeProjectPlan(
                luaSource = frozenSource,
                resources = resources,
                capabilities = capabilities,
                requiresPointerInput = snapshot.manifest.sourceMode == ProjectSourceMode.VISUAL &&
                    snapshot.flowSources.values.any { source -> source.lineSequence().filter(String::isNotBlank).any { line ->
                        val node = JsonParser.parseString(line).asJsonObject
                        node.get("kind")?.asString in setOf("input.pointerdown", "input.pointermove", "input.pointerup") ||
                            (node.get("kind")?.asString == "vision.findimage" && node.getAsJsonObject("args")?.get("successAction")?.asString in setOf("hold", "pressRelease"))
                    } },
                designWidth = design.width,
                designHeight = design.height,
                scaleMode = when (design.scaleMode) {
                    "letterbox" -> RuntimeProtocol.SCALE_LETTERBOX
                    "crop" -> RuntimeProtocol.SCALE_CROP
                    "stretch" -> RuntimeProtocol.SCALE_STRETCH
                    else -> throw IllegalArgumentException("不支持的缩放模式：${design.scaleMode}")
                },
            )
        }

        /** Suppress only user-facing prompts when the project debug setting hides that channel. */
        private fun withRunPromptVisibility(source: ByteArray, showRunPrompts: Boolean): ByteArray {
            if (showRunPrompts) return source
            val prefix = "-- @autoscript debug hide run prompts\nPrompt.show = function(_) end\nPrompt.toast = function(_) end\n"
            return (prefix + source.toString(Charsets.UTF_8)).toByteArray(Charsets.UTF_8)
        }

        private fun withPopupStyle(source: ByteArray, settings: ProjectDebugSettings): ByteArray {
            val styleJson = com.google.gson.Gson().toJson(settings.popupStyle)
            val prefix = buildString {
                append("-- @autoscript project popup style\n")
                append("local __autoscript_toast_impl = Prompt.toast\n")
                append("Prompt.toast = function(message, styleJson) return __autoscript_toast_impl(message, styleJson or ")
                append(com.google.gson.JsonPrimitive(styleJson)).append(") end\n")
            }
            return (prefix + source.toString(Charsets.UTF_8)).toByteArray(Charsets.UTF_8)
        }

        /** The compiler emits typed debug hooks; bind them to this project's selected categories. */
        private fun withVisualRunPromptEvents(
            source: ByteArray,
            snapshot: ProjectSnapshot,
            settings: ProjectDebugSettings,
        ): ByteArray {
            val filters = settings.runPromptFilters
            if (!settings.showRunPrompts || !listOf(
                filters.loops, filters.jumps, filters.flowStart, filters.flowReturn,
                filters.imageSearch, filters.variables,
            ).any { it }) return ("__autoscript_debug_event = function() end\n" +
                source.toString(Charsets.UTF_8)).toByteArray(Charsets.UTF_8)
            val metadata = buildString {
                append("local __autoscript_variable_meta = {}\n")
                snapshot.manifest.variables.sortedWith(compareBy({ it.scope == ProjectVariableScope.FLOW }, { it.name }))
                    .forEach { variable ->
                        val flows = if (variable.scope == ProjectVariableScope.GLOBAL) {
                            snapshot.manifest.flows.map { it.flowId }
                        } else listOfNotNull(variable.flowId)
                        flows.forEach { flowId ->
                            val key = "$flowId:${variable.name}"
                            val scope = if (variable.scope == ProjectVariableScope.GLOBAL) "global" else "flow:$flowId"
                            append("__autoscript_variable_meta[").append(com.google.gson.JsonPrimitive(key))
                                .append("] = {scope=").append(com.google.gson.JsonPrimitive(scope))
                                .append(", kind=").append(com.google.gson.JsonPrimitive(variable.type.name.lowercase()))
                                .append("}\n")
                        }
                    }
            }
            val prefix = buildString {
                append("-- @autoscript project run prompt filters\n")
                append(metadata)
                append("local __autoscript_event_enabled = {loop=").append(filters.loops)
                    .append(", jump=").append(filters.jumps)
                    .append(", flowStart=").append(filters.flowStart)
                    .append(", flowReturn=").append(filters.flowReturn)
                    .append(", imageSearch=").append(filters.imageSearch)
                    .append(", variable=").append(filters.variables).append("}\n")
                append("local __autoscript_scope = ").append(com.google.gson.JsonPrimitive(filters.variableScope)).append('\n')
                append("local __autoscript_type = ").append(com.google.gson.JsonPrimitive(filters.variableType)).append('\n')
                append("local __autoscript_name = ").append(com.google.gson.JsonPrimitive(filters.variableName)).append('\n')
                append("local function __autoscript_hint_value(value)\n")
                append("  local text = tostring(value)\n")
                append("  if #text > 1024 then return '<' .. #text .. ' bytes>' end\n")
                append("  return text\nend\n")
                append("function __autoscript_debug_event(kind, flow, name, value)\n")
                append("  if not __autoscript_event_enabled[kind] then return end\n")
                append("  if kind == 'variable' then\n")
                append("    local meta = __autoscript_variable_meta[flow .. ':' .. name]\n")
                append("    if not meta then return end\n")
                append("    if __autoscript_scope ~= 'all' and meta.scope ~= __autoscript_scope then return end\n")
                append("    if __autoscript_type ~= 'all' and meta.kind ~= __autoscript_type then return end\n")
                append("    if __autoscript_name ~= 'all' and name ~= __autoscript_name then return end\n")
                append("    Prompt.show('变量 · ' .. name .. ' = ' .. (meta.kind == 'image' and '<图像>' or __autoscript_hint_value(value)))\n")
                append("  elseif kind == 'loop' then Prompt.show('循环 · ' .. flow .. ' · ' .. name .. ' ' .. __autoscript_hint_value(value))\n")
                append("  elseif kind == 'jump' then Prompt.show('判断 · ' .. flow .. ' · ' .. name .. ' ' .. __autoscript_hint_value(value))\n")
                append("  elseif kind == 'imageSearch' then Prompt.show('寻图 · ' .. name .. ' · ' .. __autoscript_hint_value(value))\n")
                append("  elseif kind == 'flowStart' then Prompt.show('插件运行 · ' .. flow)\n")
                append("  elseif kind == 'flowReturn' then Prompt.show('插件返回 · ' .. flow)\n")
                append("  end\nend\n")
            }
            return (prefix + source.toString(Charsets.UTF_8)).toByteArray(Charsets.UTF_8)
        }

        /**
         * Delay lives inside the task scheduler instead of blocking the Binder or a UI thread.
         * The original module remains self-contained and is evaluated once to obtain its entry
         * function; cancellation during `Task.sleep` uses the normal runtime cancellation path.
         */
        private fun withRunDelay(source: ByteArray, delayMs: Int): ByteArray {
            require(delayMs in 0..60_000) { "运行延迟无效" }
            if (delayMs == 0) return source
            val original = source.toString(Charsets.UTF_8)
            return buildString {
                append("-- @autoscript debug run delay\n")
                append("local __autoscript_entry = (function()\n")
                append(original)
                if (!original.endsWith('\n')) append('\n')
                append("end)()\n")
                append("return function()\n")
                append("  Task.sleep(").append(delayMs).append(")\n")
                append("  return __autoscript_entry()\n")
                append("end\n")
            }.toByteArray(Charsets.UTF_8)
        }

        /**
         * The runtime intentionally receives one frozen module.  Package project modules into a
         * lexical `require` implementation instead of giving Lua a file-system capability.
         * `lua/foo/bar.lua` is imported as `require("foo.bar")`.
         */
        private fun bundleLuaProject(
            snapshot: ProjectSnapshot,
            sources: Map<String, String>,
            entryPath: String,
        ): String {
            val declared = snapshot.manifest.luaFiles.ifEmpty { listOf(entryPath) }
            require(entryPath in declared) { "Lua 入口不在项目文件清单中" }
            require(declared.all { it in sources }) { "Lua 项目存在未加载的文件" }
            val modules = declared.filter { it != entryPath }.sorted()
            require(modules.map(::moduleName).distinct().size == modules.size) { "Lua 模块名称冲突" }
            if (modules.isEmpty()) return requireNotNull(sources[entryPath])
            return buildString {
                append("-- @autoscript bundled Lua project; do not edit while running\n")
                append("local require\n")
                append("local __autoscript_modules = {\n")
                modules.forEach { path ->
                    append("  [").append(luaQuoted(moduleName(path))).append("] = function()\n")
                    append(requireNotNull(sources[path]))
                    if (!sources.getValue(path).endsWith('\n')) append('\n')
                    append("  end,\n")
                }
                append("}\nlocal __autoscript_loaded = {}\n")
                append("local __autoscript_loading = {}\n")
                append("require = function(name)\n")
                append("  if __autoscript_loaded[name] ~= nil then return __autoscript_loaded[name] end\n")
                append("  local loader = __autoscript_modules[name]\n")
                append("  if loader == nil then error('Lua 模块不存在: ' .. tostring(name), 2) end\n")
                append("  if __autoscript_loading[name] then error('Lua 模块循环依赖: ' .. tostring(name), 2) end\n")
                append("  __autoscript_loading[name] = true\n")
                append("  local ok, value = pcall(loader)\n")
                append("  __autoscript_loading[name] = nil\n")
                append("  if not ok then error(value, 0) end\n")
                append("  if value == nil then value = true end\n")
                append("  __autoscript_loaded[name] = value\n  return value\nend\n")
                append(requireNotNull(sources[entryPath]))
            }
        }

        private fun moduleName(path: String): String {
            require(path.startsWith("lua/") && path.endsWith(".lua")) { "不可作为模块的 Lua 路径：$path" }
            return path.removePrefix("lua/").removeSuffix(".lua").replace('/', '.')
        }

        private fun luaQuoted(value: String): String = buildString {
            append('"')
            value.forEach { character ->
                when (character) {
                    '\\' -> append("\\\\")
                    '"' -> append("\\\"")
                    '\n' -> append("\\n")
                    '\r' -> append("\\r")
                    else -> append(character)
                }
            }
            append('"')
        }

        private fun isCanonicalResourcePath(path: String): Boolean =
            path.length in 1..256 &&
                '\\' !in path &&
                '\u0000' !in path &&
                path.split('/').all { it.isNotEmpty() && it != "." && it != ".." }

        private fun containedDirectory(rootDirectory: File, relativePath: String): File {
            val root = rootDirectory.canonicalFile
            val directory = File(root, relativePath).canonicalFile
            require(directory.path.startsWith(root.path + File.separator) && directory.isDirectory) {
                "生成目录不存在或越界"
            }
            return directory
        }

        private fun readBounded(file: File, maximum: Int): ByteArray {
            require(file.isFile && file.canRead()) { "生成文件不存在：${file.name}" }
            require(file.length() in 1..maximum.toLong()) { "生成文件大小无效：${file.name}" }
            return file.readBytes().also {
                require(it.size <= maximum) { "生成文件超过大小上限：${file.name}" }
            }
        }

        private fun com.google.gson.JsonObject.requiredString(name: String): String =
            get(name)?.takeIf { it.isJsonPrimitive }?.asString
                ?.takeIf(String::isNotEmpty)
                ?: throw IllegalArgumentException("generation记录缺少$name")

        private fun sha256(bytes: ByteArray): String =
            MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") {
                (it.toInt() and 0xff).toString(16).padStart(2, '0')
            }

        private fun ByteArray.indexOf(needle: ByteArray): Int {
            if (needle.isEmpty()) return 0
            for (start in 0..size - needle.size) {
                if (needle.indices.all { offset -> this[start + offset] == needle[offset] }) return start
            }
            return -1
        }

        private const val MAX_SOURCE_MAP_BYTES = 16 * 1024 * 1024
        private const val MAX_RECORD_BYTES = 64 * 1024
        private const val MAX_PROJECT_CAPABILITIES = 64
        private val CAPABILITY = Regex("[a-z][a-z0-9]*(\\.[a-z][a-z0-9]*)+")
    }
}
