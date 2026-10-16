package com.autoscript.studio

import com.autoscript.project.store.ProjectSnapshot
import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest

internal data class EditorDraftEntry(val flowId: String, val rootBlockId: String, val baseHash: String, val source: String)
internal data class EditorDraftCheckpoint(val manifestHash: String, val entries: List<EditorDraftEntry>)

/** Private recovery data only. Project source files are never opened for writing here. */
internal class EditorDraftJournal(private val files: File) {
    private val gson = Gson()
    private val owner = synchronized(diskLock) { ++ownerSequence }
    fun nextRevision(): Long = synchronized(diskLock) {
        if (ownerSequence == owner) ++revision else -1L
    }
    fun writeIfCurrent(projectId: String, checkpoint: EditorDraftCheckpoint, ticket: Long) = synchronized(diskLock) {
        if (ownerSequence == owner && ticket >= 0 && revision == ticket) write(projectId, checkpoint)
    }
    fun manifestHash(snapshot: ProjectSnapshot) = hash(gson.toJson(snapshot.manifest))

    fun capture(snapshot: ProjectSnapshot, editors: Map<String, VisualEditorState>): EditorDraftCheckpoint {
        val entries = snapshot.manifest.flows.mapNotNull { flow ->
            editors[flow.flowId]?.takeIf { it.isDirty && !it.isReadOnly }?.let {
                EditorDraftEntry(flow.flowId, flow.rootBlockId, hash(it.savedSource), it.currentSource)
            }
        }
        require(entries.size <= MAX_ENTRIES) { "恢复草稿超过16个插件，请先保存" }
        entries.forEach { require(it.source.toByteArray(Charsets.UTF_8).size <= MAX_SOURCE_BYTES) { "插件恢复草稿超过1 MiB，请先保存" } }
        return EditorDraftCheckpoint(manifestHash(snapshot), entries)
    }

    fun compatible(checkpoint: EditorDraftCheckpoint, snapshot: ProjectSnapshot): List<EditorDraftEntry> {
        if (checkpoint.manifestHash != manifestHash(snapshot)) return emptyList()
        return checkpoint.entries.filter { entry ->
            snapshot.manifest.flows.any { it.flowId == entry.flowId && it.rootBlockId == entry.rootBlockId } &&
                snapshot.flowSources[entry.flowId]?.let(::hash) == entry.baseHash
        }
    }

    fun read(projectId: String): EditorDraftCheckpoint? = synchronized(diskLock) {
        val target = target(projectId)
        val previous = checked(requireNotNull(target.parentFile), target.name + ".previous")
        val file = if (target.exists()) target else previous.takeIf { it.exists() } ?: return@synchronized null
        require(file.isFile && file.length() in 1..MAX_FILE_BYTES.toLong()) { "恢复文件无效或超限" }
        val bytes = file.inputStream().use { input ->
            val output = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                require(output.size() + count <= MAX_FILE_BYTES)
                output.write(buffer, 0, count)
            }
            output.toByteArray()
        }
        val text = Charsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(bytes)).toString()
        val json = JsonParser.parseString(text).asJsonObject
        require(json["version"].asInt == 1 && json["projectId"].asString == projectId)
        val manifestHash = json["manifestHash"].asString.also { require(HASH.matches(it)) }
        val values = json.getAsJsonArray("entries").also { require(it.size() <= MAX_ENTRIES) }
        val entries = values.map { value ->
            val entry = value.asJsonObject
            EditorDraftEntry(entry["flowId"].asString, entry["rootBlockId"].asString,
                entry["baseHash"].asString, entry["source"].asString).also {
                require(it.flowId.length in 1..256 && it.rootBlockId.length in 1..256 && HASH.matches(it.baseHash))
                require(it.source.toByteArray(Charsets.UTF_8).size <= MAX_SOURCE_BYTES)
            }
        }
        require(entries.map { it.flowId }.distinct().size == entries.size)
        EditorDraftCheckpoint(manifestHash, entries)
    }

    fun write(projectId: String, checkpoint: EditorDraftCheckpoint) = synchronized(diskLock) {
        require(checkpoint.entries.size <= MAX_ENTRIES && HASH.matches(checkpoint.manifestHash))
        require(checkpoint.entries.map { it.flowId }.distinct().size == checkpoint.entries.size)
        if (checkpoint.entries.isEmpty()) { discard(projectId); return@synchronized }
        val entries = JsonArray()
        checkpoint.entries.forEach { entry ->
            require(entry.flowId.length in 1..256 && entry.rootBlockId.length in 1..256 && HASH.matches(entry.baseHash))
            require(entry.source.toByteArray(Charsets.UTF_8).size <= MAX_SOURCE_BYTES)
            entries.add(JsonObject().apply {
                addProperty("flowId", entry.flowId); addProperty("rootBlockId", entry.rootBlockId)
                addProperty("baseHash", entry.baseHash); addProperty("source", entry.source)
            })
        }
        val bytes = gson.toJson(JsonObject().apply {
            addProperty("version", 1); addProperty("projectId", projectId)
            addProperty("manifestHash", checkpoint.manifestHash); add("entries", entries)
        }).toByteArray(Charsets.UTF_8)
        require(bytes.size <= MAX_FILE_BYTES)
        val target = target(projectId)
        val parent = requireNotNull(target.parentFile)
        check(parent.mkdirs() || parent.isDirectory)
        val temporary = checked(parent, target.name + ".tmp")
        val previous = checked(parent, target.name + ".previous")
        FileOutputStream(temporary).use { it.write(bytes); it.fd.sync() }
        if (target.exists()) {
            check(!previous.exists() || previous.delete())
            check(target.renameTo(previous))
        }
        if (!temporary.renameTo(target)) {
            if (previous.exists()) previous.renameTo(target)
            error("恢复草稿提交失败")
        }
        check(!previous.exists() || previous.delete())
    }

    fun discard(projectId: String) = synchronized(diskLock) {
        val target = target(projectId)
        listOf(target.name, target.name + ".tmp", target.name + ".previous").forEach {
            val file = checked(requireNotNull(target.parentFile), it)
            check(!file.exists() || (file.isFile && file.delete())) { "恢复草稿清理失败" }
        }
    }

    private fun target(projectId: String): File {
        require(projectId.isNotBlank() && projectId.length <= 256)
        val root = checked(files.canonicalFile, "editor-drafts")
        return checked(root, hash(projectId) + ".json")
    }
    private fun checked(parent: File, name: String): File = File(parent, name).also {
        require(it.canonicalFile == it.absoluteFile && it.canonicalFile.parentFile == parent.canonicalFile)
    }
    companion object {
        private val diskLock = Any()
        // Only one Studio project window is allowed. Tickets must survive journal replacement:
        // an old disposed session must not overwrite a newly opened session's checkpoint.
        private var ownerSequence = 0L
        private var revision = 0L
        private val HASH = Regex("[0-9a-f]{64}")
        private const val MAX_ENTRIES = 16
        private const val MAX_SOURCE_BYTES = 1_048_576
        private const val MAX_FILE_BYTES = 20 * 1_048_576
        internal fun hash(value: String): String = MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { (it.toInt() and 255).toString(16).padStart(2, '0') }
    }
}
