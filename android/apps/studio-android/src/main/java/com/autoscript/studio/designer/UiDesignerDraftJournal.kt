package com.autoscript.studio

import com.google.gson.Gson
import com.google.gson.JsonParser
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest

internal data class UiDesignerCheckpoint(val version: Int, val projectId: String, val baseHash: String, val draft: RunnerUiDesignerDraft)

internal class UiDesignerDraftJournal(files: File, private val projectId: String) {
    // Android may expose its trusted private root through /data/user/0 or another system alias.
    // Canonicalize only that root; the fixed child directory and every file still reject redirects.
    private val files = files.canonicalFile
    private val gson = Gson()
    private val owner = synchronized(lock) { ++ownerSequence }
    fun ticket() = synchronized(lock) { if (owner == ownerSequence) ++revision else -1L }
    fun base(value: Any) = digest(gson.toJson(value))
    fun read(): UiDesignerCheckpoint? = synchronized(lock) {
        val target = target()
        val file = if (target.exists()) target else checked(File(target.path + ".previous")).takeIf { it.exists() } ?: return@synchronized null
        require(file.isFile && file.length() in 1..MAX_BYTES.toLong())
        val bytes = file.inputStream().use { it.readNBytesCompat(MAX_BYTES) }
        val text = Charsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(bytes)).toString()
        val json = JsonParser.parseString(text).asJsonObject
        require(json["version"].asInt == 1 && json["projectId"].asString == projectId)
        val record = gson.fromJson(json, UiDesignerCheckpoint::class.java)
        require(record.baseHash.matches(Regex("[0-9a-f]{64}")))
        checkDraft(record.draft)
        record
    }
    fun write(baseHash: String, draft: RunnerUiDesignerDraft?, ticket: Long) = synchronized(lock) {
        if (owner != ownerSequence || ticket < 0 || ticket != revision) return@synchronized
        if (draft == null) { discardLocked(); return@synchronized }
        checkDraft(draft)
        require(baseHash.matches(Regex("[0-9a-f]{64}")))
        val target = target()
        val bytes = gson.toJson(UiDesignerCheckpoint(1, projectId, baseHash, draft)).toByteArray(Charsets.UTF_8)
        require(bytes.size <= MAX_BYTES) { "设计恢复记录超过1MiB，请主动保存" }
        val directory = requireNotNull(target.parentFile)
        require(target.exists() || checked(File(target.path + ".previous")).exists() || directory.listFiles { _, name -> name.endsWith(".json") || name.endsWith(".json.previous") }.orEmpty().map { it.name.removeSuffix(".previous") }.distinct().size < 16) { "设计恢复项目记录已满，请先处理旧草稿" }
        val temporary = checked(File(target.path + ".tmp"))
        val previous = checked(File(target.path + ".previous"))
        FileOutputStream(temporary).use { it.write(bytes); it.fd.sync() }
        if (target.exists()) { if (previous.exists()) check(previous.delete()); check(target.renameTo(previous)) }
        if (!temporary.renameTo(target)) { if (!target.exists() && previous.exists()) previous.renameTo(target); error("设计恢复提交失败") }
        if (previous.exists()) check(previous.delete())
    }
    fun discard() = synchronized(lock) {
        if (owner != ownerSequence) return@synchronized
        ++revision; discardLocked()
    }
    private fun discardLocked() {
        val target = target()
        listOf(target, checked(File(target.path + ".previous")), checked(File(target.path + ".tmp"))).forEach { if (it.exists()) check(it.delete()) }
    }
    private fun target(): File {
        require(projectId.length in 1..256)
        val directory = File(files, "ui-designer-drafts")
        require(directory.canonicalFile == File(files.canonicalFile, "ui-designer-drafts"))
        check(directory.isDirectory || directory.mkdirs())
        return checked(File(directory, digest(projectId) + ".json"))
    }
    private fun checked(file: File): File { require(file.canonicalFile == file.absoluteFile); return file }
    private fun checkDraft(draft: RunnerUiDesignerDraft) {
        require(draft.pages.size in 1..16 && draft.fields.size <= 256 && draft.description.length <= 512)
        require(draft.pages.map { it.id }.distinct().size == draft.pages.size && draft.fields.map { it.id }.distinct().size == draft.fields.size)
        draft.pages.forEach { require(it.id.matches(Regex("[A-Za-z_][A-Za-z0-9_]{0,63}")) && it.width in 100..4096 && it.height in 100..8192) }
        draft.fields.forEach { f ->
            require(f.id.matches(Regex("[A-Za-z_][A-Za-z0-9_]{0,63}")) && f.kind in RunnerUiControlKind.entries)
            require(f.label.length <= 128 && f.initialText.length <= 8192 && f.optionsText.length <= 16384 && f.minimum.length <= 64 && f.maximum.length <= 64)
            require(draft.pages.any { it.id == f.ui.pageId } && f.ui.control == f.kind.control.name)
            require(f.ui.x in 0..4096 && f.ui.y in 0..8192 && f.ui.width in 20..4096 && f.ui.height in 20..8192)
            var parent = f.ui.parentId; val seen = mutableSetOf(f.id)
            while (parent != null) {
                require(seen.add(parent) && seen.size <= 9)
                val p = draft.fields.single { it.id == parent }
                require(p.kind == RunnerUiControlKind.CONTAINER && p.ui.pageId == f.ui.pageId)
                parent = p.ui.parentId
            }
        }
    }
    private fun java.io.InputStream.readNBytesCompat(max: Int): ByteArray {
        val output = java.io.ByteArrayOutputStream(); val buffer = ByteArray(8192)
        while (true) { val count = read(buffer); if (count < 0) break; require(output.size() + count <= max); output.write(buffer, 0, count) }
        return output.toByteArray()
    }
    private fun digest(value: String) = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    companion object { private val lock = Any(); private var ownerSequence = 0L; private var revision = 0L; private const val MAX_BYTES = 1024 * 1024 }
}
