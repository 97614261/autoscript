package com.autoscript.project.store

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.util.Locale
import java.util.Properties
import java.util.UUID
import com.google.gson.Gson
import com.google.gson.GsonBuilder
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
                    ProjectSummary(
                        projectId = decoded.projectId,
                        name = decoded.name,
                        sourceMode = decoded.sourceMode,
                        updatedAt = state.updatedAt ?: directory.lastModified(),
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
    fun createProject(name: String, mode: ProjectSourceMode): ProjectSnapshot {
        ensureRoot()
        val cleanName = validateName(name)
        val projectId = validateProjectId(idFactory())
        val destination = projectDirectory(projectId)
        check(!destination.exists()) { "项目 ID 已存在：$projectId" }

        val staging = File(rootDirectory, ".$projectId.creating")
        checkContained(staging)
        if (staging.exists() && !staging.deleteRecursively()) {
            throw ProjectStoreException("无法清理未完成的项目目录")
        }
        if (!staging.mkdirs()) throw ProjectStoreException("无法创建项目目录")

        try {
            val manifest = when (mode) {
                ProjectSourceMode.LUA -> luaManifest(projectId, cleanName)
                ProjectSourceMode.VISUAL -> visualManifest(projectId, cleanName)
            }
            writeManifest(staging, manifest, keepBackup = false)
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
            writeState(staging, ProjectState(updatedAt = clock()))
            if (!staging.renameTo(destination)) throw ProjectStoreException("无法提交新项目目录")
            return openProject(projectId)
        } catch (error: Throwable) {
            staging.deleteRecursively()
            throw if (error is ProjectStoreException) error
            else ProjectStoreException("创建项目失败", error)
        }
    }

    @Synchronized
    fun openProject(projectId: String): ProjectSnapshot {
        val directory = existingProjectDirectory(projectId)
        val loaded = readManifest(directory, recover = true)
        var manifest = loaded.manifest
        if (loaded.wasLegacy) {
            val migrationBackup = File(directory, MIGRATION_BACKUP)
            if (!migrationBackup.exists()) {
                writeAtomic(migrationBackup, loaded.originalBytes, backup = null)
            }
            writeManifest(directory, manifest, keepBackup = true)
        }
        validateManifest(manifest)
        require(manifest.projectId == projectId) { "项目目录与 projectId 不一致" }
        ensureDeclaredFiles(directory, manifest)

        val state = readState(directory)
        writeState(
            directory,
            state.copy(updatedAt = state.updatedAt ?: clock(), lastOpenedAt = clock()),
        )
        return ProjectSnapshot(
            directory = directory,
            manifest = manifest,
            luaSource = manifest.entryPoint?.let { readTextWithRecovery(directory, it, LUA_BACKUP) },
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
            }
            writeBoundedAndSync(staging, source, maximumBytes)
            val extension = validateImportedResource(kind, staging)
            val fileName = uniqueResourceFileName(
                current,
                kind,
                sanitizeResourceStem(sourceName),
                extension,
            )
            val path = when (kind) {
                ProjectResourceKind.IMAGE -> "assets/images/$fileName"
                ProjectResourceKind.GLYPH_DICTIONARY -> "dictionaries/$fileName"
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
    fun updateCapabilities(
        projectId: String,
        capabilities: Set<String>,
        expectedCapabilities: Set<String>,
    ): ProjectSnapshot {
        val current = openProject(projectId)
        if (current.manifest.capabilities.toSet() != expectedCapabilities) {
            throw ProjectManifestConflictException()
        }
        require(capabilities.all(CAPABILITY::matches)) { "非法能力声明" }
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
    fun saveLua(
        projectId: String,
        source: String,
        expectedSource: String? = null,
    ): ProjectSnapshot {
        val current = openProject(projectId)
        val directory = current.directory
        val manifest = current.manifest
        require(manifest.sourceMode == ProjectSourceMode.LUA) { "只有 Lua 项目可以保存 main.lua" }
        if (expectedSource != null && current.luaSource != expectedSource) {
            throw ProjectWriteConflictException()
        }
        val sourceBytes = source.toByteArray(Charsets.UTF_8)
        require(sourceBytes.size <= MAX_LUA_SOURCE_BYTES) { "Lua 源码不能超过 16 MiB" }
        writeAtomic(
            File(directory, LUA_ENTRY),
            sourceBytes,
            File(directory, LUA_BACKUP),
        )
        val state = readState(directory)
        writeState(directory, state.copy(updatedAt = clock()))
        return openProject(projectId)
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
            "只有可视化项目可以保存 Flow"
        }
        val declaration = manifest.flows.singleOrNull { it.flowId == flowId }
            ?: throw IllegalArgumentException("Flow 不在项目清单中：$flowId")
        if (current.flowSources[flowId] != expectedSource) {
            throw FlowWriteConflictException(flowId)
        }
        val sourceBytes = source.toByteArray(Charsets.UTF_8)
        require(sourceBytes.size <= MAX_FLOW_SOURCE_BYTES) { "Flow 源码不能超过 64 MiB" }
        val target = File(directory, declaration.path)
        checkContained(target)

        // 先让生成代次失效。即使随后写 Flow 失败，也绝不能运行旧生成物。
        invalidateGeneration(directory)
        writeAtomic(target, sourceBytes, File(directory, "$FLOW_BACKUP_ROOT/$flowId.jsonl.bak"))
        val state = readState(directory)
        writeState(directory, state.copy(updatedAt = clock()))
        return openProject(projectId)
    }

    @Synchronized
    fun createFlow(
        projectId: String,
        flowId: String,
        expectedFlowIds: Set<String>,
    ): ProjectSnapshot {
        val current = openProject(projectId)
        require(current.manifest.sourceMode == ProjectSourceMode.VISUAL) {
            "只有可视化项目可以新建 Flow"
        }
        require(current.manifest.flows.map(ProjectFlow::flowId).toSet() == expectedFlowIds) {
            throw ProjectManifestConflictException()
        }
        require(FLOW_ID.matches(flowId)) { "非法 Flow ID：$flowId" }
        require(current.manifest.flows.none { it.flowId == flowId }) { "Flow 已存在：$flowId" }
        require(current.manifest.flows.size < MAX_PROJECT_FLOWS) { "Flow 数量不能超过 256" }
        val declaration = ProjectFlow(
            flowId = flowId,
            path = "visual/flows/$flowId.jsonl",
            rootBlockId = "block-${UUID.randomUUID()}",
        )
        val flowFile = File(current.directory, declaration.path)
        checkContained(flowFile)
        require(!flowFile.exists()) { "Flow 文件已存在：${declaration.path}" }
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
            "只有可视化项目可以删除 Flow"
        }
        require(current.manifest.flows.map(ProjectFlow::flowId).toSet() == expectedFlowIds) {
            throw ProjectManifestConflictException()
        }
        require(flowId != current.manifest.entryFlowId) { "不能删除入口 Flow" }
        val declaration = current.manifest.flows.singleOrNull { it.flowId == flowId }
            ?: throw IllegalArgumentException("Flow 不存在：$flowId")
        require(!isFlowReferenced(current.flowSources, flowId)) { "Flow $flowId 仍被 flow.call 引用" }

        invalidateGeneration(current.directory)
        writeManifest(
            current.directory,
            current.manifest.copy(flows = current.manifest.flows - declaration),
            keepBackup = true,
        )
        val flowFile = File(current.directory, declaration.path)
        checkContained(flowFile)
        if (flowFile.exists() && !flowFile.delete()) {
            throw ProjectStoreException("Flow 已从清单移除，但旧文件清理失败：$flowId")
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

    private fun readManifest(directory: File, recover: Boolean): LoadedManifest {
        val target = File(directory, MANIFEST)
        val temp = File(directory, "$MANIFEST.tmp")
        val backup = File(directory, MANIFEST_BACKUP)
        val previous = File(directory, "$MANIFEST.previous")

        decodeManifest(target)?.let { loaded ->
            if (temp.exists()) temp.delete()
            if (previous.exists()) previous.delete()
            return loaded
        }
        val candidate = decodeManifest(temp) ?: decodeManifest(previous) ?: decodeManifest(backup)
            ?: throw ProjectStoreException("项目清单损坏且没有可恢复副本：${directory.name}")
        if (recover) writeAtomic(target, candidate.originalBytes, backup = null)
        return candidate
    }

    private fun decodeManifest(file: File): LoadedManifest? {
        if (!file.isFile) return null
        return try {
            val bytes = file.readBytes()
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
            if (!normalized.has("flows")) normalized.add("flows", com.google.gson.JsonArray())
            if (!normalized.has("resources")) normalized.add("resources", com.google.gson.JsonArray())
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
    ): String {
        val prefix = when (kind) {
            ProjectResourceKind.IMAGE -> "assets/images/"
            ProjectResourceKind.GLYPH_DICTIONARY -> "dictionaries/"
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

    private fun resourceManifestKind(kind: ProjectResourceKind): String = when (kind) {
        ProjectResourceKind.IMAGE -> "image"
        ProjectResourceKind.GLYPH_DICTIONARY -> "glyphDictionary"
    }

    private fun isResourceReferenced(snapshot: ProjectSnapshot, path: String): Boolean =
        when (snapshot.manifest.sourceMode) {
            ProjectSourceMode.LUA -> snapshot.luaSource?.contains(path) == true
            ProjectSourceMode.VISUAL -> snapshot.flowSources.any { (flowId, source) ->
                source.lineSequence().filter(String::isNotBlank).any { line ->
                    val node = runCatching { JsonParser.parseString(line).asJsonObject }
                        .getOrElse { throw ProjectStoreException("Flow $flowId 损坏，不能安全删除资源") }
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

    private fun isFlowReferenced(sources: Map<String, String>, targetFlowId: String): Boolean =
        sources.any { (sourceFlowId, source) ->
            if (sourceFlowId == targetFlowId) return@any false
            source.lineSequence().filter(String::isNotBlank).any { line ->
                val node = runCatching { JsonParser.parseString(line).asJsonObject }
                    .getOrElse { throw ProjectStoreException("Flow $sourceFlowId 损坏，不能安全删除") }
                node.get("kind")?.asString == "flow.call" &&
                    node.getAsJsonObject("args")?.get("targetFlowId")?.asString == targetFlowId
            }
        }

    private fun copyAndSync(source: File, target: File) {
        target.parentFile?.let { if (!it.exists() && !it.mkdirs()) throw IOException("mkdir $it") }
        writeAndSync(target, source.readBytes())
    }

    private fun ensureDeclaredFiles(directory: File, manifest: ProjectManifestDocument) {
        when (manifest.sourceMode) {
            ProjectSourceMode.LUA -> Unit
            ProjectSourceMode.VISUAL -> manifest.flows.forEach { flow ->
                val file = File(directory, flow.path)
                checkContained(file)
                require(file.isFile) { "缺少 Flow 文件：${flow.path}" }
            }
        }
        manifest.resources.forEach { resource ->
            val path = resource.get("path").asString
            val file = File(directory, path)
            checkContained(file)
            require(file.isFile) { "缺少资源文件：$path" }
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

    private fun decodeLuaSource(bytes: ByteArray): String {
        require(bytes.size <= MAX_LUA_SOURCE_BYTES) { "Lua 源码不能超过 16 MiB" }
        return bytes.toString(Charsets.UTF_8)
    }

    private fun validateManifest(manifest: ProjectManifestDocument) {
        require(manifest.formatVersion == CURRENT_PROJECT_FORMAT_VERSION) { "不支持的项目版本" }
        require(manifest.flowSchemaVersion == 1) { "不支持的 Flow 版本" }
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
        require(manifest.capabilities.all(CAPABILITY::matches)) { "非法能力声明" }
        validateResources(manifest.resources)
        when (manifest.sourceMode) {
            ProjectSourceMode.LUA -> {
                require(manifest.entryPoint == LUA_ENTRY) { "Lua 项目入口必须是 main.lua" }
                require(manifest.entryFlowId == null && manifest.flows.isEmpty()) { "Lua 项目不能声明 Flow 入口" }
            }
            ProjectSourceMode.VISUAL -> {
                require(manifest.entryPoint == null) { "可视化项目不能声明 Lua 入口" }
                val entry = requireNotNull(manifest.entryFlowId) { "可视化项目缺少入口 Flow" }
                require(manifest.flows.isNotEmpty() && manifest.flows.any { it.flowId == entry }) {
                    "入口 Flow 不存在"
                }
                require(manifest.flows.map(ProjectFlow::flowId).distinct().size == manifest.flows.size) {
                    "Flow ID 重复"
                }
                require(manifest.flows.map(ProjectFlow::path).distinct().size == manifest.flows.size) {
                    "Flow 路径重复"
                }
                require(manifest.flows.map(ProjectFlow::rootBlockId).distinct().size == manifest.flows.size) {
                    "根积木 ID 重复"
                }
                manifest.flows.forEach { flow ->
                    require(FLOW_ID.matches(flow.flowId)) { "非法 Flow ID：${flow.flowId}" }
                    require(flow.rootBlockId.isNotEmpty() && flow.rootBlockId.length <= 128) { "非法根积木 ID" }
                    require(flow.path.startsWith("visual/flows/") && flow.path.endsWith(".jsonl")) {
                        "非法 Flow 路径：${flow.path}"
                    }
                    val fileName = flow.path.removePrefix("visual/flows/")
                    require(!fileName.contains('/') && !fileName.contains('\\') && FLOW_FILE.matches(fileName)) {
                        "Flow 路径越界"
                    }
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
                    (kind == "glyphDictionary" && path.startsWith("dictionaries/") && path.endsWith(".asglyph")),
            ) { "资源类型与路径不匹配" }
            require(paths.add(path)) { "资源路径重复" }
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
                "Flow 声明字段不完整或含未知字段"
            }
            flow.getAsJsonArray("params").forEach { parameter ->
                val value = parameter.asJsonObject
                require(value.keySet() == setOf("name", "type", "required")) {
                    "Flow 参数含未知字段"
                }
                require(PARAMETER_NAME.matches(value.get("name").asString)) { "非法 Flow 参数名" }
                require(value.get("type").asString in VALUE_TYPES) { "非法 Flow 参数类型" }
                require(value.get("required").isJsonPrimitive && value.get("required").asJsonPrimitive.isBoolean) {
                    "Flow 参数 required 必须是布尔值"
                }
            }
            if (!flow.get("returns").isJsonNull) {
                val returns = flow.getAsJsonObject("returns")
                require(returns.keySet() == setOf("type", "nullable")) {
                    "Flow 返回值含未知字段"
                }
                require(returns.get("type").asString in VALUE_TYPES) { "非法 Flow 返回类型" }
                require(returns.get("nullable").isJsonPrimitive && returns.get("nullable").asJsonPrimitive.isBoolean) {
                    "Flow 返回 nullable 必须是布尔值"
                }
            }
        }
    }

    private fun luaManifest(projectId: String, name: String) = ProjectManifestDocument(
        projectId = projectId,
        name = name,
        sourceMode = ProjectSourceMode.LUA,
        entryPoint = LUA_ENTRY,
    )

    private fun visualManifest(projectId: String, name: String): ProjectManifestDocument {
        val rootBlockId = "block-${UUID.randomUUID()}"
        return ProjectManifestDocument(
            projectId = projectId,
            name = name,
            sourceMode = ProjectSourceMode.VISUAL,
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

    private fun checkContained(file: File) {
        val root = rootDirectory.canonicalFile
        val candidate = file.canonicalFile
        require(candidate.parentFile == root || candidate.toPath().startsWith(root.toPath())) {
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
                updatedAt = properties.getProperty("updatedAt")?.toLongOrNull(),
                lastOpenedAt = properties.getProperty("lastOpenedAt")?.toLongOrNull(),
            )
        }.getOrDefault(ProjectState())
    }

    private fun writeState(directory: File, state: ProjectState) {
        val text = buildString {
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
        val updatedAt: Long? = null,
        val lastOpenedAt: Long? = null,
    )

    private companion object {
        val PROJECT_ID = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,63}")
        val FLOW_ID = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,63}")
        val FLOW_FILE = Regex("[0-9A-Za-z._-]+\\.jsonl")
        val RUNTIME_API = Regex("[0-9]+\\.[0-9]+")
        val CAPABILITY = Regex("[a-z][a-z0-9]*(\\.[a-z][a-z0-9]*)+")
        val PARAMETER_NAME = Regex("[A-Za-z_][A-Za-z0-9_]*")
        val VALUE_TYPES = setOf("boolean", "integer", "number", "string")
        val MANIFEST_KEYS = setOf(
            "formatVersion", "flowSchemaVersion", "runtimeApi", "projectId", "name", "sourceMode",
            "entryPoint", "entryFlowId", "flows", "resources", "capabilities", "design", "ownerId",
            "cloudId", "syncState", "signature", "licensePolicy",
        )
        val OPTIONAL_MANIFEST_KEYS = setOf(
            "entryPoint", "entryFlowId", "ownerId", "cloudId", "syncState", "signature", "licensePolicy",
        )
        const val MANIFEST = "project.json"
        const val MANIFEST_BACKUP = ".studio/project.json.bak"
        const val MIGRATION_BACKUP = ".studio/migration/project.v1.json"
        const val STATE = ".studio/state.properties"
        const val LUA_ENTRY = "main.lua"
        const val LUA_BACKUP = ".studio/main.lua.bak"
        const val FLOW_BACKUP_ROOT = ".studio/flows"
        const val GENERATION_RECORD = "generated/generation.json"
        const val STALE_GENERATION_RECORD = "generated/generation.stale.json"
        const val MAIN_FLOW = "main"
        const val MAIN_FLOW_PATH = "visual/flows/main.jsonl"
        const val MAX_PROJECT_FLOWS = 256
        const val MAX_PROJECT_RESOURCES = 256
        const val DEFAULT_LUA = """return function()
    Task.sleep(1)
end
"""
    }
}
