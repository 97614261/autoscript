package com.autoscript.project.store

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.Locale
import java.util.Properties
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlin.math.roundToInt
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import com.google.gson.JsonParser

class ProjectStore(
    private val rootDirectory: File,
    private val clock: () -> Long = System::currentTimeMillis,
    private val idFactory: () -> String = { "project-${UUID.randomUUID()}" },
) {
    private val json: Gson = GsonBuilder()
        .setPrettyPrinting()
        .disableHtmlEscaping()
        .serializeNulls()
        .create()

    @Synchronized
    fun listProjects(): List<ProjectSummary> {
        ensureRoot()
        return rootDirectory.listFiles()
            .orEmpty()
            .asSequence()
            .filter { it.isDirectory && !it.name.startsWith('.') && PROJECT_ID.matches(it.name) }
            .mapNotNull { directory ->
                runCatching {
                    val decoded = readManifest(directory, recover = false).manifest
                    require(decoded.projectId == directory.name) { "项目目录与 projectId 不一致" }
                    validateManifest(decoded)
                    val state = readState(directory)
                    val updatedAt = state.updatedAt ?: directory.lastModified()
                    ProjectSummary(
                        projectId = decoded.projectId,
                        name = decoded.name,
                        sourceMode = decoded.sourceMode,
                        designWidth = decoded.design.width,
                        designHeight = decoded.design.height,
                        createdAt = state.createdAt ?: updatedAt,
                        updatedAt = updatedAt,
                        lastOpenedAt = state.lastOpenedAt,
                    )
                }.getOrNull()
            }
            .sortedWith(
                compareByDescending<ProjectSummary> { it.lastOpenedAt ?: it.updatedAt }
                    .thenBy { it.name.lowercase(Locale.ROOT) }
                    .thenBy { it.projectId },
            ).toList()
    }

    @Synchronized
    fun createProject(
        name: String,
        mode: ProjectSourceMode,
        designWidth: Int = 720,
        designHeight: Int = 1280,
        initialCapabilities: Set<String> = setOf("core.task"),
    ): ProjectSnapshot {
        ensureRoot()
        val cleanName = validateName(name)
        require(initialCapabilities.size <= MAX_PROJECT_CAPABILITIES && initialCapabilities.all {
            it.length <= MAX_CAPABILITY_LENGTH && CAPABILITY.matches(it)
        }) { "非法能力声明" }
        require(designWidth in 1..8192 && designHeight in 1..8192) { "基准分辨率无效" }
        val design = ProjectDesign(width = designWidth, height = designHeight)
        val projectId = validateProjectId(idFactory())
        val destination = projectDirectory(projectId)
        check(!destination.exists()) { "项目 ID 已存在：$projectId" }

        val staging = File(rootDirectory, ".$projectId.creating")
        checkContained(staging)
        if (staging.exists() && !staging.deleteRecursively()) {
            throw ProjectStoreException("无法清理未完成的项目目录")
        }
        if (!staging.mkdirs()) throw ProjectStoreException("无法创建项目目录")

        var creationStage = ProjectCreationStage.MANIFEST
        try {
            val manifest = when (mode) {
                ProjectSourceMode.LUA -> luaManifest(projectId, cleanName, design)
                ProjectSourceMode.VISUAL -> visualManifest(projectId, cleanName, design)
            }.copy(capabilities = initialCapabilities.sorted())
            creationStage = ProjectCreationStage.WRITE_MANIFEST
            writeManifest(staging, manifest, keepBackup = false)
            creationStage = ProjectCreationStage.WRITE_ENTRY
            when (mode) {
                ProjectSourceMode.LUA -> writeAtomic(
                    File(staging, LUA_ENTRY),
                    DEFAULT_LUA.toByteArray(Charsets.UTF_8),
                    backup = null,
                )
                ProjectSourceMode.VISUAL -> {
                    val flow = manifest.flows.single()
                    val node = initialVisualNode(flow.rootBlockId)
                    writeAtomic(
                        File(staging, flow.path),
                        "$node\n".toByteArray(Charsets.UTF_8),
                        backup = null,
                    )
                }
            }
            creationStage = ProjectCreationStage.WRITE_STATE
            val createdAt = clock()
            writeState(staging, ProjectState(createdAt = createdAt, updatedAt = createdAt))
            creationStage = ProjectCreationStage.COMMIT
            if (!staging.renameTo(destination)) throw ProjectStoreException("无法提交新项目目录")
            creationStage = ProjectCreationStage.REOPEN
            return openProject(projectId)
        } catch (error: Throwable) {
            staging.deleteRecursively()
            val diagnostic = projectCreationDiagnostic(creationStage, error)
            throw ProjectStoreException("创建项目失败\n${diagnostic.summary}", error, diagnostic)
        }
    }

    /** Read-only freeze guard: shares decoding defaults, but never recovers or deletes transaction files. */
    @Synchronized
    fun matchesManifestSnapshot(snapshot: ProjectSnapshot, expectedBytes: ByteArray): Boolean {
        if (expectedBytes.size !in 1..1_048_576) return false
        return runCatching {
            val directory = existingProjectDirectory(snapshot.manifest.projectId)
            if (directory.canonicalFile != snapshot.directory.canonicalFile) return false
            val manifest = File(directory, MANIFEST)
            if (!manifest.isFile || manifest.length() != expectedBytes.size.toLong()) return false
            manifest.inputStream().use { input ->
                val buffer = ByteArray(16_384)
                var offset = 0
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    if (offset + count > expectedBytes.size || (0 until count).any { buffer[it] != expectedBytes[offset + it] }) return false
                    offset += count
                }
                if (offset != expectedBytes.size) return false
            }
            decodeManifestBytes(expectedBytes)?.manifest == snapshot.manifest
        }.getOrDefault(false)
    }

    @Synchronized
    fun openProject(projectId: String): ProjectSnapshot {
        val directory = existingProjectDirectory(projectId)
        recoverVariableTransaction(directory)
        val loaded = readManifest(directory, recover = true)
        var manifest = loaded.manifest
        if (loaded.wasLegacy) {
            val migrationBackup = File(directory, MIGRATION_BACKUP)
            if (!migrationBackup.exists()) {
                writeAtomic(migrationBackup, loaded.originalBytes, backup = null)
            }
            writeManifest(directory, manifest, keepBackup = true)
        }
        // v2 Lua projects preceded the file-tree declaration.  Preserve their physical
        // main.lua and upgrade the manifest transactionally on first open.
        if (manifest.sourceMode == ProjectSourceMode.LUA &&
            (manifest.luaFiles.isEmpty() || manifest.luaDirectories.isEmpty())
        ) {
            manifest = manifest.copy(
                luaFiles = manifest.luaFiles.ifEmpty { listOf(requireNotNull(manifest.entryPoint)) },
                luaDirectories = manifest.luaDirectories.ifEmpty { listOf(LUA_MODULE_ROOT) },
            )
            writeManifest(directory, manifest, keepBackup = true)
        }
        validateManifest(manifest)
        require(manifest.projectId == projectId) { "项目目录与 projectId 不一致" }
        ensureDeclaredFiles(directory, manifest)

        val state = readState(directory)
        val updatedAt = state.updatedAt ?: clock()
        writeState(
            directory,
            state.copy(
                createdAt = state.createdAt ?: updatedAt,
                updatedAt = updatedAt,
                lastOpenedAt = clock(),
            ),
        )
        val luaSources = if (manifest.sourceMode == ProjectSourceMode.LUA) {
            manifest.luaFiles.associateWith { path ->
                if (path == manifest.entryPoint) readTextWithRecovery(directory, path, LUA_BACKUP)
                else readLuaFile(directory, path)
            }
        } else emptyMap()
        return ProjectSnapshot(
            directory = directory,
            manifest = manifest,
            luaSource = manifest.entryPoint?.let(luaSources::get),
            luaSources = luaSources,
            luaDirectories = manifest.luaDirectories,
            flowSources = manifest.flows.associate { flow ->
                flow.flowId to File(directory, flow.path).readText(Charsets.UTF_8)
            },
        )
    }

    @Synchronized
    fun renameProject(projectId: String, name: String): ProjectSnapshot {
        val current = openProject(projectId)
        val directory = current.directory
        val renamed = current.manifest.copy(name = validateName(name))
        validateManifest(renamed)
        writeManifest(directory, renamed, keepBackup = true)
        val state = readState(directory)
        writeState(directory, state.copy(updatedAt = clock()))
        return openProject(projectId)
    }

    @Synchronized
    fun importResource(
        projectId: String,
        kind: ProjectResourceKind,
        sourceName: String,
        source: InputStream,
        expectedResourcePaths: Set<String>,
        imageDirectory: String = "",
    ): ProjectSnapshot {
        val current = openProject(projectId)
        requireResourcePathsMatch(current, expectedResourcePaths)
        require(current.manifest.resources.size < MAX_PROJECT_RESOURCES) {
            "资源数量不能超过 $MAX_PROJECT_RESOURCES"
        }
        val staging = File(current.directory, ".studio/import/${UUID.randomUUID()}.tmp")
        checkContained(staging)
        try {
            val maximumBytes = when (kind) {
                ProjectResourceKind.IMAGE -> MAX_IMAGE_RESOURCE_BYTES
                ProjectResourceKind.GLYPH_DICTIONARY -> MAX_GLYPH_DICTIONARY_BYTES
                ProjectResourceKind.AUDIO -> MAX_AUDIO_RESOURCE_BYTES
            }
            writeBoundedAndSync(staging, source, maximumBytes)
            if (kind == ProjectResourceKind.AUDIO) {
                val audioBytes = current.manifest.resources.filter { it.get("kind")?.asString == "audio" }
                    .sumOf { File(current.directory, it.get("path").asString).length() }
                require(audioBytes + staging.length() <= 64L * 1024 * 1024) { "项目音频总量超过64MiB" }
            }
            val extension = validateImportedResource(kind, staging)
            val normalizedImageDirectory = if (kind == ProjectResourceKind.IMAGE) {
                validateImageResourceDirectory(imageDirectory)
            } else {
                require(imageDirectory.isEmpty()) { "字库资源不能指定图片目录" }
                ""
            }
            val fileName = uniqueResourceFileName(
                current,
                kind,
                sanitizeResourceStem(sourceName),
                extension,
                normalizedImageDirectory,
            )
            val path = when (kind) {
                ProjectResourceKind.IMAGE -> "assets/images/${normalizedImageDirectory}$fileName"
                ProjectResourceKind.GLYPH_DICTIONARY -> "dictionaries/$fileName"
                ProjectResourceKind.AUDIO -> "assets/audio/$fileName"
            }
            val target = File(current.directory, path)
            checkContained(target)
            target.parentFile?.let { parent ->
                if (!parent.exists() && !parent.mkdirs()) {
                    throw ProjectStoreException("无法创建资源目录")
                }
            }
            require(!target.exists()) { "资源文件已存在：$path" }
            if (!staging.renameTo(target)) throw ProjectStoreException("无法提交资源文件")
            try {
                invalidateGeneration(current.directory)
                val declaration = JsonObject().apply {
                    addProperty("kind", resourceManifestKind(kind))
                    addProperty("path", path)
                }
                writeManifest(
                    current.directory,
                    current.manifest.copy(resources = current.manifest.resources + declaration),
                    keepBackup = true,
                )
            } catch (failure: Throwable) {
                target.delete()
                throw failure
            }
            touchProject(current.directory)
            return openProject(projectId)
        } finally {
            staging.delete()
        }
    }

    @Synchronized
    fun deleteResource(
        projectId: String,
        path: String,
        expectedResourcePaths: Set<String>,
    ): ProjectSnapshot {
        val current = openProject(projectId)
        requireResourcePathsMatch(current, expectedResourcePaths)
        val declaration = current.manifest.resources.singleOrNull {
            it.get("path")?.asString == path
        } ?: throw IllegalArgumentException("资源不在项目清单中：$path")
        require(!isResourceReferenced(current, path)) { "资源仍被脚本或积木引用：$path" }
        val target = File(current.directory, path)
        checkContained(target)
        require(target.isFile) { "资源文件不存在：$path" }
        val staged = File(current.directory, ".studio/resource-trash/${UUID.randomUUID()}-${target.name}")
        checkContained(staged)
        staged.parentFile?.let { parent ->
            if (!parent.exists() && !parent.mkdirs()) throw ProjectStoreException("无法创建资源事务目录")
        }
        invalidateGeneration(current.directory)
        if (!target.renameTo(staged)) throw ProjectStoreException("无法暂存待删除资源")
        try {
            writeManifest(
                current.directory,
                current.manifest.copy(resources = current.manifest.resources - declaration),
                keepBackup = true,
            )
        } catch (failure: Throwable) {
            if (!staged.renameTo(target)) {
                throw ProjectStoreException("资源清单写入失败且无法恢复资源文件", failure)
            }
            throw failure
        }
        staged.delete()
        touchProject(current.directory)
        return openProject(projectId)
    }

    @Synchronized
    fun updateExecutionSettings(
        projectId: String,
        capabilities: Set<String>,
        debugSettings: ProjectDebugSettings,
        randomization: ProjectRandomization,
        expectedCapabilities: Set<String>,
        expectedDebugSettings: ProjectDebugSettings,
        expectedRandomization: ProjectRandomization,
    ): ProjectSnapshot {
        val current = openProject(projectId)
        require(current.manifest.sourceMode == ProjectSourceMode.VISUAL) { "运行设置仅用于可视化项目" }
        if (current.manifest.capabilities.toSet() != expectedCapabilities ||
            current.manifest.debugSettings != expectedDebugSettings ||
            current.manifest.randomization != expectedRandomization) throw ProjectManifestConflictException()
        require(capabilities.size <= MAX_PROJECT_CAPABILITIES && capabilities.all {
            it.length <= MAX_CAPABILITY_LENGTH && CAPABILITY.matches(it)
        }) { "非法能力声明" }
        validateDebugSettings(debugSettings)
        require(randomization.valid()) { "随机策略参数无效" }
        val api = current.manifest.runtimeApi
        val updated = current.manifest.copy(capabilities = capabilities.sorted(), debugSettings = debugSettings,
            randomization = randomization, runtimeApi = if (randomization != expectedRandomization &&
                api.substringBefore('.') == "1" && api.substringAfter('.').toInt() < 8) "1.8" else api)
        if (capabilities != expectedCapabilities || randomization != expectedRandomization) invalidateGeneration(current.directory)
        writeManifest(current.directory, updated, keepBackup = true)
        touchProject(current.directory)
        return openProject(projectId)
    }

    @Synchronized
    fun updateCapabilities(
        projectId: String,
        capabilities: Set<String>,
        expectedCapabilities: Set<String>,
    ): ProjectSnapshot {
        val current = openProject(projectId)
        if (current.manifest.capabilities.toSet() != expectedCapabilities) {
            throw ProjectManifestConflictException()
        }
        require(capabilities.size <= MAX_PROJECT_CAPABILITIES) { "项目能力数量超过64" }
        require(capabilities.all { it.length <= MAX_CAPABILITY_LENGTH && CAPABILITY.matches(it) }) {
            "非法能力声明"
        }
        invalidateGeneration(current.directory)
        writeManifest(
            current.directory,
            current.manifest.copy(capabilities = capabilities.sorted()),
            keepBackup = true,
        )
        touchProject(current.directory)
        return openProject(projectId)
    }

    @Synchronized
    fun updateRunnerUi(
        projectId: String,
        runnerUi: JsonObject?,
        expectedRunnerUi: JsonObject?,
    ): ProjectSnapshot {
        val current = openProject(projectId)
        if (current.manifest.runnerUi != expectedRunnerUi) {
            throw ProjectManifestConflictException()
        }
        validateRunnerUi(runnerUi)
        validateInterfaceReferences(current.manifest.copy(runnerUi = runnerUi))
        invalidateGeneration(current.directory)
        writeManifest(
            current.directory,
            current.manifest.copy(runnerUi = runnerUi?.deepCopy(), capabilities =
                if (runnerUi?.get("version")?.asInt == 2) (current.manifest.capabilities + "ui.control" + "core.task").distinct().sorted() else current.manifest.capabilities,
                runtimeApi = if (runnerUi?.get("version")?.asInt == 2 && current.manifest.runtimeApi.substringBefore('.') == "1" && current.manifest.runtimeApi.substringAfter('.').toInt() < 7) "1.7" else current.manifest.runtimeApi),
            keepBackup = true,
        )
        touchProject(current.directory)
        return openProject(projectId)
    }

    @Synchronized
    fun updateDebugSettings(
        projectId: String,
        debugSettings: ProjectDebugSettings,
        expected: ProjectDebugSettings,
    ): ProjectSnapshot {
        val current = openProject(projectId)
        if (current.manifest.debugSettings != expected) throw ProjectManifestConflictException()
        validateDebugSettings(debugSettings)
        writeManifest(
            current.directory,
            current.manifest.copy(debugSettings = debugSettings),
            keepBackup = true,
        )
        touchProject(current.directory)
        return openProject(projectId)
    }

    @Synchronized
    fun updateRandomization(projectId: String, submitted: ProjectRandomization, expected: ProjectRandomization): ProjectSnapshot {
        val current = openProject(projectId)
        if (current.manifest.randomization != expected) throw ProjectManifestConflictException()
        require(submitted.valid()) { "随机策略参数无效" }
        require(current.manifest.sourceMode == ProjectSourceMode.VISUAL) { "随机策略仅用于可视化项目" }
        val api = current.manifest.runtimeApi
        val updated = current.manifest.copy(randomization = submitted,
            runtimeApi = if (api.substringBefore('.') == "1" && api.substringAfter('.').toInt() < 8) "1.8" else api)
        writeManifest(current.directory, updated, keepBackup = true)
        touchProject(current.directory)
        return openProject(projectId)
    }

    @Synchronized
    fun updateVariables(
        projectId: String,
        variables: List<ProjectVariable>,
        expected: List<ProjectVariable>,
        renames: List<ProjectVariableRename> = emptyList(),
        flowDrafts: Map<String, String> = emptyMap(),
        expectedFlowSources: Map<String, String>? = null,
    ): ProjectSnapshot {
        val current = openProject(projectId)
        if (current.manifest.variables != expected) throw ProjectManifestConflictException()
        validateVariables(variables, current.manifest.flows)
        require(renames.size <= 256 && renames.map { it.original }.distinct().size == renames.size) { "变量改名重复或超过上限" }
        renames.forEach { rename ->
            require(rename.original in expected && rename.newName != rename.original.name &&
                variables.any { it.name == rename.newName && it.scope == rename.original.scope && it.flowId == rename.original.flowId }) { "改名缺少原始声明或目标声明" }
            require(variables.none { it.name == rename.original.name && it.scope == rename.original.scope && it.flowId == rename.original.flowId }) { "请分次完成变量交换或链式改名" }
        }
        require(renames.isEmpty() || current.manifest.sourceMode == ProjectSourceMode.VISUAL) { "Lua 源码改名需自行维护引用" }
        if (expectedFlowSources != null && expectedFlowSources != current.flowSources) throw ProjectManifestConflictException()
        require(flowDrafts.keys.all { it in current.flowSources }) { "草稿不属于项目插件" }
        var manifest = current.manifest.copy(variables = variables, runnerUi = rewriteVariableBindings(current.manifest.runnerUi, renames))
        val filter = manifest.debugSettings.runPromptFilters
        renames.firstOrNull { it.original.name == filter.variableName &&
            (filter.variableScope == "global" && it.original.scope == ProjectVariableScope.GLOBAL ||
                filter.variableScope == "flow:${it.original.flowId}" && it.original.scope == ProjectVariableScope.FLOW) }?.let {
            manifest = manifest.copy(debugSettings = manifest.debugSettings.copy(runPromptFilters = filter.copy(variableName = it.newName)))
        }
        validateInterfaceReferences(manifest)
        if (renames.isNotEmpty()) {
            val replacements = current.manifest.flows.associate { flow ->
                flow.path to rewriteFlowVariableReferences(flowDrafts[flow.flowId] ?: current.flowSources.getValue(flow.flowId),
                    variableRenameMap(current.manifest, flow, renames))
            }.filter { (path, source) -> source != current.flowSources.getValue(current.manifest.flows.single { it.path == path }.flowId) }
            commitVariableTransaction(current, manifest, replacements)
        } else {
            invalidateGeneration(current.directory)
            writeManifest(current.directory, manifest, keepBackup = true)
        }
        touchProject(current.directory)
        return openProject(projectId)
    }

    /** Crash recovery rolls back an uncommitted multi-file rename instead of accepting mixed names. */
    private fun recoverVariableTransaction(directory: File) {
        val journal = File(directory, ".studio/variable-transaction.json")
        checkContained(journal)
        if (!journal.exists()) return
        require(journal.isFile && journal.length() in 1..64L * 1024 * 1024) { "变量事务日志大小无效" }
        val value = JsonParser.parseString(journal.readText(Charsets.UTF_8)).asJsonObject
        require(value.keySet() == setOf("version", "before") && value.get("version").asInt == 1) { "变量事务日志格式无效" }
        val before = value.getAsJsonObject("before")
        require(before.size() in 1..257 && before.has(MANIFEST)) { "变量事务缺少清单" }
        val originalManifest = json.fromJson(before.get(MANIFEST).asString, ProjectManifestDocument::class.java)
        validateManifest(originalManifest)
        require(originalManifest.projectId == directory.name) { "变量事务项目身份无效" }
        val paths = originalManifest.flows.map { it.path }.toSet() + MANIFEST
        var size = 0L
        val writes = before.entrySet().map { (path, text) ->
            require(path in paths && text.isJsonPrimitive && text.asJsonPrimitive.isString) { "变量事务路径无效" }
            val bytes = text.asString.toByteArray(Charsets.UTF_8)
            size += bytes.size
            require(bytes.size <= if (path == MANIFEST) MAX_PROJECT_MANIFEST_BYTES else MAX_FLOW_SOURCE_BYTES.toLong()) { "变量事务文件超过预算" }
            require(size <= 32L * 1024 * 1024) { "变量事务超过预算" }
            val target = File(directory, path)
            checkContained(target)
            target to bytes
        }
        invalidateGeneration(directory)
        writes.forEach { (target, bytes) -> writeAtomic(target, bytes, backup = null) }
        if (!journal.delete()) throw ProjectStoreException("无法结束变量事务恢复")
    }

    private fun commitVariableTransaction(current: ProjectSnapshot, manifest: ProjectManifestDocument, sources: Map<String, String>) {
        val manifestBytes = readBoundedStoreFile(File(current.directory, MANIFEST), MAX_PROJECT_MANIFEST_BYTES)
        var rollbackBytes = manifestBytes.size.toLong()
        val before = JsonObject().apply { addProperty(MANIFEST, manifestBytes.toString(Charsets.UTF_8)) }
        sources.keys.forEach { path ->
            val id = current.manifest.flows.single { it.path == path }.flowId
            val original = current.flowSources.getValue(id)
            require(original.length <= 32L * 1024 * 1024 - rollbackBytes) { "项目事务涉及原文件超过32 MiB，请缩小或拆分插件" }
            rollbackBytes += original.toByteArray(Charsets.UTF_8).size
            require(rollbackBytes <= 32L * 1024 * 1024) { "项目事务涉及原文件超过32 MiB，请缩小或拆分插件" }
            before.addProperty(path, original)
        }
        sources.values.forEach { require(it.toByteArray(Charsets.UTF_8).size <= MAX_FLOW_SOURCE_BYTES) { "提交后插件超过预算" } }
        val journal = File(current.directory, ".studio/variable-transaction.json")
        val journalBytes = JsonObject().apply { addProperty("version", 1); add("before", before) }.toString().toByteArray(Charsets.UTF_8)
        require(journalBytes.size <= 64 * 1024 * 1024) { "变量事务日志超过预算" }
        writeAtomic(journal, journalBytes, backup = null)
        try {
            invalidateGeneration(current.directory)
            sources.forEach { (path, source) ->
                val flow = current.manifest.flows.single { it.path == path }
                writeAtomic(File(current.directory, path), source.toByteArray(Charsets.UTF_8), File(current.directory, "$FLOW_BACKUP_ROOT/${flow.flowId}.jsonl.bak"))
            }
            writeManifest(current.directory, manifest, keepBackup = true)
            if (!journal.delete()) throw ProjectStoreException("无法提交变量事务")
        } catch (failure: Throwable) {
            try { recoverVariableTransaction(current.directory) } catch (recovery: Throwable) { failure.addSuppressed(recovery) }
            throw failure
        }
    }

    @Synchronized
    fun saveLua(
        projectId: String,
        source: String,
        expectedSource: String? = null,
    ): ProjectSnapshot {
        val current = openProject(projectId)
        val manifest = current.manifest
        require(manifest.sourceMode == ProjectSourceMode.LUA) { "只有 Lua 项目可以保存 Lua 源码" }
        if (expectedSource != null && current.luaSource != expectedSource) {
            throw ProjectWriteConflictException()
        }
        return saveLuaFileInternal(current, requireNotNull(manifest.entryPoint), source)
    }

    /** Creates a persisted Lua module.  Names are project-relative (`lua/foo.lua`). */
    @Synchronized
    fun createLuaFile(projectId: String, path: String, content: String = DEFAULT_LUA_MODULE): ProjectSnapshot {
        val current = openProject(projectId)
        require(current.manifest.sourceMode == ProjectSourceMode.LUA) { "只有 Lua 项目可以创建 Lua 文件" }
        val normalized = normalizeLuaPath(path)
        require(normalized !in current.manifest.luaFiles) { "Lua 文件已存在：$normalized" }
        val source = content.toByteArray(Charsets.UTF_8)
        require(source.size <= MAX_LUA_SOURCE_BYTES) { "Lua 源码不能超过 16 MiB" }
        val target = File(current.directory, normalized)
        checkContained(target)
        require(!target.exists()) { "Lua 文件已存在：$normalized" }
        writeAtomic(target, source, backup = null)
        try {
            invalidateGeneration(current.directory)
            writeManifest(
                current.directory,
                current.manifest.copy(
                    luaFiles = (current.manifest.luaFiles + normalized).sorted(),
                    luaDirectories = (current.manifest.luaDirectories + luaParentDirectories(normalized)).distinct().sorted(),
                ),
                keepBackup = true,
            )
        } catch (failure: Throwable) {
            target.delete()
            throw failure
        }
        touchProject(current.directory)
        return openProject(projectId)
    }

    /** Creates an empty, restricted Lua folder.  It becomes authoritative once it contains a Lua file. */
    @Synchronized
    fun createLuaDirectory(projectId: String, path: String): ProjectSnapshot {
        val current = openProject(projectId)
        require(current.manifest.sourceMode == ProjectSourceMode.LUA) { "只有 Lua 项目可以创建 Lua 文件夹" }
        val normalized = normalizeLuaDirectory(path)
        val target = File(current.directory, normalized)
        checkContained(target)
        require(!target.exists()) { "Lua 文件夹已存在：$normalized" }
        require(target.mkdirs()) { "无法创建 Lua 文件夹：$normalized" }
        writeManifest(
            current.directory,
            current.manifest.copy(
                luaDirectories = (current.manifest.luaDirectories + luaDirectoryChain(normalized)).distinct().sorted(),
            ),
            keepBackup = true,
        )
        touchProject(current.directory)
        return openProject(projectId)
    }

    @Synchronized
    fun saveLuaFile(projectId: String, path: String, source: String, expectedSource: String? = null): ProjectSnapshot {
        val current = openProject(projectId)
        require(current.manifest.sourceMode == ProjectSourceMode.LUA) { "只有 Lua 项目可以保存 Lua 源码" }
        val normalized = normalizeLuaPath(path)
        require(normalized in current.manifest.luaFiles) { "Lua 文件不在项目清单中：$normalized" }
        if (expectedSource != null && current.luaSources[normalized] != expectedSource) {
            throw ProjectWriteConflictException()
        }
        return saveLuaFileInternal(current, normalized, source)
    }

    @Synchronized
    fun saveFlow(
        projectId: String,
        flowId: String,
        source: String,
        expectedSource: String,
    ): ProjectSnapshot {
        val current = openProject(projectId)
        val directory = current.directory
        val manifest = current.manifest
        require(manifest.sourceMode == ProjectSourceMode.VISUAL) {
            "只有可视化项目可以保存插件"
        }
        val declaration = manifest.flows.singleOrNull { it.flowId == flowId }
            ?: throw IllegalArgumentException("插件不在项目清单中：$flowId")
        if (current.flowSources[flowId] != expectedSource) {
            throw FlowWriteConflictException(flowId)
        }
        val sourceBytes = source.toByteArray(Charsets.UTF_8)
        require(sourceBytes.size <= MAX_FLOW_SOURCE_BYTES) { "插件源码不能超过 64 MiB" }
        val target = File(directory, declaration.path)
        checkContained(target)

        // 先让生成代次失效。即使随后写 Flow 失败，也绝不能运行旧生成物。
        invalidateGeneration(directory)
        val requiredMinor = requiredFlowApiMinor(source)
        if (manifest.runtimeApi.substringBefore('.').toInt() == 1 && manifest.runtimeApi.substringAfter('.').toInt() < requiredMinor) {
            commitVariableTransaction(current, manifest.copy(runtimeApi = "1.$requiredMinor"), mapOf(declaration.path to source))
        } else {
            writeAtomic(target, sourceBytes, File(directory, "$FLOW_BACKUP_ROOT/$flowId.jsonl.bak"))
        }
        val state = readState(directory)
        writeState(directory, state.copy(updatedAt = clock()))
        return openProject(projectId)
    }

    /** Validated editor batch; conflicts reject the entire write before touching generation or files. */
    @Synchronized
    fun saveFlows(expected: ProjectSnapshot, sources: Map<String, String>): ProjectSnapshot {
        val current = openProject(expected.manifest.projectId)
        require(current.manifest.sourceMode == ProjectSourceMode.VISUAL) { "只有可视化项目可以保存插件" }
        if (current.manifest != expected.manifest || current.flowSources != expected.flowSources) {
            throw ProjectManifestConflictException()
        }
        require(sources.keys.all { id -> current.manifest.flows.any { it.flowId == id } }) { "插件不在项目清单中" }
        sources.values.forEach { require(it.toByteArray(Charsets.UTF_8).size <= MAX_FLOW_SOURCE_BYTES) { "插件源码不能超过64 MiB" } }
        val changed = sources.filter { (id, source) -> source != current.flowSources.getValue(id) }
        if (changed.isEmpty()) return current
        val replacements = changed.mapKeys { (id, _) -> current.manifest.flows.single { it.flowId == id }.path }
        val requiredMinor = changed.values.maxOf(::requiredFlowApiMinor)
        val manifest = current.manifest.let {
            if (it.runtimeApi.substringBefore('.').toInt() == 1 && it.runtimeApi.substringAfter('.').toInt() < requiredMinor)
                it.copy(runtimeApi = "1.$requiredMinor") else it
        }
        commitVariableTransaction(current, manifest, replacements)
        touchProject(current.directory)
        return openProject(current.manifest.projectId)
    }

    private fun requiredFlowApiMinor(source: String): Int = source.lineSequence().filter(String::isNotBlank).maxOfOrNull { line ->
        runCatching {
            val node = JsonParser.parseString(line).asJsonObject
            val kind = node.get("kind")?.asString
            when {
                node.has("randomization") || kind in setOf("files.getsize", "files.getextension", "files.directorystats", "files.tobase64", "files.frombase64", "files.zip", "files.unzip", "input.pastetext", "clipboard.gettext", "clipboard.settext", "app.info", "app.open", "floating.show", "floating.hide", "floating.close", "floating.bounds") -> 8
                kind in setOf("task.spawn", "task.cancel", "timer.every", "timer.cancel", "vision.findgray", "vision.findallimages", "vision.result", "ocr.alphanumeric", "ui.get", "ui.set", "ui.command") ||
                    kind == "vision.findimage" && (node.get("nodeVersion")?.asInt ?: 1) >= 2 ||
                    kind == "control.if" && (node.getAsJsonObject("args")?.has("condition") == true || node.getAsJsonObject("args")?.getAsJsonArray("elseIf")?.any { it.asJsonObject.has("condition") } == true) -> 7
                else -> 0
            }
        }.getOrDefault(0)
    } ?: 0

    /**
     * 新建 Flow。`flowId` 是引用身份（ASCII），`fileName` 是源文件管理里显示的名字（允许中文），
     * 默认与 `flowId` 相同。
     */
    @Synchronized
    fun createFlow(
        projectId: String,
        flowId: String,
        expectedFlowIds: Set<String>,
        fileName: String = flowId,
    ): ProjectSnapshot {
        val current = openProject(projectId)
        require(current.manifest.sourceMode == ProjectSourceMode.VISUAL) {
            "只有可视化项目可以新建插件"
        }
        require(current.manifest.flows.map(ProjectFlow::flowId).toSet() == expectedFlowIds) {
            throw ProjectManifestConflictException()
        }
        require(FLOW_ID.matches(flowId)) { "非法插件 ID：$flowId" }
        require(current.manifest.flows.none { it.flowId == flowId }) { "插件已存在：$flowId" }
        require(current.manifest.flows.size < MAX_PROJECT_FLOWS) { "插件数量不能超过 256" }
        val path = flowPathForName(fileName)
        require(current.manifest.flows.none { it.path == path }) { "源文件名已存在：$fileName" }
        val declaration = ProjectFlow(
            flowId = flowId,
            path = path,
            rootBlockId = "block-${UUID.randomUUID()}",
        )
        val flowFile = File(current.directory, declaration.path)
        checkContained(flowFile)
        require(!flowFile.exists()) { "插件文件已存在：${declaration.path}" }
        writeAtomic(
            flowFile,
            "${initialVisualNode(declaration.rootBlockId)}\n".toByteArray(Charsets.UTF_8),
            backup = null,
        )
        try {
            invalidateGeneration(current.directory)
            writeManifest(
                current.directory,
                current.manifest.copy(flows = current.manifest.flows + declaration),
                keepBackup = true,
            )
        } catch (failure: Throwable) {
            flowFile.delete()
            throw failure
        }
        val state = readState(current.directory)
        writeState(current.directory, state.copy(updatedAt = clock()))
        return openProject(projectId)
    }

    @Synchronized
    fun deleteFlow(
        projectId: String,
        flowId: String,
        expectedFlowIds: Set<String>,
    ): ProjectSnapshot {
        val current = openProject(projectId)
        require(current.manifest.sourceMode == ProjectSourceMode.VISUAL) {
            "只有可视化项目可以删除插件"
        }
        require(current.manifest.flows.map(ProjectFlow::flowId).toSet() == expectedFlowIds) {
            throw ProjectManifestConflictException()
        }
        require(flowId != current.manifest.entryFlowId) { "不能删除主插件，请先设置其他插件为主插件" }
        val declaration = current.manifest.flows.singleOrNull { it.flowId == flowId }
            ?: throw IllegalArgumentException("插件不存在：$flowId")
        require(!isFlowReferenced(current.flowSources, flowId)) { "插件仍被其他插件调用，请先移除调用" }
        val references = interfaceFlowReferences(current.manifest, flowId)
        require(references.isEmpty()) { "插件仍被界面引用：${references.joinToString("、")}" }
        val updated = current.manifest.copy(
            flows = current.manifest.flows - declaration,
            variables = current.manifest.variables.filterNot { it.scope == ProjectVariableScope.FLOW && it.flowId == flowId },
            debugSettings = if (current.manifest.debugSettings.runPromptFilters.variableScope == "flow:$flowId") {
                current.manifest.debugSettings.copy(runPromptFilters = current.manifest.debugSettings.runPromptFilters.copy(variableScope = "all", variableName = "all"))
            } else current.manifest.debugSettings,
        )
        validateInterfaceReferences(updated)

        invalidateGeneration(current.directory)
        writeManifest(
            current.directory,
            updated,
            keepBackup = true,
        )
        val flowFile = File(current.directory, declaration.path)
        checkContained(flowFile)
        if (flowFile.exists() && !flowFile.delete()) {
            throw ProjectStoreException("插件已从清单移除，但旧文件清理失败：$flowId")
        }
        pruneSourceGroups(current.directory, current.manifest.flows.map(ProjectFlow::flowId).toSet() - flowId)
        val state = readState(current.directory)
        writeState(current.directory, state.copy(updatedAt = clock()))
        return openProject(projectId)
    }

    /**
     * 把 Flow 文件移动到 [VISUAL_FLOW_ROOT] 内的新相对路径（当前策略下只有单层，等价于改名）。
     *
     * `flowId` 是 Flow 的身份，路径只是位置：移动不改变 `flowId`，因此所有 `flow.call` 引用保持有效。
     * 参考产品用路径当身份（新版易编精灵 `Host.deleteProjectFile {path}`），移动即改身份、引用会断；
     * 这里刻意不沿用。
     */
    @Synchronized
    fun moveFlow(
        projectId: String,
        flowId: String,
        relativePath: String,
        expectedFlowIds: Set<String>,
    ): ProjectSnapshot {
        val current = openProject(projectId)
        require(current.manifest.sourceMode == ProjectSourceMode.VISUAL) {
            "只有可视化项目可以移动插件"
        }
        require(current.manifest.flows.map(ProjectFlow::flowId).toSet() == expectedFlowIds) {
            throw ProjectManifestConflictException()
        }
        val declaration = current.manifest.flows.singleOrNull { it.flowId == flowId }
            ?: throw IllegalArgumentException("插件不存在：$flowId")
        val target = normalizeFlowPath(relativePath)
        if (target == declaration.path) return current
        require(current.manifest.flows.none { it.path == target }) { "目标路径已被占用：$target" }

        val sourceFile = File(current.directory, declaration.path)
        val targetFile = File(current.directory, target)
        checkContained(sourceFile)
        checkContained(targetFile)
        require(!targetFile.exists()) { "目标文件已存在：$target" }
        targetFile.parentFile?.mkdirs()
        require(sourceFile.renameTo(targetFile)) { "移动插件文件失败：$flowId" }
        try {
            invalidateGeneration(current.directory)
            writeManifest(
                current.directory,
                current.manifest.copy(
                    flows = current.manifest.flows.map {
                        if (it.flowId == flowId) it.copy(path = target) else it
                    },
                ),
                keepBackup = true,
            )
        } catch (failure: Throwable) {
            targetFile.renameTo(sourceFile)
            throw failure
        }
        val state = readState(current.directory)
        writeState(current.directory, state.copy(updatedAt = clock()))
        return openProject(projectId)
    }

    /**
     * 重命名 Flow 的显示文件名，保留 `flowId`。
     *
     * 与 [moveFlow] 同理，这只改变显示名，不改变引用身份。
     */
    @Synchronized
    fun renameFlow(
        projectId: String,
        flowId: String,
        fileName: String,
        expectedFlowIds: Set<String>,
    ): ProjectSnapshot = moveFlow(projectId, flowId, flowPathForName(fileName), expectedFlowIds)

    /** Change only the project entry identity, never rewrite calls or silently detach UI bindings. */
    @Synchronized
    fun setEntryFlow(projectId: String, flowId: String, expectedEntryFlowId: String?, expectedFlowIds: Set<String>): ProjectSnapshot {
        val current = openProject(projectId)
        require(current.manifest.sourceMode == ProjectSourceMode.VISUAL) { "只有可视化项目可以设置主插件" }
        if (current.manifest.entryFlowId != expectedEntryFlowId || current.manifest.flows.map(ProjectFlow::flowId).toSet() != expectedFlowIds) {
            throw ProjectManifestConflictException()
        }
        require(current.manifest.flows.any { it.flowId == flowId }) { "插件不存在：$flowId" }
        if (current.manifest.entryFlowId == flowId) return current
        val updated = current.manifest.copy(entryFlowId = flowId)
        validateInterfaceReferences(updated)
        invalidateGeneration(current.directory)
        writeManifest(current.directory, updated, keepBackup = true)
        touchProject(current.directory)
        return openProject(projectId)
    }

    /**
     * 读取源文件管理的虚拟分组（`.studio/source-groups.json`）。
     *
     * 已不存在的 Flow 会被静默剔除；文件损坏时按“没有分组”处理，不影响项目打开。
     */
    @Synchronized
    fun readSourceGroups(projectId: String): List<SourceGroup> {
        val directory = existingProjectDirectory(projectId)
        val manifest = readManifest(directory, recover = false).manifest
        return loadSourceGroups(directory, manifest.flows.map(ProjectFlow::flowId).toSet())
    }

    /**
     * 覆盖写入虚拟分组。分组名与 Flow 文件名共用同一套字符规则；一个 Flow 最多属于一个分组；
     * 引用了不存在 Flow 的分组会被拒绝，而不是静默修正，避免 UI 与磁盘状态不一致。
     */
    @Synchronized
    fun writeSourceGroups(projectId: String, groups: List<SourceGroup>): List<SourceGroup> {
        val directory = existingProjectDirectory(projectId)
        val manifest = readManifest(directory, recover = false).manifest
        require(manifest.sourceMode == ProjectSourceMode.VISUAL) { "只有可视化项目可以管理源文件分组" }
        require(groups.size <= MAX_SOURCE_GROUPS) { "分组数量不能超过 $MAX_SOURCE_GROUPS" }
        val flowIds = manifest.flows.map(ProjectFlow::flowId).toSet()
        val seenNames = mutableSetOf<String>()
        val seenFlows = mutableSetOf<String>()
        val normalized = groups.map { group ->
            val name = group.name.trim()
            require(isValidFlowName(name)) { "非法分组名：${group.name}" }
            require(seenNames.add(name)) { "分组名重复：$name" }
            group.flowIds.forEach { flowId ->
                require(flowId in flowIds) { "分组引用了不存在的插件：$flowId" }
                require(seenFlows.add(flowId)) { "插件只能属于一个分组：$flowId" }
            }
            SourceGroup(name, group.flowIds.toList())
        }
        writeAtomic(File(directory, SOURCE_GROUPS), serializeSourceGroups(normalized), backup = null)
        touchProject(directory)
        return normalized
    }

    /**
     * 复制一个 Flow（源文件管理里的「另存为」）。
     *
     * 副本必须拿到**新的** `flowId` 和新的 `rootBlockId`：`flowId` 是引用身份，
     * 复制出的两份若共用身份，`flow.call` 就会指向二义的目标。
     * 节点内的 `nodeId`/`blockId` 逐一重写，保持父子关系和 `orderKey` 不变，
     * 于是副本结构与原件一致，但两份的节点身份互不重叠。
     *
     * 副本里的 `flow.call` 参数保持原样：它引用的是**别的** Flow，
     * 复制不应该改变被调用方，这一点与子树复制的语义一致。
     */
    @Synchronized
    fun copyFlow(
        projectId: String,
        sourceFlowId: String,
        targetFlowId: String,
        fileName: String,
        expectedFlowIds: Set<String>,
    ): ProjectSnapshot {
        val current = openProject(projectId)
        require(current.manifest.sourceMode == ProjectSourceMode.VISUAL) {
            "只有可视化项目可以复制插件"
        }
        require(current.manifest.flows.map(ProjectFlow::flowId).toSet() == expectedFlowIds) {
            throw ProjectManifestConflictException()
        }
        require(FLOW_ID.matches(targetFlowId)) { "非法插件 ID：$targetFlowId" }
        require(current.manifest.flows.none { it.flowId == targetFlowId }) { "插件已存在：$targetFlowId" }
        require(current.manifest.flows.size < MAX_PROJECT_FLOWS) { "插件数量不能超过 256" }
        val origin = current.manifest.flows.singleOrNull { it.flowId == sourceFlowId }
            ?: throw IllegalArgumentException("插件不存在：$sourceFlowId")
        val target = flowPathForName(fileName)
        require(current.manifest.flows.none { it.path == target }) { "源文件名已存在：$fileName" }

        val originSource = current.flowSources[sourceFlowId]
            ?: throw ProjectStoreException("插件源码缺失：$sourceFlowId")
        val rootBlockId = "block-${UUID.randomUUID()}"
        val copied = reidentifyFlowSource(originSource, origin.rootBlockId, rootBlockId, sourceFlowId)
        val declaration = ProjectFlow(
            flowId = targetFlowId,
            path = target,
            rootBlockId = rootBlockId,
            params = origin.params,
            returns = origin.returns,
        )
        val copiedVariables = current.manifest.variables.filter { it.scope == ProjectVariableScope.FLOW && it.flowId == sourceFlowId }
            .map { it.copy(flowId = targetFlowId) }
        val updated = current.manifest.copy(flows = current.manifest.flows + declaration, variables = current.manifest.variables + copiedVariables)
        validateVariables(updated.variables, updated.flows)
        val flowFile = File(current.directory, target)
        checkContained(flowFile)
        require(!flowFile.exists()) { "插件文件已存在：$target" }
        writeAtomic(flowFile, copied.toByteArray(Charsets.UTF_8), backup = null)
        try {
            invalidateGeneration(current.directory)
            writeManifest(
                current.directory,
                updated,
                keepBackup = true,
            )
        } catch (failure: Throwable) {
            flowFile.delete()
            throw failure
        }
        val state = readState(current.directory)
        writeState(current.directory, state.copy(updatedAt = clock()))
        return openProject(projectId)
    }

    @Synchronized
    fun deleteProject(projectId: String) {
        val directory = existingProjectDirectory(projectId)
        if (!directory.deleteRecursively() && directory.exists()) {
            throw ProjectStoreException("删除项目失败：$projectId")
        }
    }

    /** Authoritative files plus portable organization; generated output and transient editor state are omitted. */
    @Synchronized
    fun exportProjectBackup(projectId: String, destination: OutputStream) {
        val current = openProject(projectId)
        val directories = readImageDirectories(projectId)
        if (directories.isNotEmpty()) writeImageDirectories(current.directory, directories)
        val organization = listOf(SOURCE_GROUPS, IMAGE_DIRECTORIES).filter { File(current.directory, it).isFile }.toSet()
        validateOrganization(current.directory, current.manifest, organization)
        val paths = backupPaths(current.manifest) + organization.sorted()
        var totalBytes = 0L
        try {
            ZipOutputStream(destination).use { archive ->
                archive.setLevel(BACKUP_COMPRESSION_LEVEL)
                paths.forEach { path ->
                    val file = File(current.directory, path)
                    checkContained(file)
                    require(file.isFile && file.canRead()) { "备份文件不存在：$path" }
                    val maximum = backupEntryLimit(path)
                    require(file.length() in 1..maximum) { "备份文件大小无效：$path" }
                    archive.putNextEntry(ZipEntry(path).apply { time = ZIP_EPOCH_MILLIS })
                    file.inputStream().use { input ->
                        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                        var entryBytes = 0L
                        while (true) {
                            val count = input.read(buffer)
                            if (count < 0) break
                            if (count == 0) continue
                            entryBytes += count
                            totalBytes += count
                            require(entryBytes <= maximum) { "备份文件超过大小上限：$path" }
                            require(totalBytes <= MAX_PROJECT_BACKUP_BYTES) { "项目备份超过 512 MiB" }
                            archive.write(buffer, 0, count)
                        }
                        require(entryBytes > 0) { "备份文件为空：$path" }
                    }
                    archive.closeEntry()
                }
            }
        } catch (error: Throwable) {
            throw if (error is ProjectStoreException || error is IllegalArgumentException) error
            else ProjectStoreException("导出项目备份失败", error)
        }
    }

    /** Imports a backup as a new local project and never overwrites the source project ID. */
    @Synchronized
    fun importProjectBackup(source: InputStream): ProjectSnapshot {
        ensureRoot()
        val staging = File(rootDirectory, ".project-import-${UUID.randomUUID()}")
        checkContained(staging)
        if (!staging.mkdirs()) throw ProjectStoreException("无法创建项目导入目录")
        var committed: File? = null
        try {
            val extracted = extractProjectBackup(source, staging)
            require(MANIFEST in extracted) { "备份缺少 project.json" }
            val loaded = readManifest(staging, recover = false)
            val organization = extracted.intersect(setOf(SOURCE_GROUPS, IMAGE_DIRECTORIES))
            val expected = backupPaths(loaded.manifest).toSet() + organization
            require(extracted == expected) {
                val unexpected = (extracted - expected).sorted().firstOrNull()
                val missing = (expected - extracted).sorted().firstOrNull()
                when {
                    unexpected != null -> "备份包含未声明文件：$unexpected"
                    missing != null -> "备份缺少声明文件：$missing"
                    else -> "备份文件清单不一致"
                }
            }
            validateImportedBackupFiles(staging, loaded.manifest)
            validateInterfaceReferences(loaded.manifest)
            validateOrganization(staging, loaded.manifest, organization)
            if (IMAGE_DIRECTORIES in organization) {
                decodeImageDirectories(File(staging, IMAGE_DIRECTORIES)).forEach { path ->
                    val folder = File(staging, "assets/images/$path")
                    requireImageContained(File(staging, "assets/images"), folder)
                    require(folder.isDirectory || folder.mkdirs()) { "无法恢复图片文件夹：$path" }
                }
            }

            val projectId = allocateImportedProjectId()
            val destination = projectDirectory(projectId)
            val importedManifest = loaded.manifest.copy(
                projectId = projectId,
                ownerId = null,
                cloudId = null,
                syncState = null,
                signature = null,
                licensePolicy = null,
            )
            writeManifest(staging, importedManifest, keepBackup = false)
            val createdAt = clock()
            writeState(staging, ProjectState(createdAt = createdAt, updatedAt = createdAt))
            if (!staging.renameTo(destination)) throw ProjectStoreException("无法提交导入项目")
            committed = destination
            return openProject(projectId)
        } catch (error: Throwable) {
            (committed ?: staging).deleteRecursively()
            throw if (error is ProjectStoreException || error is IllegalArgumentException) error
            else ProjectStoreException("导入项目备份失败", error)
        }
    }

    /** 本地备份槽位：`<root>/.backups/<projectId>/slot-N.asproject` + `slot-N.json`，每个项目固定 3 个。 */
    @Synchronized
    fun listBackupSlots(projectId: String): List<BackupSlot> {
        validateProjectId(projectId)
        return (1..BACKUP_SLOTS).map { readBackupSlot(projectId, it) }
    }

    /** 把当前权威项目导出到槽位；覆盖已有槽位，写入先落临时文件再原子替换。 */
    @Synchronized
    fun backupToSlot(projectId: String, slot: Int, remark: String? = null): BackupSlot {
        require(slot in 1..BACKUP_SLOTS) { "备份槽位无效：$slot" }
        val snapshot = openProject(projectId)
        val directory = backupDirectory(projectId)
        if (!directory.exists() && !directory.mkdirs()) throw ProjectStoreException("无法创建备份目录")
        val target = backupSlotFile(projectId, slot)
        val temp = File(directory, "${target.name}.tmp")
        try {
            FileOutputStream(temp).use { output -> exportProjectBackup(projectId, output) }
            if (target.exists() && !target.delete()) throw ProjectStoreException("无法覆盖备份槽位 $slot")
            if (!temp.renameTo(target)) throw ProjectStoreException("无法提交备份槽位 $slot")
        } finally {
            temp.delete()
        }
        val meta = BackupSlot(
            index = slot,
            createdAt = clock(),
            bytes = target.length(),
            remark = remark?.trim()?.takeIf { it.isNotEmpty() }?.take(MAX_BACKUP_REMARK_CHARS),
            projectName = snapshot.manifest.name,
        )
        writeAtomic(backupSlotMetaFile(projectId, slot), serializeBackupSlot(meta), backup = null)
        return meta
    }

    /** 恢复 = 把槽位当作备份包导入为一个新项目；沿用导入的全部校验，绝不覆盖本地项目。 */
    @Synchronized
    fun restoreBackupSlot(projectId: String, slot: Int): ProjectSnapshot {
        require(slot in 1..BACKUP_SLOTS) { "备份槽位无效：$slot" }
        validateProjectId(projectId)
        val file = backupSlotFile(projectId, slot)
        require(file.isFile) { "槽位 $slot 没有备份" }
        return file.inputStream().use(::importProjectBackup)
    }

    @Synchronized
    fun deleteBackupSlot(projectId: String, slot: Int) {
        require(slot in 1..BACKUP_SLOTS) { "备份槽位无效：$slot" }
        validateProjectId(projectId)
        backupSlotFile(projectId, slot).delete()
        backupSlotMetaFile(projectId, slot).delete()
        val directory = backupDirectory(projectId)
        if (directory.isDirectory && directory.listFiles().isNullOrEmpty()) directory.delete()
    }

    @Synchronized
    fun deleteProjectBackups(projectId: String) {
        validateProjectId(projectId)
        backupDirectory(projectId).deleteRecursively()
    }

    /** 备份管理页的数据：有至少一个槽位的项目，本地项目可能已删除。 */
    @Synchronized
    fun listBackedUpProjects(): List<BackupProjectSummary> {
        val root = File(rootDirectory, BACKUP_ROOT)
        return root.listFiles().orEmpty()
            .filter { it.isDirectory && PROJECT_ID.matches(it.name) }
            .mapNotNull { directory ->
                val slots = (1..BACKUP_SLOTS).map { readBackupSlot(directory.name, it) }
                val occupied = slots.filter(BackupSlot::occupied)
                if (occupied.isEmpty()) return@mapNotNull null
                val latest = occupied.maxByOrNull { it.createdAt ?: 0L }
                BackupProjectSummary(
                    projectId = directory.name,
                    projectName = latest?.projectName ?: directory.name,
                    slots = slots,
                    localProjectExists = projectDirectory(directory.name).isDirectory,
                    latestBackupAt = latest?.createdAt,
                    totalBytes = occupied.sumOf { it.bytes ?: 0L },
                )
            }
            .sortedWith(
                compareByDescending<BackupProjectSummary> { it.latestBackupAt ?: 0L }
                    .thenBy { it.projectName.lowercase(Locale.ROOT) },
            )
    }

    private fun backupDirectory(projectId: String): File =
        File(rootDirectory, "$BACKUP_ROOT/$projectId").also(::checkContained)

    private fun backupSlotFile(projectId: String, slot: Int): File =
        File(backupDirectory(projectId), "slot-$slot.asproject")

    private fun backupSlotMetaFile(projectId: String, slot: Int): File =
        File(backupDirectory(projectId), "slot-$slot.json")

    private fun readBackupSlot(projectId: String, slot: Int): BackupSlot {
        val file = backupSlotFile(projectId, slot)
        if (!file.isFile) return BackupSlot(slot, null, null, null, null)
        val meta = runCatching {
            JsonParser.parseString(backupSlotMetaFile(projectId, slot).readText(Charsets.UTF_8)).asJsonObject
        }.getOrNull()
        fun text(key: String): String? = meta?.get(key)?.takeIf { it.isJsonPrimitive && !it.isJsonNull }?.asString
        return BackupSlot(
            index = slot,
            createdAt = meta?.get("createdAt")?.takeIf { it.isJsonPrimitive }?.let { runCatching { it.asLong }.getOrNull() }
                ?: file.lastModified(),
            bytes = file.length(),
            remark = text("remark"),
            projectName = text("projectName"),
        )
    }

    private fun serializeBackupSlot(slot: BackupSlot): ByteArray = json.toJson(
        JsonObject().apply {
            addProperty("createdAt", slot.createdAt)
            addProperty("bytes", slot.bytes)
            addProperty("remark", slot.remark)
            addProperty("projectName", slot.projectName)
        },
    ).toByteArray(Charsets.UTF_8)

    private fun extractProjectBackup(source: InputStream, staging: File): Set<String> {
        val extracted = linkedSetOf<String>()
        var totalBytes = 0L
        ZipInputStream(source).use { archive ->
            while (true) {
                val entry = archive.nextEntry ?: break
                require(!entry.isDirectory) { "备份不能包含目录条目" }
                val path = validateBackupEntryPath(entry.name)
                require(extracted.add(path)) { "备份文件重复：$path" }
                require(extracted.size <= MAX_PROJECT_BACKUP_ENTRIES) { "备份文件数量超过上限" }
                val maximum = backupEntryLimit(path)
                if (entry.size >= 0) require(entry.size in 1..maximum) { "备份文件大小无效：$path" }
                val target = File(staging, path)
                require(isDescendant(staging, target)) {
                    "备份路径越界：$path"
                }
                target.parentFile?.let { parent ->
                    if (!parent.exists() && !parent.mkdirs()) throw ProjectStoreException("无法创建备份目录")
                }
                FileOutputStream(target).use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    var entryBytes = 0L
                    while (true) {
                        val count = archive.read(buffer)
                        if (count < 0) break
                        if (count == 0) continue
                        entryBytes += count
                        totalBytes += count
                        require(entryBytes <= maximum) { "备份文件超过大小上限：$path" }
                        require(totalBytes <= MAX_PROJECT_BACKUP_BYTES) { "项目备份超过 512 MiB" }
                        output.write(buffer, 0, count)
                    }
                    require(entryBytes > 0) { "备份文件为空：$path" }
                    output.flush()
                    output.fd.sync()
                }
                archive.closeEntry()
            }
        }
        return extracted
    }

    private fun validateImportedBackupFiles(directory: File, manifest: ProjectManifestDocument) {
        ensureDeclaredFiles(directory, manifest)
        when (manifest.sourceMode) {
            ProjectSourceMode.LUA -> manifest.luaFiles.forEach { path ->
                decodeLuaSource(File(directory, path).readBytes())
            }
            ProjectSourceMode.VISUAL -> manifest.flows.forEach { flow ->
                val file = File(directory, flow.path)
                require(file.length() in 1..MAX_FLOW_SOURCE_BYTES.toLong()) {
                    "插件文件大小无效：${flow.path}"
                }
                file.readText(Charsets.UTF_8)
            }
        }
        manifest.resources.forEach { resource ->
            val path = resource.get("path").asString
            val kind = when (resource.get("kind").asString) {
                "image" -> ProjectResourceKind.IMAGE
                "glyphDictionary" -> ProjectResourceKind.GLYPH_DICTIONARY
                "audio" -> ProjectResourceKind.AUDIO
                else -> error("不支持的资源类型")
            }
            validateImportedResource(kind, File(directory, path))
        }
    }

    private fun backupPaths(manifest: ProjectManifestDocument): List<String> = buildList {
        add(MANIFEST)
        when (manifest.sourceMode) {
            ProjectSourceMode.LUA -> addAll(manifest.luaFiles.sorted())
            ProjectSourceMode.VISUAL -> addAll(manifest.flows.map(ProjectFlow::path).sorted())
        }
        addAll(manifest.resources.map { it.get("path").asString }.sorted())
    }

    private fun validateBackupEntryPath(path: String): String {
        require(path.length in 1..256 && '\\' !in path && '\u0000' !in path) { "非法备份路径" }
        require(path.split('/').none { it.isEmpty() || it == "." || it == ".." }) { "备份路径越界" }
        require(
            path == MANIFEST || path in setOf(SOURCE_GROUPS, IMAGE_DIRECTORIES) || isValidLuaPath(path) ||
                (path.startsWith("visual/flows/") && path.endsWith(".jsonl")) ||
                path.startsWith("assets/images/") || path.startsWith("dictionaries/") || path.startsWith("assets/audio/"),
        ) { "备份包含非法文件：$path" }
        return path
    }

    private fun backupEntryLimit(path: String): Long = when {
        path == MANIFEST -> MAX_PROJECT_MANIFEST_BYTES
        path in setOf(SOURCE_GROUPS, IMAGE_DIRECTORIES) -> MAX_ORGANIZATION_BYTES
        isValidLuaPath(path) -> MAX_LUA_SOURCE_BYTES.toLong()
        path.startsWith("visual/flows/") -> MAX_FLOW_SOURCE_BYTES.toLong()
        path.startsWith("assets/images/") -> MAX_IMAGE_RESOURCE_BYTES
        path.startsWith("dictionaries/") -> MAX_GLYPH_DICTIONARY_BYTES
        path.startsWith("assets/audio/") -> MAX_AUDIO_RESOURCE_BYTES
        else -> throw IllegalArgumentException("备份包含非法文件：$path")
    }

    private fun allocateImportedProjectId(): String {
        repeat(MAX_IMPORT_ID_ATTEMPTS) {
            val candidate = validateProjectId(idFactory())
            if (!projectDirectory(candidate).exists()) return candidate
        }
        throw ProjectStoreException("无法为导入项目分配唯一 ID")
    }

    private fun readManifest(directory: File, recover: Boolean): LoadedManifest {
        val target = File(directory, MANIFEST)
        val temp = File(directory, "$MANIFEST.tmp")
        val backup = File(directory, MANIFEST_BACKUP)
        val previous = File(directory, "$MANIFEST.previous")

        decodeManifest(target)?.let { loaded ->
            if (recover) {
                if (temp.exists()) temp.delete()
                if (previous.exists()) previous.delete()
            }
            return loaded
        }
        val candidate = decodeManifest(temp) ?: decodeManifest(previous) ?: decodeManifest(backup)
            ?: throw ProjectStoreException("项目清单损坏且没有可恢复副本：${directory.name}")
        if (recover) writeAtomic(target, candidate.originalBytes, backup = null)
        return candidate
    }

    private fun decodeManifest(file: File): LoadedManifest? {
        if (!file.isFile || file.length() !in 1..MAX_PROJECT_MANIFEST_BYTES) return null
        return try { decodeManifestBytes(readBoundedStoreFile(file, MAX_PROJECT_MANIFEST_BYTES)) } catch (_: IOException) { null }
    }

    private fun decodeManifestBytes(bytes: ByteArray): LoadedManifest? {
        return try {
            val tree = JsonParser.parseString(bytes.toString(Charsets.UTF_8)) as? JsonObject ?: return null
            val version = tree.get("formatVersion")?.takeIf { it.isJsonPrimitive }?.asInt ?: return null
            val normalized = when (version) {
                CURRENT_PROJECT_FORMAT_VERSION -> tree
                LEGACY_PROJECT_FORMAT_VERSION -> tree.deepCopy().apply {
                    addProperty("formatVersion", CURRENT_PROJECT_FORMAT_VERSION)
                    addProperty("sourceMode", "visual")
                }
                else -> return null
            }
            validateJsonShape(normalized)
            if (!normalized.has("randomization")) normalized.add("randomization", json.toJsonTree(ProjectRandomization()))
            validateRandomizationJson(normalized.get("randomization"))
            if (!normalized.has("flows")) normalized.add("flows", com.google.gson.JsonArray())
            if (!normalized.has("resources")) normalized.add("resources", com.google.gson.JsonArray())
            // Gson allocates Kotlin DTOs without running constructor defaults. These optional
            // lists have the same empty default as Flow IR/schema; explicit null remains invalid.
            for (field in listOf("luaFiles", "luaDirectories", "variables")) {
                if (!normalized.has(field)) normalized.add(field, com.google.gson.JsonArray())
            }
            if (!normalized.has("debugSettings")) {
                normalized.add("debugSettings", JsonObject().apply {
                    addProperty("runDelayMs", 0)
                    addProperty("showRunPrompts", true)
                    addProperty("traceExecution", false)
                    addProperty("recordVisionMetrics", true)
                    add("runPromptFilters", json.toJsonTree(RunPromptFilters()))
                    add("popupStyle", json.toJsonTree(PopupStyle()))
                })
            } else {
                normalized.getAsJsonObject("debugSettings").let { settings ->
                    if (!settings.has("showRunPrompts")) settings.addProperty("showRunPrompts", true)
                    if (!settings.has("traceExecution")) settings.addProperty("traceExecution", false)
                    if (!settings.has("recordVisionMetrics")) settings.addProperty("recordVisionMetrics", true)
                    for (key in listOf("traceExecution", "recordVisionMetrics")) {
                        require(settings.get(key).isJsonPrimitive && settings.getAsJsonPrimitive(key).isBoolean) { "invalid observation setting" }
                    }
                    if (!settings.has("runPromptFilters")) settings.add("runPromptFilters", json.toJsonTree(RunPromptFilters()))
                    if (!settings.has("popupStyle")) {
                        settings.add("popupStyle", json.toJsonTree(PopupStyle()))
                    } else {
                        val style = settings.getAsJsonObject("popupStyle")
                        if (!style.has("widthPx")) settings.add("popupStyle", migrateLegacyPopupStyle(style))
                    }
                }
            }
            val manifest = json.fromJson(normalized, ProjectManifestDocument::class.java)
            validateManifest(manifest)
            LoadedManifest(manifest, bytes, version == LEGACY_PROJECT_FORMAT_VERSION)
        } catch (_: IOException) {
            null
        } catch (_: RuntimeException) {
            null
        }
    }

    private fun writeManifest(
        directory: File,
        manifest: ProjectManifestDocument,
        keepBackup: Boolean,
    ) {
        validateManifest(manifest)
        val tree = json.toJsonTree(manifest).asJsonObject
        OPTIONAL_MANIFEST_KEYS.forEach { key ->
            if (tree.get(key)?.isJsonNull == true) tree.remove(key)
        }
        tree.getAsJsonArray("flows").forEach { flowElement ->
            val flow = flowElement.asJsonObject
            if (!flow.has("returns")) flow.add("returns", JsonNull.INSTANCE)
        }
        val bytes = (json.toJson(tree) + "\n").toByteArray(Charsets.UTF_8)
        writeAtomic(
            File(directory, MANIFEST),
            bytes,
            if (keepBackup) File(directory, MANIFEST_BACKUP) else null,
        )
    }

    private fun writeAtomic(target: File, bytes: ByteArray, backup: File?) {
        target.parentFile?.let { parent ->
            if (!parent.exists() && !parent.mkdirs()) throw ProjectStoreException("无法创建目录：$parent")
        }
        val temp = File(target.parentFile, "${target.name}.tmp")
        writeAndSync(temp, bytes)
        if (target.exists() && backup != null) copyAndSync(target, backup)
        if (temp.renameTo(target)) return

        val previous = File(target.parentFile, "${target.name}.previous")
        if (previous.exists() && !previous.delete()) throw ProjectStoreException("无法清理旧事务文件：$previous")
        if (target.exists() && !target.renameTo(previous)) throw ProjectStoreException("无法暂存旧文件：$target")
        if (!temp.renameTo(target)) {
            if (!target.exists() && previous.exists()) previous.renameTo(target)
            throw ProjectStoreException("无法提交文件：$target")
        }
        previous.delete()
    }

    private fun writeAndSync(file: File, bytes: ByteArray) {
        FileOutputStream(file).use { output ->
            output.write(bytes)
            output.flush()
            output.fd.sync()
        }
    }

    private fun invalidateGeneration(directory: File) {
        val current = File(directory, GENERATION_RECORD)
        if (!current.isFile) return
        val stale = File(directory, STALE_GENERATION_RECORD)
        writeAtomic(stale, current.readBytes(), backup = null)
        if (!current.delete()) throw ProjectStoreException("无法使旧生成代次失效")
    }

    private fun requireResourcePathsMatch(
        current: ProjectSnapshot,
        expectedResourcePaths: Set<String>,
    ) {
        val actual = current.manifest.resources.map { it.get("path").asString }.toSet()
        if (actual != expectedResourcePaths) throw ProjectManifestConflictException()
    }

    private fun writeBoundedAndSync(target: File, source: InputStream, maximumBytes: Long) {
        target.parentFile?.let { parent ->
            if (!parent.exists() && !parent.mkdirs()) throw ProjectStoreException("无法创建导入目录")
        }
        FileOutputStream(target).use { output ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            var total = 0L
            while (true) {
                val count = source.read(buffer)
                if (count < 0) break
                if (count == 0) {
                    val byte = source.read()
                    if (byte < 0) break
                    total++
                    require(total <= maximumBytes) { "资源文件超过大小上限" }
                    output.write(byte)
                    continue
                }
                total += count
                require(total <= maximumBytes) { "资源文件超过大小上限" }
                output.write(buffer, 0, count)
            }
            require(total > 0) { "资源文件为空" }
            output.flush()
            output.fd.sync()
        }
    }

    private fun validateImportedResource(kind: ProjectResourceKind, file: File): String {
        val header = file.inputStream().use { input ->
            ByteArray(16).also { bytes ->
                var offset = 0
                while (offset < bytes.size) {
                    val count = input.read(bytes, offset, bytes.size - offset)
                    if (count < 0) break
                    offset += count
                }
            }
        }
        return when (kind) {
            ProjectResourceKind.IMAGE -> detectImageExtension(header)
                ?: throw IllegalArgumentException("仅支持 PNG、JPEG、WebP、GIF 或 BMP 图片")
            ProjectResourceKind.AUDIO -> projectAudioExtension(header)
                ?: throw IllegalArgumentException("仅支持 WAV、MP3、OGG、FLAC 或 M4A 音频")
            ProjectResourceKind.GLYPH_DICTIONARY -> {
                require(header.copyOfRange(0, 8).contentEquals("ASGLYPH\u0000".toByteArray())) {
                    "字库不是 ASGLYPH 格式"
                }
                val version = littleEndianU16(header, 8)
                val reserved = littleEndianU16(header, 10)
                val count = littleEndianU32(header, 12)
                require(version == 1 && reserved == 0 && count in 1..65_535) {
                    "仅支持有效的 ASGLYPH v1 字库"
                }
                ".asglyph"
            }
        }
    }

    private fun detectImageExtension(header: ByteArray): String? = when {
        header.copyOfRange(0, 8).contentEquals(
            byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A),
        ) -> ".png"
        header[0] == 0xFF.toByte() && header[1] == 0xD8.toByte() && header[2] == 0xFF.toByte() -> ".jpg"
        header.copyOfRange(0, 4).contentEquals("RIFF".toByteArray()) &&
            header.copyOfRange(8, 12).contentEquals("WEBP".toByteArray()) -> ".webp"
        header.copyOfRange(0, 6).toString(Charsets.US_ASCII) in setOf("GIF87a", "GIF89a") -> ".gif"
        header[0] == 'B'.code.toByte() && header[1] == 'M'.code.toByte() -> ".bmp"
        else -> null
    }

    private fun littleEndianU16(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xFF) or ((bytes[offset + 1].toInt() and 0xFF) shl 8)

    private fun littleEndianU32(bytes: ByteArray, offset: Int): Long =
        (bytes[offset].toLong() and 0xFF) or
            ((bytes[offset + 1].toLong() and 0xFF) shl 8) or
            ((bytes[offset + 2].toLong() and 0xFF) shl 16) or
            ((bytes[offset + 3].toLong() and 0xFF) shl 24)

    private fun sanitizeResourceStem(sourceName: String): String {
        val name = sourceName.substringAfterLast('/').substringAfterLast('\\').substringBeforeLast('.')
        val clean = buildString {
            name.trim().forEach { character ->
                append(if (character.isLetterOrDigit() || character in "._-") character else '_')
            }
        }.trim('.', '_', '-').take(80)
        return clean.ifEmpty { "resource" }
    }

    private fun uniqueResourceFileName(
        current: ProjectSnapshot,
        kind: ProjectResourceKind,
        stem: String,
        extension: String,
        imageDirectory: String = "",
    ): String {
        val prefix = when (kind) {
            ProjectResourceKind.IMAGE -> "assets/images/$imageDirectory"
            ProjectResourceKind.GLYPH_DICTIONARY -> "dictionaries/"
            ProjectResourceKind.AUDIO -> "assets/audio/"
        }
        val existing = current.manifest.resources.map { it.get("path").asString }.toSet()
        for (suffix in 1..MAX_PROJECT_RESOURCES + 1) {
            val candidate = if (suffix == 1) "$stem$extension" else "$stem-$suffix$extension"
            if ("$prefix$candidate" !in existing && !File(current.directory, "$prefix$candidate").exists()) {
                return candidate
            }
        }
        throw ProjectStoreException("无法生成唯一资源名")
    }

    private fun validateImageResourceDirectory(directory: String): String {
        if (directory.isEmpty()) return ""
        require(directory.length <= 120 && !directory.startsWith('/') && !directory.endsWith('/') &&
            '\\' !in directory && '\u0000' !in directory) {
            "图片文件夹路径无效"
        }
        val segments = directory.split('/')
        require(segments.size in 1..4 && segments.all { segment ->
            segment.length in 1..40 && segment != "." && segment != ".." &&
                segment.all { it.isLetterOrDigit() || it in "._-" }
        }) { "图片文件夹名称只能包含文字、数字、点、横线和下划线，最多四层" }
        return segments.joinToString("/", postfix = "/")
    }

    /** Image folders are real directories, unlike virtual plugin groups. Returns paths relative to images. */
    @Synchronized
    fun readImageDirectories(projectId: String): List<String> {
        val directory = existingProjectDirectory(projectId)
        val stored = File(directory, IMAGE_DIRECTORIES).let { if (it.isFile) decodeImageDirectories(it) else emptyList() }
        val found = stored.toMutableSet()
        val imageRoot = File(directory, "assets/images").canonicalFile
        requireImageContained(directory, imageRoot)
        fun scan(folder: File, prefix: String, depth: Int) {
            folder.listFiles().orEmpty().filter(File::isDirectory).forEach { child ->
                val path = prefix + child.name
                if (runCatching { validateImageResourceDirectory(path) }.isFailure) return@forEach
                requireImageContained(imageRoot, child)
                found += path
                require(found.size <= MAX_IMAGE_DIRECTORIES) { "图片文件夹数量超过 $MAX_IMAGE_DIRECTORIES" }
                if (depth < 4) scan(child, "$path/", depth + 1)
            }
        }
        if (imageRoot.isDirectory) scan(imageRoot, "", 1)
        return found.sorted()
    }

    @Synchronized
    fun createImageDirectory(projectId: String, relativePath: String): List<String> {
        val directory = existingProjectDirectory(projectId)
        val normalized = validateImageResourceDirectory(relativePath).removeSuffix("/")
        require(normalized.isNotEmpty()) { "图片根目录不能重复创建" }
        val target = File(directory, "assets/images/$normalized")
        val imageRoot = File(directory, "assets/images").canonicalFile
        requireImageContained(directory, imageRoot)
        requireImageContained(imageRoot, target)
        require(!target.exists()) { "图片文件夹已存在：$normalized" }
        val parts = normalized.split('/')
        val paths = (readImageDirectories(projectId) + parts.indices.map { parts.take(it + 1).joinToString("/") }).distinct().sorted()
        require(paths.size <= MAX_IMAGE_DIRECTORIES) { "图片文件夹数量超过 $MAX_IMAGE_DIRECTORIES" }
        // Create first: failed mkdir must not publish a nonexistent folder. If metadata writing
        // fails, the real empty directory remains discoverable on the next read/export.
        require(target.mkdirs()) { "无法创建图片文件夹：$normalized" }
        writeImageDirectories(directory, paths)
        touchProject(directory)
        return paths
    }

    private fun writeImageDirectories(directory: File, paths: List<String>) {
        val document = JsonObject().apply {
            addProperty("version", 1)
            add("directories", JsonArray().apply { paths.forEach(::add) })
        }
        writeAtomic(File(directory, IMAGE_DIRECTORIES), json.toJson(document).toByteArray(Charsets.UTF_8), backup = null)
    }

    /** Canonical, separator-aware containment using only APIs available on Android 7. */
    private fun requireImageContained(parent: File, child: File) {
        require(isDescendant(parent, child)) { "图片目录越界" }
    }

    private fun isDescendant(parent: File, child: File): Boolean {
        val prefix = parent.canonicalPath.trimEnd(File.separatorChar) + File.separator
        return child.canonicalPath.startsWith(prefix, ignoreCase = File.separatorChar == '\\')
    }

    private fun organizationDocument(file: File): JsonObject {
        require(file.length() in 1..MAX_ORGANIZATION_BYTES) { "项目组织配置大小无效" }
        val parsed = try {
            JsonParser.parseString(file.readText(Charsets.UTF_8))
        } catch (failure: com.google.gson.JsonParseException) {
            throw IllegalArgumentException("项目组织配置 JSON 无效", failure)
        }
        require(parsed.isJsonObject) { "项目组织配置必须是对象" }
        return parsed.asJsonObject
    }

    private fun decodeImageDirectories(file: File): List<String> {
        val document = organizationDocument(file)
        require(document.keySet() == setOf("version", "directories") && document.get("version").toString() == "1") { "图片文件夹配置版本无效" }
        require(document.get("directories").isJsonArray) { "图片文件夹清单必须是数组" }
        val entries = document.getAsJsonArray("directories")
        require(entries.size() <= MAX_IMAGE_DIRECTORIES) { "图片文件夹数量超过上限" }
        val paths = entries.map {
            require(it.isJsonPrimitive && it.asJsonPrimitive.isString) { "图片文件夹路径必须是字符串" }
            val path = it.asString
            require(path.isNotEmpty() && validateImageResourceDirectory(path).removeSuffix("/") == path) { "图片文件夹路径无效" }
            path
        }
        require(paths == paths.distinct().sorted()) { "图片文件夹清单必须有序且不重复" }
        require(paths.all { path -> path.substringBeforeLast('/', "").let { it.isEmpty() || it in paths } }) { "图片文件夹缺少父目录" }
        return paths
    }

    private fun validateOrganization(directory: File, manifest: ProjectManifestDocument, paths: Set<String>) {
        if (IMAGE_DIRECTORIES in paths) decodeImageDirectories(File(directory, IMAGE_DIRECTORIES))
        if (SOURCE_GROUPS !in paths) return
        require(manifest.sourceMode == ProjectSourceMode.VISUAL) { "Lua 项目不能包含插件分组" }
        val file = File(directory, SOURCE_GROUPS)
        val document = organizationDocument(file)
        require(document.keySet() == setOf("version", "groups") && document.get("version").toString() == SOURCE_GROUPS_VERSION.toString()) { "插件分组配置版本无效" }
        require(document.get("groups").isJsonArray) { "插件分组必须是数组" }
        val groups = document.getAsJsonArray("groups")
        require(groups.size() <= MAX_SOURCE_GROUPS) { "插件分组数量超过上限" }
        val names = mutableSetOf<String>()
        val members = mutableSetOf<String>()
        val validIds = manifest.flows.map(ProjectFlow::flowId).toSet()
        groups.forEach { element ->
            require(element.isJsonObject) { "插件分组必须是对象" }
            val group = element.asJsonObject
            require(group.keySet() == setOf("name", "flowIds")) { "插件分组字段无效" }
            val name = group.get("name")
            require(name.isJsonPrimitive && name.asJsonPrimitive.isString && isValidFlowName(name.asString) && names.add(name.asString)) { "插件分组名称无效或重复" }
            require(group.get("flowIds").isJsonArray) { "插件分组成员必须是数组" }
            val ids = group.getAsJsonArray("flowIds")
            require(ids.size() <= MAX_PROJECT_FLOWS) { "插件分组成员超过上限" }
            ids.forEach { id ->
                require(id.isJsonPrimitive && id.asJsonPrimitive.isString && id.asString in validIds && members.add(id.asString)) { "插件分组成员不存在或重复" }
            }
        }
    }

    private fun resourceManifestKind(kind: ProjectResourceKind): String = when (kind) {
        ProjectResourceKind.IMAGE -> "image"
        ProjectResourceKind.GLYPH_DICTIONARY -> "glyphDictionary"
        ProjectResourceKind.AUDIO -> "audio"
    }

    private fun isResourceReferenced(snapshot: ProjectSnapshot, path: String): Boolean =
        snapshot.manifest.runnerUi?.let { com.autoscript.script.ui.ScriptUiDefinition.parse(it.toString()).fields.any { field -> field.control == com.autoscript.script.ui.UiControl.IMAGE && field.initialValue == path } } == true ||
        when (snapshot.manifest.sourceMode) {
            ProjectSourceMode.LUA -> snapshot.luaSources.values.any { it.contains(path) }
            ProjectSourceMode.VISUAL -> snapshot.flowSources.any { (flowId, source) ->
                source.lineSequence().filter(String::isNotBlank).any { line ->
                    val node = runCatching { JsonParser.parseString(line).asJsonObject }
                        .getOrElse { throw ProjectStoreException("插件 $flowId 损坏，不能安全删除资源") }
                    jsonContainsString(node.get("args"), path)
                }
            }
        }

    private fun jsonContainsString(value: com.google.gson.JsonElement?, target: String): Boolean = when {
        value == null || value.isJsonNull -> false
        value.isJsonPrimitive -> value.asJsonPrimitive.isString && value.asString == target
        value.isJsonArray -> value.asJsonArray.any { jsonContainsString(it, target) }
        value.isJsonObject -> value.asJsonObject.entrySet().any { jsonContainsString(it.value, target) }
        else -> false
    }

    private fun touchProject(directory: File) {
        val state = readState(directory)
        writeState(directory, state.copy(updatedAt = clock()))
    }

    /**
     * 为 Flow 副本重新分配全部结构身份。
     *
     * `nodeId` 和 `blockId` 全部换新，`parentId` 与 `childBlocks` 跟着重映射，
     * 原根积木映射到副本的新根积木。`orderKey`、`kind`、`args` 原样保留，
     * 所以副本的结构和参数与原件逐节点一致，只是身份不重叠。
     */
    private fun reidentifyFlowSource(
        source: String,
        originRootBlockId: String,
        targetRootBlockId: String,
        sourceFlowId: String,
    ): String {
        fun corrupt() = ProjectStoreException("插件 $sourceFlowId 损坏，不能安全复制")
        fun text(node: JsonObject, key: String): String? =
            node.get(key)?.takeIf { it.isJsonPrimitive }?.asString

        val nodes = source.lineSequence().filter(String::isNotBlank).map { line ->
            runCatching { JsonParser.parseString(line).asJsonObject }.getOrElse { throw corrupt() }
        }.toList()
        if (nodes.isEmpty()) throw corrupt()

        val nodeIds = HashMap<String, String>()
        val blockIds = hashMapOf(originRootBlockId to targetRootBlockId)
        nodes.forEach { node ->
            val nodeId = text(node, "nodeId") ?: throw corrupt()
            nodeIds.getOrPut(nodeId) { "node-${UUID.randomUUID()}" }
            val blockId = text(node, "blockId") ?: throw corrupt()
            blockIds.getOrPut(blockId) { "block-${UUID.randomUUID()}" }
            node.getAsJsonObject("childBlocks")?.entrySet()?.forEach { slot ->
                val childBlockId = slot.value?.takeIf { it.isJsonPrimitive }?.asString ?: throw corrupt()
                blockIds.getOrPut(childBlockId) { "block-${UUID.randomUUID()}" }
            }
        }

        return nodes.joinToString(separator = "\n", postfix = "\n") { node ->
            val copy = node.deepCopy()
            copy.addProperty("nodeId", nodeIds.getValue(text(node, "nodeId")!!))
            copy.addProperty("blockId", blockIds.getValue(text(node, "blockId")!!))
            text(node, "parentId")?.let { parentId ->
                copy.addProperty("parentId", nodeIds[parentId] ?: throw corrupt())
            }
            node.getAsJsonObject("childBlocks")?.let { slots ->
                val remapped = JsonObject()
                slots.entrySet().forEach { slot ->
                    remapped.addProperty(slot.key, blockIds.getValue(slot.value.asString))
                }
                copy.add("childBlocks", remapped)
            }
            copy.toString()
        }
    }

    private fun isFlowReferenced(sources: Map<String, String>, targetFlowId: String): Boolean =
        sources.any { (sourceFlowId, source) ->
            if (sourceFlowId == targetFlowId) return@any false
            source.lineSequence().filter(String::isNotBlank).any { line ->
                val node = runCatching { JsonParser.parseString(line).asJsonObject }
                    .getOrElse { throw ProjectStoreException("插件 $sourceFlowId 损坏，不能安全删除") }
                node.get("kind")?.asString == "flow.call" &&
                    node.getAsJsonObject("args")?.get("targetFlowId")?.asString == targetFlowId
            }
        }

    private fun interfaceFlowReferences(manifest: ProjectManifestDocument, flowId: String): List<String> {
        val source = manifest.runnerUi?.takeIf { it.has("version") } ?: return emptyList()
        val model = com.autoscript.script.ui.ScriptUiDefinition.parse(source.toString())
        return model.fields.mapNotNull { field ->
            val ui = field.ui ?: return@mapNotNull null
            when {
                ui.events.values.any { it == "flow:$flowId" } -> "${field.label}的事件"
                ui.binding?.startsWith("flow:$flowId:") == true -> "${field.label}的局部变量绑定"
                else -> null
            }
        }
    }

    private fun copyAndSync(source: File, target: File) {
        target.parentFile?.let { if (!it.exists() && !it.mkdirs()) throw IOException("mkdir $it") }
        writeAndSync(target, source.readBytes())
    }

    private fun ensureDeclaredFiles(directory: File, manifest: ProjectManifestDocument) {
        when (manifest.sourceMode) {
            ProjectSourceMode.LUA -> {
                manifest.luaDirectories.forEach { path ->
                    val folder = File(directory, path)
                    checkContained(folder)
                    if (!folder.exists() && !folder.mkdirs()) throw ProjectStoreException("无法恢复 Lua 文件夹：$path")
                    require(folder.isDirectory) { "Lua 文件夹不是目录：$path" }
                }
                manifest.luaFiles.forEach { path ->
                    val file = File(directory, path)
                    checkContained(file)
                    // The legacy entry retains atomic recovery from its .bak/.previous file.
                    if (path != manifest.entryPoint) require(file.isFile) { "缺少 Lua 文件：$path" }
                }
            }
            ProjectSourceMode.VISUAL -> manifest.flows.forEach { flow ->
                val file = File(directory, flow.path)
                checkContained(file)
                if (!file.exists()) recoverMissingFlow(directory, flow, file)
                require(file.isFile) { "缺少插件文件：${flow.path}" }
            }
        }
        manifest.resources.forEach { resource ->
            val path = resource.get("path").asString
            val file = File(directory, path)
            checkContained(file)
            require(file.isFile) { "缺少资源文件：$path" }
            if (resource.get("kind").asString == "audio") require(file.length() in 1..MAX_AUDIO_RESOURCE_BYTES) { "音频资源大小无效" }
        }
        require(manifest.resources.filter { it.get("kind").asString == "audio" }.sumOf { File(directory, it.get("path").asString).length() } <= 64L * 1024 * 1024) { "项目音频总量超过64MiB" }
    }

    /** Only the previously committed file is safe to restore without guessing draft intent. */
    private fun recoverMissingFlow(directory: File, flow: ProjectFlow, target: File) {
        val previous = File(target.parentFile, "${target.name}.previous")
        checkContained(previous)
        if (!previous.isFile) return
        require(!File(target.parentFile, "${target.name}.tmp").exists()) { "插件存在未提交草稿，请先备份并检查恢复副本" }
        require(previous.length() in 1..MAX_FLOW_SOURCE_BYTES.toLong()) { "插件恢复副本超过预算" }
        val bytes = readBoundedStoreFile(previous, MAX_FLOW_SOURCE_BYTES.toLong())
        require(bytes.size in 1..MAX_FLOW_SOURCE_BYTES) { "插件恢复副本超过预算" }
        val source = Charsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(bytes)).toString()
        val nodeIds = HashSet<String>()
        var hasRoot = false
        source.lineSequence().filter(String::isNotBlank).forEach { line ->
            val node = JsonParser.parseString(line).asJsonObject
            val nodeId = node.get("nodeId")?.asString
            require(!nodeId.isNullOrBlank() && nodeId.length <= 128 && nodeIds.add(nodeId)) { "插件恢复副本积木身份无效" }
            if (node.get("blockId")?.asString == flow.rootBlockId &&
                (node.get("parentId") == null || node.get("parentId").isJsonNull)) hasRoot = true
        }
        require(hasRoot) { "插件恢复副本根身份无效" }
        invalidateGeneration(directory)
        writeAtomic(target, bytes, backup = null)
    }

    private fun readBoundedStoreFile(file: File, maximumBytes: Long): ByteArray {
        java.io.ByteArrayOutputStream().use { output ->
            file.inputStream().use { input ->
                val buffer = ByteArray(16_384)
                var total = 0L
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    total += count
                    if (total > maximumBytes) throw IOException("项目恢复文件超过预算")
                    output.write(buffer, 0, count)
                }
            }
            return output.toByteArray()
        }
    }

    private fun readTextWithRecovery(directory: File, path: String, backupPath: String): String {
        val target = File(directory, path)
        checkContained(target)
        val temp = File(target.parentFile, "${target.name}.tmp")
        val previous = File(target.parentFile, "${target.name}.previous")
        if (target.isFile) {
            temp.delete()
            previous.delete()
            return decodeLuaSource(target.readBytes())
        }
        val recovery = listOf(temp, previous, File(directory, backupPath)).firstOrNull(File::isFile)
            ?: throw ProjectStoreException("缺少脚本入口：$path")
        val bytes = recovery.readBytes()
        writeAtomic(target, bytes, backup = null)
        return decodeLuaSource(bytes)
    }

    private fun readLuaFile(directory: File, path: String): String {
        val target = File(directory, path)
        checkContained(target)
        require(target.isFile) { "缺少 Lua 文件：$path" }
        return decodeLuaSource(target.readBytes())
    }

    private fun saveLuaFileInternal(current: ProjectSnapshot, path: String, source: String): ProjectSnapshot {
        val sourceBytes = source.toByteArray(Charsets.UTF_8)
        require(sourceBytes.size <= MAX_LUA_SOURCE_BYTES) { "Lua 源码不能超过 16 MiB" }
        val backup = if (path == current.manifest.entryPoint) File(current.directory, LUA_BACKUP) else null
        writeAtomic(File(current.directory, path), sourceBytes, backup)
        invalidateGeneration(current.directory)
        touchProject(current.directory)
        return openProject(current.manifest.projectId)
    }

    private fun decodeLuaSource(bytes: ByteArray): String {
        require(bytes.size <= MAX_LUA_SOURCE_BYTES) { "Lua 源码不能超过 16 MiB" }
        return bytes.toString(Charsets.UTF_8)
    }

    private fun validateManifest(manifest: ProjectManifestDocument) {
        require(manifest.formatVersion == CURRENT_PROJECT_FORMAT_VERSION) { "不支持的项目版本" }
        require(manifest.flowSchemaVersion == 1) { "不支持的插件版本" }
        require(RUNTIME_API.matches(manifest.runtimeApi)) { "非法 Runtime API 版本" }
        validateProjectId(manifest.projectId)
        validateName(manifest.name)
        require(manifest.design.width in 1..32_768 && manifest.design.height in 1..32_768) {
            "非法设计分辨率"
        }
        require(manifest.design.scaleMode in setOf("letterbox", "crop", "stretch")) { "非法缩放模式" }
        require(manifest.design.orientationPolicy in setOf("follow", "portrait", "landscape")) {
            "非法方向策略"
        }
        require(manifest.capabilities.distinct().size == manifest.capabilities.size) { "能力声明重复" }
        require(manifest.capabilities.size <= MAX_PROJECT_CAPABILITIES) { "项目能力数量超过64" }
        require(manifest.capabilities.all { it.length <= MAX_CAPABILITY_LENGTH && CAPABILITY.matches(it) }) {
            "非法能力声明"
        }
        validateResources(manifest.resources)
        validateVariables(manifest.variables, manifest.flows)
        validateDebugSettings(manifest.debugSettings)
        require(manifest.randomization.valid()) { "随机策略参数无效" }
        validateRunnerUi(manifest.runnerUi)
        when (manifest.sourceMode) {
            ProjectSourceMode.LUA -> {
                val entry = requireNotNull(manifest.entryPoint) { "Lua 项目缺少入口" }
                require(isValidLuaPath(entry)) { "Lua 项目入口路径无效" }
                // Empty is the v2 pre-file-tree representation and is upgraded on openProject.
                val luaFiles = manifest.luaFiles.ifEmpty { listOf(entry) }
                val luaDirectories = manifest.luaDirectories.ifEmpty { listOf(LUA_MODULE_ROOT) }
                require(luaFiles.size <= MAX_LUA_FILES) { "Lua 文件清单超过上限" }
                require(luaFiles == luaFiles.distinct().sorted()) { "Lua 文件清单必须有序且不可重复" }
                require(luaFiles.all(::isValidLuaPath)) { "Lua 文件路径无效" }
                require(entry in luaFiles) { "Lua 项目入口不在 Lua 文件清单中" }
                require(luaDirectories.size <= MAX_LUA_DIRECTORIES) { "Lua 文件夹清单超过上限" }
                require(luaDirectories == luaDirectories.distinct().sorted()) { "Lua 文件夹清单必须有序且不可重复" }
                require(luaDirectories.all(::isValidLuaDirectory)) { "Lua 文件夹路径无效" }
                require(LUA_MODULE_ROOT in luaDirectories) { "Lua 根文件夹缺失" }
                require(luaFiles.flatMap(::luaParentDirectories).all { it in luaDirectories }) { "Lua 文件父文件夹缺失" }
                require(manifest.entryFlowId == null && manifest.flows.isEmpty()) { "Lua 项目不能声明插件入口" }
            }
            ProjectSourceMode.VISUAL -> {
                require(manifest.entryPoint == null) { "可视化项目不能声明 Lua 入口" }
                require(manifest.luaFiles.isEmpty()) { "可视化项目不能声明 Lua 文件" }
                require(manifest.luaDirectories.isEmpty()) { "可视化项目不能声明 Lua 文件夹" }
                val entry = requireNotNull(manifest.entryFlowId) { "可视化项目缺少入口插件" }
                require(manifest.flows.isNotEmpty() && manifest.flows.any { it.flowId == entry }) {
                    "入口插件不存在"
                }
                require(manifest.flows.map(ProjectFlow::flowId).distinct().size == manifest.flows.size) {
                    "插件 ID 重复"
                }
                require(manifest.flows.map(ProjectFlow::path).distinct().size == manifest.flows.size) {
                    "插件路径重复"
                }
                require(manifest.flows.map(ProjectFlow::rootBlockId).distinct().size == manifest.flows.size) {
                    "根积木 ID 重复"
                }
                manifest.flows.forEach { flow ->
                    require(FLOW_ID.matches(flow.flowId)) { "非法插件 ID：${flow.flowId}" }
                    require(flow.rootBlockId.isNotEmpty() && flow.rootBlockId.length <= 128) { "非法根积木 ID" }
                    require(isValidFlowPath(flow.path)) { "非法插件路径：${flow.path}" }
                }
            }
        }
    }

    private fun validateResources(resources: List<JsonObject>) {
        require(resources.size <= 256) { "资源数量超过上限" }
        val paths = mutableSetOf<String>()
        resources.forEach { resource ->
            require(resource.keySet() == setOf("kind", "path")) { "非法资源声明" }
            val kind = resource.get("kind")?.asString ?: error("资源缺少 kind")
            val path = resource.get("path")?.asString ?: error("资源缺少 path")
            require(path.length in 1..256 && !path.contains('\\') && !path.contains('\u0000')) {
                "非法资源路径"
            }
            require(path.split('/').none { it.isEmpty() || it == "." || it == ".." }) { "资源路径越界" }
            require(
                (kind == "image" && path.startsWith("assets/images/")) ||
                    (kind == "glyphDictionary" && path.startsWith("dictionaries/") && path.endsWith(".asglyph")) ||
                    (kind == "audio" && path.startsWith("assets/audio/") && path.substringAfterLast('.').lowercase() in setOf("wav", "mp3", "ogg", "flac", "m4a")),
            ) { "资源类型与路径不匹配" }
            require(paths.add(path)) { "资源路径重复" }
        }
    }

    private fun validateVariables(variables: List<ProjectVariable>, flows: List<ProjectFlow>) {
        require(variables.size <= 512) { "变量数量超过512" }
        val keys = mutableSetOf<Pair<ProjectVariableScope, String>>()
        variables.forEach { variable ->
            require(PARAMETER_NAME.matches(variable.name) && variable.name.length <= 64) { "非法变量名：${variable.name}" }
            require(keys.add(variable.scope to "${variable.flowId.orEmpty()}:${variable.name}")) { "变量声明重复：${variable.name}" }
            if (variable.scope == ProjectVariableScope.GLOBAL) require(variable.flowId == null) { "全局变量不能指定插件" }
            else require(variable.flowId != null && flows.any { it.flowId == variable.flowId }) { "局部变量缺少有效插件" }
        }
    }

    private fun migrateLegacyPopupStyle(style: JsonObject): JsonElement {
        require(style.keySet().all { it in setOf(
            "widthDp", "heightDp", "xPercent", "yPercent", "backgroundColor", "textColor",
            "fontSp", "cornerDp", "durationMs", "textAlign",
        ) }) { "旧版弹窗样式含未知字段" }
        fun oldInt(key: String, default: Int) = style.get(key)?.asInt ?: default
        val widthDp = oldInt("widthDp", 260)
        val heightDp = oldInt("heightDp", 72)
        val xPercent = oldInt("xPercent", 50)
        val yPercent = oldInt("yPercent", 40)
        val fontSp = oldInt("fontSp", 14)
        val cornerDp = oldInt("cornerDp", 8)
        require(widthDp in 120..600 && heightDp in 44..400 && xPercent in 0..100 &&
            yPercent in 0..100 && fontSp in 10..48 && cornerDp in 0..48) {
            "旧版弹窗样式无效"
        }
        val metrics = runCatching { android.content.res.Resources.getSystem().displayMetrics }.getOrNull()
        val density = metrics?.density?.takeIf { it > 0f } ?: 1f
        val scaledDensity = metrics?.scaledDensity?.takeIf { it > 0f } ?: density
        val widthPx = (widthDp * density).roundToInt().coerceIn(120, 2160)
        val heightPx = (heightDp * density).roundToInt().coerceIn(44, 1200)
        val xPx = metrics?.widthPixels?.takeIf { it > 0 }?.let {
            (it * xPercent / 100 - widthPx / 2).coerceAtLeast(0)
        } ?: -1
        val yPx = metrics?.heightPixels?.takeIf { it > 0 }?.let {
            (it * yPercent / 100 - heightPx / 2).coerceAtLeast(0)
        } ?: -1
        return json.toJsonTree(PopupStyle(
            widthPx = widthPx, heightPx = heightPx, xPx = xPx, yPx = yPx,
            backgroundColor = style.get("backgroundColor")?.asString ?: "#B3000000",
            textColor = style.get("textColor")?.asString ?: "#FFFFFFFF",
            fontPx = (fontSp * scaledDensity).roundToInt().coerceIn(10, 160),
            cornerPx = (cornerDp * density).roundToInt().coerceIn(0, 200),
            durationMs = oldInt("durationMs", 3_000),
            textAlign = style.get("textAlign")?.asString ?: "center",
        ))
    }

    private fun validateDebugSettings(settings: ProjectDebugSettings) {
        require(settings.runDelayMs in 0..60_000) { "运行延迟必须在 0 至 60000 毫秒之间" }
        val style = settings.popupStyle
        require(style.widthPx in 1..2160 && style.heightPx in 1..1200 &&
            style.xPx in -1..10_000 && style.yPx in -1..10_000 &&
            style.fontPx in 10..160 && style.cornerPx in 0..200 &&
            style.durationMs in 500..30_000 && style.textAlign in setOf("left", "center", "right") &&
            listOf(style.backgroundColor, style.textColor).all { it.matches(Regex("#[0-9A-Fa-f]{8}")) }) {
            "弹出提示样式无效"
        }
        val filters = settings.runPromptFilters
        require(filters.variableScope == "all" || filters.variableScope == "global" ||
            (filters.variableScope.startsWith("flow:") && filters.variableScope.length <= 133)) {
            "运行提示变量范围无效"
        }
        require(filters.variableType in setOf("all", "integer", "number", "string", "image")) {
            "运行提示变量类型无效"
        }
        require(filters.variableName == "all" ||
            (filters.variableName.length <= 64 && PARAMETER_NAME.matches(filters.variableName))) {
            "运行提示变量名称无效"
        }
    }

    private fun validateInterfaceReferences(manifest: ProjectManifestDocument) {
        val json = manifest.runnerUi?.takeIf { it.has("version") } ?: return
        val model = com.autoscript.script.ui.ScriptUiDefinition.parse(json.toString())
        val bindings = mutableSetOf<String>()
        model.fields.forEach { field ->
            try {
            val ui = requireNotNull(field.ui)
            if (field.control == com.autoscript.script.ui.UiControl.IMAGE && field.initialValue.isNotBlank())
                require(manifest.resources.any { it.get("kind")?.asString == "image" && it.get("path")?.asString == field.initialValue }) { "${field.label} 图片不是已声明的项目资源" }
            ui.binding?.let { binding ->
                require(bindings.add(binding)) { "多个控件不能绑定同一变量" }
                val parts = binding.split(':')
                if (parts[0] == "param") {
                    val entry = manifest.flows.find { it.flowId == manifest.entryFlowId }
                    val parameter = entry?.params?.find { it.get("name").asString == parts[1] } ?: throw IllegalArgumentException("绑定参数不存在")
                    require(uiParameterCompatible(field.kind, parameter.get("type").asString)) { "绑定参数类型不匹配" }
                } else {
                    val variable = manifest.variables.find { it.name == parts.last() &&
                        if (parts[0] == "global") it.scope == ProjectVariableScope.GLOBAL else it.scope == ProjectVariableScope.FLOW && it.flowId == parts[1] } ?: throw IllegalArgumentException("绑定变量不存在")
                    require(if (field.kind == "number") variable.type == ProjectVariableType.NUMBER else if (field.kind in setOf("integer", "boolean")) variable.type in setOf(ProjectVariableType.INTEGER, ProjectVariableType.NUMBER) else variable.type == ProjectVariableType.STRING) { "绑定变量类型不匹配" }
                }
            }
            ui.events.values.filter { it.startsWith("flow:") }.forEach { action ->
                val plugin = manifest.flows.find { it.flowId == action.removePrefix("flow:") } ?: throw IllegalArgumentException("事件插件不存在")
                plugin.params.forEach { p ->
                    val name = p.get("name").asString; val type = p.get("type").asString
                    require(when (name) { "controlId", "event" -> type == "string"; "value" -> uiParameterCompatible(field.kind, type); else -> !p.get("required").asBoolean }) { "事件插件参数不匹配：$name" }
                }
            }
            } catch (failure: IllegalArgumentException) {
                throw ProjectInterfaceReferenceException(field.id, failure)
            }
        }
    }
    private fun uiParameterCompatible(kind: String, type: String) = when (kind) {
        "number" -> type == "number"; "integer" -> type in setOf("integer", "number"); "boolean" -> type == "boolean"; else -> type == "string"
    }
    private fun validateRunnerUi(runnerUi: JsonObject?) {
        if (runnerUi == null) return
        if (runnerUi.has("version")) {
            com.autoscript.script.ui.ScriptUiDefinition.parse(runnerUi.toString())
            return
        }
        require(runnerUi.keySet() == setOf("description", "fields")) { "runnerUi 含未知字段" }
        val description = runnerUi.get("description")
        require(description != null) { "runnerUi 缺少description" }
        if (!description.isJsonNull) {
            require(description.isJsonPrimitive && description.asJsonPrimitive.isString) {
                "runnerUi.description 必须是字符串或null"
            }
            require(description.asString.codePointLength() <= 512 && description.asString.none(Char::isISOControl)) {
                "runnerUi.description 无效"
            }
        }
        val fieldsElement = runnerUi.get("fields")
        require(fieldsElement != null && fieldsElement.isJsonArray) { "runnerUi.fields必须是数组" }
        val fields = fieldsElement.asJsonArray
        require(fields.size() in 1..32) { "runnerUi字段数量必须为1至32" }
        val ids = mutableSetOf<String>()
        fields.forEach { element ->
            require(element.isJsonObject) { "runnerUi字段必须是对象" }
            val field = element.asJsonObject
            require(
                field.keySet() == setOf(
                    "id", "label", "kind", "required", "initialValue", "minimum", "maximum", "options",
                ),
            ) { "runnerUi字段不完整或含未知字段" }
            fun requiredString(name: String): String {
                val value = field.get(name)
                require(value != null && value.isJsonPrimitive && value.asJsonPrimitive.isString) {
                    "runnerUi.$name 必须是字符串"
                }
                return value.asString
            }
            fun nullableInteger(name: String): Long? {
                val value = field.get(name)
                require(value != null) { "runnerUi 缺少$name" }
                if (value.isJsonNull) return null
                require(value.isJsonPrimitive && value.asJsonPrimitive.isNumber) {
                    "runnerUi.$name 必须是整数或null"
                }
                return value.asString.toLongOrNull() ?: error("runnerUi.$name 必须是整数")
            }
            val id = requiredString("id")
            val label = requiredString("label")
            val kind = requiredString("kind")
            require(PARAMETER_NAME.matches(id) && id.length <= 64 && ids.add(id)) { "runnerUi字段ID无效或重复" }
            require(label.codePointLength() in 1..64 && label.none(Char::isISOControl)) { "runnerUi字段标题无效" }
            require(kind in RUNNER_UI_KINDS) { "runnerUi字段类型无效" }
            val required = field.get("required")
            require(required != null && required.isJsonPrimitive && required.asJsonPrimitive.isBoolean) {
                "runnerUi.required必须是布尔值"
            }
            val initial = field.get("initialValue")
            require(initial != null) { "runnerUi 缺少initialValue" }
            val minimum = nullableInteger("minimum")
            val maximum = nullableInteger("maximum")
            val optionsElement = field.get("options")
            require(optionsElement != null && optionsElement.isJsonArray) { "runnerUi.options必须是数组" }
            val options = optionsElement.asJsonArray
            when (kind) {
                "text" -> require(initial.isJsonPrimitive && initial.asJsonPrimitive.isString &&
                    initial.asString.codePointLength() <= 256 && '\u0000' !in initial.asString &&
                    minimum == null && maximum == null &&
                    options.size() == 0) { "runnerUi文本字段无效" }
                "integer" -> {
                    require(initial.isJsonPrimitive && initial.asJsonPrimitive.isNumber && options.size() == 0) {
                        "runnerUi整数字段无效"
                    }
                    val value = initial.asString.toLongOrNull() ?: error("runnerUi整数默认值无效")
                    val low = minimum ?: -1_000_000_000L
                    val high = maximum ?: 1_000_000_000L
                    require(low <= high && low <= value && value <= high) { "runnerUi整数默认值越界" }
                }
                "boolean" -> require(initial.isJsonPrimitive && initial.asJsonPrimitive.isBoolean &&
                    minimum == null && maximum == null && options.size() == 0) {
                    "runnerUi布尔字段无效"
                }
                "choice" -> {
                    require(options.all {
                        it.isJsonPrimitive && it.asJsonPrimitive.isString
                    }) { "runnerUi选项必须是字符串" }
                    val values = options.map { it.asString }
                    require(initial.isJsonPrimitive && initial.asJsonPrimitive.isString &&
                        values.size in 1..32 &&
                        values.distinct().size == values.size &&
                        values.all { it.codePointLength() in 1..64 && it.none(Char::isISOControl) } &&
                        initial.asString in values && minimum == null && maximum == null) {
                        "runnerUi选项字段无效"
                    }
                }
            }
        }
    }

    private fun validateJsonShape(manifest: JsonObject) {
        require(manifest.keySet().all(MANIFEST_KEYS::contains)) { "project.json 含未知字段" }
        manifest.getAsJsonObject("design")?.let { design ->
            require(design.keySet() == setOf("width", "height", "scaleMode", "orientationPolicy")) {
                "design 含未知字段"
            }
        }
        manifest.getAsJsonArray("flows")?.forEach { element ->
            val flow = element.asJsonObject
            val flowKeys = setOf("flowId", "path", "rootBlockId", "params", "returns")
            require(flow.keySet() == flowKeys) {
                "插件声明字段不完整或含未知字段"
            }
            flow.getAsJsonArray("params").forEach { parameter ->
                val value = parameter.asJsonObject
                require(value.keySet() == setOf("name", "type", "required")) {
                    "插件参数含未知字段"
                }
                require(PARAMETER_NAME.matches(value.get("name").asString)) { "非法插件参数名" }
                require(value.get("type").asString in VALUE_TYPES) { "非法插件参数类型" }
                require(value.get("required").isJsonPrimitive && value.get("required").asJsonPrimitive.isBoolean) {
                    "插件参数 required 必须是布尔值"
                }
            }
            if (!flow.get("returns").isJsonNull) {
                val returns = flow.getAsJsonObject("returns")
                require(returns.keySet() == setOf("type", "nullable")) {
                    "插件返回值含未知字段"
                }
                require(returns.get("type").asString in VALUE_TYPES) { "非法插件返回类型" }
                require(returns.get("nullable").isJsonPrimitive && returns.get("nullable").asJsonPrimitive.isBoolean) {
                    "插件返回 nullable 必须是布尔值"
                }
            }
        }
    }

    private fun luaManifest(projectId: String, name: String, design: ProjectDesign) = ProjectManifestDocument(
        projectId = projectId,
        name = name,
        sourceMode = ProjectSourceMode.LUA,
        entryPoint = LUA_ENTRY,
        luaFiles = listOf(LUA_ENTRY),
        luaDirectories = listOf(LUA_MODULE_ROOT),
        design = design,
    )

    private fun visualManifest(projectId: String, name: String, design: ProjectDesign): ProjectManifestDocument {
        val rootBlockId = "block-${UUID.randomUUID()}"
        return ProjectManifestDocument(
            projectId = projectId,
            name = name,
            sourceMode = ProjectSourceMode.VISUAL,
            design = design,
            entryFlowId = MAIN_FLOW,
            flows = listOf(
                ProjectFlow(
                    flowId = MAIN_FLOW,
                    path = MAIN_FLOW_PATH,
                    rootBlockId = rootBlockId,
                ),
            ),
        )
    }

    private fun initialVisualNode(rootBlockId: String): String {
        val nodeId = "node-${UUID.randomUUID()}"
        return """{"flowSchemaVersion":1,"nodeId":"$nodeId","blockId":"$rootBlockId","parentId":null,"orderKey":"a0","kind":"task.noop","nodeVersion":1,"depth":0,"args":{}}"""
    }

    private fun ensureRoot() {
        if (!rootDirectory.exists() && !rootDirectory.mkdirs()) throw ProjectStoreException("无法创建项目根目录")
        require(rootDirectory.isDirectory) { "项目根路径不是目录" }
    }

    private fun existingProjectDirectory(projectId: String): File {
        val directory = projectDirectory(validateProjectId(projectId))
        require(directory.isDirectory) { "项目不存在：$projectId" }
        return directory
    }

    private fun projectDirectory(projectId: String): File =
        File(rootDirectory, projectId).also(::checkContained)

    /**
     * 归一化并校验 Flow 相对路径。
     *
     * 参考新版易编精灵：源文件全部放在同一个固定目录，分组只是编辑器里的虚拟视图（见 [readSourceGroups]），
     * 所以路径固定为单层 `visual/flows/<名称>.jsonl`，固定目录本身不可被占用或改名。
     * 清单校验和 Flow 增删改走的是同一条规则，否则 [moveFlow] 写出的清单会过不了下一次 [openProject]。
     */
    private fun normalizeFlowPath(relativePath: String): String {
        val clean = relativePath.trim().trim('/')
        require(isValidFlowPath(clean)) { "非法插件路径：$relativePath" }
        return clean
    }

    /** 由显示文件名（可带或不带 `.jsonl`）得到规范路径。 */
    private fun flowPathForName(fileName: String): String {
        val name = fileName.trim().removeSuffix(FLOW_FILE_SUFFIX)
        require(isValidFlowName(name)) { "非法源文件名：$fileName" }
        return "$VISUAL_FLOW_ROOT/$name$FLOW_FILE_SUFFIX"
    }

    private fun isValidFlowPath(path: String): Boolean {
        val prefix = "$VISUAL_FLOW_ROOT/"
        if (!path.startsWith(prefix) || !path.endsWith(FLOW_FILE_SUFFIX)) return false
        return isValidFlowName(path.removePrefix(prefix).removeSuffix(FLOW_FILE_SUFFIX))
    }

    private fun normalizeLuaPath(relativePath: String): String {
        val clean = relativePath.trim().replace('\\', '/').trim('/')
        require(isValidLuaPath(clean)) { "非法 Lua 文件路径：$relativePath" }
        return clean
    }

    private fun normalizeLuaDirectory(relativePath: String): String {
        val clean = relativePath.trim().replace('\\', '/').trim('/')
        require(clean.startsWith("$LUA_MODULE_ROOT/") && clean.length > LUA_MODULE_ROOT.length + 1) {
            "Lua 文件夹必须在 $LUA_MODULE_ROOT/ 内"
        }
        require(isValidLuaDirectory(clean)) {
            "非法 Lua 文件夹路径：$relativePath"
        }
        return clean
    }

    private fun isValidLuaDirectory(path: String): Boolean {
        if (path == LUA_MODULE_ROOT) return true
        if (!path.startsWith("$LUA_MODULE_ROOT/")) return false
        val segments = path.split('/')
        return segments.size in 2..MAX_LUA_PATH_DEPTH && segments.all(::isValidLuaPathSegment)
    }

    private fun luaParentDirectories(path: String): List<String> {
        if (path == LUA_ENTRY) return emptyList()
        val segments = path.split('/')
        return (1 until segments.size).map { segments.take(it).joinToString("/") }
    }

    private fun luaDirectoryChain(path: String): List<String> =
        luaParentDirectories(path) + path

    private fun isValidLuaPath(path: String): Boolean {
        if (path == LUA_ENTRY) return true
        if (!path.startsWith("$LUA_MODULE_ROOT/") || !path.endsWith(LUA_FILE_SUFFIX) || path.length > MAX_LUA_PATH_LENGTH) return false
        val segments = path.split('/')
        return segments.size in 2..MAX_LUA_PATH_DEPTH &&
            segments.dropLast(1).all(::isValidLuaPathSegment) &&
            isValidLuaFileStem(segments.last().removeSuffix(LUA_FILE_SUFFIX))
    }

    private fun isValidLuaPathSegment(value: String): Boolean =
        value.isNotEmpty() && value.length <= MAX_LUA_FILE_NAME_CHARS &&
            value.all { it.isLetterOrDigit() || it == '_' || it == '-' || it in '一'..'鿿' }

    private fun isValidLuaFileStem(value: String): Boolean = isValidLuaPathSegment(value)

    /**
     * Flow 文件名与分组名共用的字符策略，必须与 `flow-ir` 的 `valid_flow_name` 和 `project.schema.json` 一致：
     * 允许中文（参考产品的文件名本来就是中文），字符集限定为字母、数字、`_`、`-`、CJK 统一表意文字，
     * 以及不在首尾的 `.` 和空格；于是 `.`、`..`、隐藏名、路径分隔符和 Windows 保留字符都构造不出来。
     * [checkContained] 仍是最后一道规范化路径兜底。
     */
    private fun isValidFlowName(name: String): Boolean =
        name.isNotEmpty() &&
            name.codePointLength() <= MAX_FLOW_NAME_CHARS &&
            isFlowNameEdgeChar(name.first()) &&
            isFlowNameEdgeChar(name.last()) &&
            name.all { isFlowNameEdgeChar(it) || it == '.' || it == ' ' }

    private fun isFlowNameEdgeChar(value: Char): Boolean =
        value in 'A'..'Z' || value in 'a'..'z' || value in '0'..'9' ||
            value == '_' || value == '-' || value in '一'..'鿿'

    private fun loadSourceGroups(directory: File, validFlowIds: Set<String>): List<SourceGroup> {
        val file = File(directory, SOURCE_GROUPS)
        if (!file.isFile || file.length() !in 1..MAX_ORGANIZATION_BYTES) return emptyList()
        val document = runCatching {
            JsonParser.parseString(file.readText(Charsets.UTF_8)).asJsonObject
        }.getOrNull() ?: return emptyList()
        if (document.get("version")?.toString() != SOURCE_GROUPS_VERSION.toString()) return emptyList()
        val groups = document.get("groups")?.takeIf(JsonElement::isJsonArray)?.asJsonArray ?: return emptyList()
        val seenNames = mutableSetOf<String>()
        val seenFlows = mutableSetOf<String>()
        return groups.take(MAX_SOURCE_GROUPS).mapNotNull { element ->
            val group = element.takeIf(JsonElement::isJsonObject)?.asJsonObject ?: return@mapNotNull null
            val name = group.get("name")?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString?.trim() ?: return@mapNotNull null
            if (!isValidFlowName(name) || !seenNames.add(name)) return@mapNotNull null
            val flowIds = (group.get("flowIds")?.takeIf(JsonElement::isJsonArray)?.asJsonArray ?: JsonArray())
                .take(MAX_PROJECT_FLOWS).mapNotNull { it.takeIf { item -> item.isJsonPrimitive && item.asJsonPrimitive.isString }?.asString }
                .filter { it in validFlowIds && seenFlows.add(it) }
            SourceGroup(name, flowIds)
        }.take(MAX_SOURCE_GROUPS)
    }

    private fun serializeSourceGroups(groups: List<SourceGroup>): ByteArray {
        val document = JsonObject().apply {
            addProperty("version", SOURCE_GROUPS_VERSION)
            add(
                "groups",
                JsonArray().apply {
                    groups.forEach { group ->
                        add(
                            JsonObject().apply {
                                addProperty("name", group.name)
                                add("flowIds", JsonArray().apply { group.flowIds.forEach(::add) })
                            },
                        )
                    }
                },
            )
        }
        return json.toJson(document).toByteArray(Charsets.UTF_8)
    }

    /** Flow 删除后把它从分组里剔除；分组是派生状态，这里失败不阻断主操作。 */
    private fun pruneSourceGroups(directory: File, validFlowIds: Set<String>) {
        val file = File(directory, SOURCE_GROUPS)
        if (!file.isFile) return
        val pruned = loadSourceGroups(directory, validFlowIds)
        runCatching { writeAtomic(file, serializeSourceGroups(pruned), backup = null) }
    }

    private fun checkContained(file: File) {
        val root = rootDirectory.canonicalFile
        val candidate = file.canonicalFile
        require(candidate == root || isDescendant(root, candidate)) {
            "项目路径越界"
        }
    }

    private fun validateProjectId(value: String): String {
        require(PROJECT_ID.matches(value)) { "非法项目 ID" }
        return value
    }

    private fun validateName(value: String): String {
        val clean = value.trim()
        require(clean.isNotEmpty()) { "项目名不能为空" }
        require(clean.length <= 128) { "项目名不能超过 128 个字符" }
        require(clean.none(Char::isISOControl)) { "项目名不能包含控制字符" }
        return clean
    }

    private fun readState(directory: File): ProjectState {
        val file = File(directory, STATE)
        if (!file.isFile) return ProjectState()
        return runCatching {
            val properties = Properties().apply { file.inputStream().use(::load) }
            ProjectState(
                createdAt = properties.getProperty("createdAt")?.toLongOrNull(),
                updatedAt = properties.getProperty("updatedAt")?.toLongOrNull(),
                lastOpenedAt = properties.getProperty("lastOpenedAt")?.toLongOrNull(),
            )
        }.getOrDefault(ProjectState())
    }

    private fun writeState(directory: File, state: ProjectState) {
        val text = buildString {
            state.createdAt?.let { append("createdAt=$it\n") }
            state.updatedAt?.let { append("updatedAt=$it\n") }
            state.lastOpenedAt?.let { append("lastOpenedAt=$it\n") }
        }
        writeAtomic(File(directory, STATE), text.toByteArray(Charsets.UTF_8), backup = null)
    }

    private data class LoadedManifest(
        val manifest: ProjectManifestDocument,
        val originalBytes: ByteArray,
        val wasLegacy: Boolean,
    )

    private data class ProjectState(
        val createdAt: Long? = null,
        val updatedAt: Long? = null,
        val lastOpenedAt: Long? = null,
    )

    private companion object {
        val PROJECT_ID = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,63}")
        val FLOW_ID = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,63}")
        val RUNTIME_API = Regex("[0-9]+\\.[0-9]+")
        val CAPABILITY = Regex("[a-z][a-z0-9]*(\\.[a-z][a-z0-9]*)+")
        val PARAMETER_NAME = Regex("[A-Za-z_][A-Za-z0-9_]*")
        val VALUE_TYPES = setOf("boolean", "integer", "number", "string")
        val MANIFEST_KEYS = setOf(
            "formatVersion", "flowSchemaVersion", "runtimeApi", "projectId", "name", "sourceMode",
            "entryPoint", "luaFiles", "luaDirectories", "entryFlowId", "flows", "variables", "resources", "capabilities", "design", "debugSettings", "ownerId",
            "cloudId", "syncState", "signature", "licensePolicy", "runnerUi", "randomization",
        )
        val OPTIONAL_MANIFEST_KEYS = setOf(
            "entryPoint", "luaFiles", "luaDirectories", "entryFlowId", "ownerId", "cloudId", "syncState", "signature", "licensePolicy",
            "runnerUi",
        )
        const val MANIFEST = "project.json"
        const val MANIFEST_BACKUP = ".studio/project.json.bak"
        const val MIGRATION_BACKUP = ".studio/migration/project.v1.json"
        const val STATE = ".studio/state.properties"
        const val LUA_ENTRY = "main.lua"
        const val LUA_BACKUP = ".studio/main.lua.bak"
        const val LUA_MODULE_ROOT = "lua"
        const val LUA_FILE_SUFFIX = ".lua"
        const val MAX_LUA_FILES = 128
        const val MAX_LUA_DIRECTORIES = 128
        const val MAX_LUA_PATH_DEPTH = 6
        const val MAX_LUA_PATH_LENGTH = 256
        const val MAX_LUA_FILE_NAME_CHARS = 64
        const val DEFAULT_LUA_MODULE = "return {}\n"
        const val FLOW_BACKUP_ROOT = ".studio/flows"
        const val VISUAL_FLOW_ROOT = "visual/flows"
        const val FLOW_FILE_SUFFIX = ".jsonl"

        /** Flow 文件名/分组名最大字符数（按码点计），与 `flow-ir` 和 Schema 一致。 */
        const val MAX_FLOW_NAME_CHARS = 64
        const val SOURCE_GROUPS = ".studio/source-groups.json"
        const val SOURCE_GROUPS_VERSION = 1
        const val MAX_SOURCE_GROUPS = 64
        const val IMAGE_DIRECTORIES = ".studio/image-directories.json"
        const val MAX_IMAGE_DIRECTORIES = 256
        const val MAX_ORGANIZATION_BYTES = 64L * 1024
        const val BACKUP_ROOT = ".backups"
        const val BACKUP_SLOTS = 3
        const val MAX_BACKUP_REMARK_CHARS = 64
        const val GENERATION_RECORD = "generated/generation.json"
        const val STALE_GENERATION_RECORD = "generated/generation.stale.json"
        const val MAIN_FLOW = "main"
        const val MAIN_FLOW_PATH = "visual/flows/main.jsonl"
        const val MAX_PROJECT_FLOWS = 256
        const val MAX_PROJECT_RESOURCES = 256
        const val MAX_PROJECT_CAPABILITIES = 64
        const val MAX_CAPABILITY_LENGTH = 128
        val RUNNER_UI_KINDS = setOf("text", "integer", "boolean", "choice")
        const val MAX_PROJECT_BACKUP_ENTRIES = 516
        const val MAX_IMPORT_ID_ATTEMPTS = 32
        const val BACKUP_COMPRESSION_LEVEL = 6
        const val ZIP_EPOCH_MILLIS = 315_532_800_000L
        const val MAX_PROJECT_MANIFEST_BYTES = 1024L * 1024
        const val MAX_PROJECT_BACKUP_BYTES = 512L * 1024 * 1024
        const val DEFAULT_LUA = """return function()
    Task.sleep(1)
end
"""
    }
}

private fun String.codePointLength(): Int = codePointCount(0, length)
