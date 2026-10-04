package com.autoscript.runtime.service

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.UUID

internal class ProjectDataFailure(val code: String) : RuntimeException(code)
internal enum class ProjectDataKind { FILE, DIRECTORY, UNSAFE }

/** The Android implementation uses lstat, O_NOFOLLOW and same-filesystem rename. */
internal interface ProjectDataFileOps {
    fun kind(file: File): ProjectDataKind?
    fun input(file: File): InputStream
    fun outputExclusive(file: File): OutputStream
    fun sync(output: OutputStream) { output.flush() }
    fun moveAtomic(source: File, target: File)
}

internal class ProjectDataStore(
    private val projectDirectory: File,
    private val ops: ProjectDataFileOps,
) {
    private val data = File(projectDirectory, "data")
    private val trash = File(projectDirectory, ".trash")

    init {
        ensureDirectory(projectDirectory)
        ensureDirectory(data)
        ensureDirectory(trash)
    }

    private fun fail(code: String): Nothing = throw ProjectDataFailure(code)

    private fun ensureDirectory(directory: File) {
        if (directory.canonicalFile != directory.absoluteFile) fail("FILE_PATH_INVALID")
        if (!directory.exists() && !directory.mkdirs()) fail("FILE_UNAVAILABLE")
        if (ops.kind(directory) != ProjectDataKind.DIRECTORY || directory.canonicalFile != directory.absoluteFile)
            fail("FILE_PATH_INVALID")
    }

    private fun parts(path: String, allowEmpty: Boolean): List<String> {
        if (path.toByteArray(Charsets.UTF_8).size > 256 || (path.isEmpty() && !allowEmpty)) fail("FILE_PATH_INVALID")
        if (path.isEmpty()) return emptyList()
        val parts = path.split('/')
        if (parts.size > 16 || parts.any { part ->
            part.isEmpty() || part == "." || part == ".." || part.startsWith('.') || part.endsWith('.') || part.endsWith(' ') ||
                part.any { it.isISOControl() || it in "\\:*?\"<>|" }
        }) fail("FILE_PATH_INVALID")
        return parts
    }

    private fun resolve(path: String, allowEmpty: Boolean = false, createParents: Boolean = false, created: MutableList<File>? = null): File {
        if (ops.kind(data) != ProjectDataKind.DIRECTORY || data.canonicalFile != data.absoluteFile) fail("FILE_PATH_INVALID")
        var current = data
        val segments = parts(path, allowEmpty)
        segments.forEachIndexed { index, part ->
            current = File(current, part)
            val last = index == segments.lastIndex
            if (!last && !current.exists() && createParents) { ensureDirectory(current); created?.add(current) }
            val kind = ops.kind(current)
            if ((!last && kind != ProjectDataKind.DIRECTORY) || kind == ProjectDataKind.UNSAFE || current.canonicalFile != current.absoluteFile)
                fail("FILE_PATH_INVALID")
        }
        return current
    }

    private data class Usage(val bytes: Long, val entries: Int)

    private fun usage(directory: File, check: () -> Unit, depth: Int = 0): Usage {
        check()
        if (depth > 16 || ops.kind(directory) != ProjectDataKind.DIRECTORY || directory.canonicalFile != directory.absoluteFile) fail("FILE_PATH_INVALID")
        val children = directory.listFiles() ?: fail("FILE_UNAVAILABLE")
        if (children.size > MAX_ENTRIES) fail("FILE_QUOTA")
        var bytes = 0L
        var entries = 0
        for (child in children) {
            check()
            if (child.canonicalFile != child.absoluteFile) fail("FILE_PATH_INVALID")
            entries++
            when (ops.kind(child)) {
                ProjectDataKind.DIRECTORY -> { val nested = usage(child, check, depth + 1); bytes += nested.bytes; entries += nested.entries }
                ProjectDataKind.FILE -> bytes += child.length()
                else -> fail("FILE_PATH_INVALID")
            }
            if (entries > MAX_ENTRIES || bytes > MAX_TOTAL_BYTES) fail("FILE_QUOTA")
        }
        return Usage(bytes, entries)
    }

    private fun utf8(bytes: ByteArray) {
        if (bytes.size > MAX_FILE_BYTES) fail("FILE_TOO_LARGE")
        try { Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)) }
        catch (_: java.nio.charset.CharacterCodingException) { fail("FILE_UTF8_INVALID") }
    }

    fun read(path: String, check: () -> Unit): ByteArray {
        check()
        val file = resolve(path)
        if (ops.kind(file) != ProjectDataKind.FILE) fail("FILE_NOT_FOUND")
        if (file.length() > MAX_FILE_BYTES) fail("FILE_TOO_LARGE")
        val output = ByteArrayOutputStream()
        ops.input(file).use { input ->
            resolve(path)
            val buffer = ByteArray(8192)
            while (true) {
                check()
                val count = input.read(buffer)
                if (count < 0) break
                if (output.size() + count > MAX_FILE_BYTES) fail("FILE_TOO_LARGE")
                output.write(buffer, 0, count)
            }
        }
        check()
        return output.toByteArray().also(::utf8)
    }

    fun write(path: String, bytes: ByteArray, check: () -> Unit, commit: (() -> Unit) -> Unit) {
        utf8(bytes)
        check()
        val created = mutableListOf<File>()
        var temporary: File? = null
        try {
            val file = resolve(path, createParents = true, created = created)
            val kind = ops.kind(file)
            if (kind != null && kind != ProjectDataKind.FILE) fail("FILE_PATH_INVALID")
            val live = usage(data, check)
            if (live.bytes - (if (kind == ProjectDataKind.FILE) file.length() else 0) + bytes.size > MAX_TOTAL_BYTES ||
                (kind == null && live.entries >= MAX_ENTRIES)) fail("FILE_QUOTA")
            val pending = File(requireNotNull(file.parentFile), ".autoscript-pending-${UUID.randomUUID()}")
            temporary = pending
            ops.outputExclusive(pending).use { output ->
                var offset = 0
                while (offset < bytes.size) { check(); val count = minOf(8192, bytes.size - offset); output.write(bytes, offset, count); offset += count }
                ops.sync(output)
            }
            commit {
                check()
                resolve(path)
                if (ops.kind(pending) != ProjectDataKind.FILE || pending.canonicalFile != pending.absoluteFile) fail("FILE_PATH_INVALID")
                ops.moveAtomic(pending, file)
            }
        } finally {
            temporary?.takeIf(File::exists)?.delete()
            // Only directories created by this request are considered, and delete refuses nonempty ones.
            created.asReversed().forEach { it.delete() }
        }
    }

    fun list(path: String, check: () -> Unit): ByteArray {
        check()
        val directory = resolve(path, allowEmpty = true)
        if (ops.kind(directory) != ProjectDataKind.DIRECTORY) fail("FILE_NOT_FOUND")
        usage(data, check)
        val rows = directory.listFiles()?.sortedBy { it.name } ?: fail("FILE_UNAVAILABLE")
        val prefix = if (path.isEmpty()) "" else "$path/"
        val encoded = rows.filterNot { it.name.startsWith(".autoscript-pending-") }.map { file ->
            check()
            val relative = prefix + file.name
            resolve(relative)
            val suffix = when (ops.kind(file)) { ProjectDataKind.FILE -> ""; ProjectDataKind.DIRECTORY -> "/"; else -> fail("FILE_PATH_INVALID") }
            "\"${(relative + suffix).replace("\\", "\\\\").replace("\"", "\\\"")}\""
        }.joinToString(",", "[", "]").toByteArray(Charsets.UTF_8)
        if (encoded.size > MAX_FILE_BYTES) fail("FILE_TOO_LARGE")
        check()
        return encoded
    }

    fun remove(path: String, check: () -> Unit, commit: (() -> Unit) -> Unit) {
        check()
        val file = resolve(path)
        if (ops.kind(file) != ProjectDataKind.FILE) fail("FILE_NOT_FOUND")
        if (file.length() > MAX_FILE_BYTES) fail("FILE_TOO_LARGE")
        val deleted = usage(trash, check)
        val record = path.toByteArray(Charsets.UTF_8)
        if (deleted.bytes + file.length() + record.size > MAX_TOTAL_BYTES || deleted.entries + 3 > MAX_ENTRIES) fail("FILE_QUOTA")
        val entry = File(trash, UUID.randomUUID().toString())
        ensureDirectory(entry)
        val metadata = File(entry, "path")
        val payload = File(entry, "file")
        try {
            ops.outputExclusive(metadata).use { it.write(record); ops.sync(it) }
            commit { check(); resolve(path); ops.moveAtomic(file, payload) }
        } finally {
            if (!payload.exists()) { metadata.delete(); entry.delete() }
        }
    }

    /** Recovery is a host operation; Lua cannot access the private recycling directory. */
    fun restore(entryId: String, check: () -> Unit, commit: (() -> Unit) -> Unit): String {
        if (!Regex("[0-9a-f-]{36}").matches(entryId)) fail("FILE_PATH_INVALID")
        val entry = File(trash, entryId)
        if (ops.kind(entry) != ProjectDataKind.DIRECTORY || entry.canonicalFile != entry.absoluteFile) fail("FILE_PATH_INVALID")
        val metadata = File(entry, "path")
        val payload = File(entry, "file")
        if (ops.kind(metadata) != ProjectDataKind.FILE || metadata.length() !in 1..256 || ops.kind(payload) != ProjectDataKind.FILE) fail("FILE_PATH_INVALID")
        if (payload.length() > MAX_FILE_BYTES) fail("FILE_TOO_LARGE")
        val record = ops.input(metadata).use { input ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(257)
            while (true) {
                check()
                val count = input.read(buffer)
                if (count < 0) break
                if (output.size() + count > 256) fail("FILE_PATH_INVALID")
                output.write(buffer, 0, count)
            }
            output.toByteArray()
        }
        utf8(record)
        val path = record.toString(Charsets.UTF_8)
        val destination = resolve(path, createParents = true)
        if (destination.exists()) fail("FILE_EXISTS")
        val live = usage(data, check)
        if (live.bytes + payload.length() > MAX_TOTAL_BYTES || live.entries >= MAX_ENTRIES) fail("FILE_QUOTA")
        commit { check(); resolve(path); ops.moveAtomic(payload, destination) }
        metadata.delete(); entry.delete()
        return path
    }

    companion object {
        const val MAX_FILE_BYTES = 65_536
        const val MAX_TOTAL_BYTES = 512L * 1024
        const val MAX_ENTRIES = 256
    }
}
