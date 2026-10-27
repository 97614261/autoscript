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
    fun withQuotaLock(directory: File, action: () -> Unit) { action() }
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

    private data class Usage(val bytes: Long, val entries: Int, val textBytes: Long, val directories: Int)

    private fun isBinary(file: File): Boolean = ops.input(file).use { input ->
        val signature = ByteArray(32)
        var offset = 0
        while (offset < signature.size) {
            val count = input.read(signature, offset, signature.size - offset)
            if (count <= 0) break
            offset += count
        }
        com.autoscript.runtime.api.RuntimeBinarySignature.matches(signature.copyOf(offset))
    }

    private fun usage(directory: File, check: () -> Unit, depth: Int = 0, ignored: File? = null): Usage {
        check()
        if (depth > 16 || ops.kind(directory) != ProjectDataKind.DIRECTORY || directory.canonicalFile != directory.absoluteFile) fail("FILE_PATH_INVALID")
        val children = directory.listFiles()?.filter { it != ignored } ?: fail("FILE_UNAVAILABLE")
        if (children.size > MAX_ENTRIES) fail("FILE_QUOTA")
        var bytes = 0L
        var textBytes = 0L
        var entries = 0
        var directories = 0
        for (child in children) {
            if (child == ignored) continue
            check()
            if (child.canonicalFile != child.absoluteFile) fail("FILE_PATH_INVALID")
            entries++
            when (ops.kind(child)) {
                ProjectDataKind.DIRECTORY -> { val nested = usage(child, check, depth + 1, ignored); bytes += nested.bytes; textBytes += nested.textBytes; entries += nested.entries; directories += nested.directories + 1 }
                ProjectDataKind.FILE -> { bytes += child.length(); if (!isBinary(child)) textBytes += child.length() }
                else -> fail("FILE_PATH_INVALID")
            }
            if (entries > MAX_ENTRIES || bytes > MAX_TOTAL_BYTES || textBytes > MAX_TEXT_BYTES) fail("FILE_QUOTA")
        }
        return Usage(bytes, entries, textBytes, directories)
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

    fun openAudio(path: String): java.io.FileInputStream {
        val file = resolve(path)
        if (ops.kind(file) != ProjectDataKind.FILE || file.length() !in 1..MAX_PNG_BYTES.toLong()) fail("FILE_TOO_LARGE")
        if (file.extension.lowercase() !in setOf("wav", "mp3", "ogg", "flac", "m4a")) fail("FILE_PATH_INVALID")
        val input = ops.input(file)
        return input as? java.io.FileInputStream ?: run { input.close(); fail("FILE_UNAVAILABLE") }
    }

    fun write(path: String, bytes: ByteArray, check: () -> Unit, commit: (() -> Unit) -> Unit, exclusive: Boolean = false) {
        utf8(bytes)
        if (com.autoscript.runtime.api.RuntimeBinarySignature.matches(bytes)) fail("FILE_BINARY_FORMAT")
        writePayload(path, bytes, check, commit, exclusive, binary = false)
    }

    fun writePng(path: String, bytes: ByteArray, check: () -> Unit, commit: (() -> Unit) -> Unit) {
        if (!path.endsWith(".png", ignoreCase = true) || bytes.size !in 45..MAX_PNG_BYTES ||
            !bytes.take(8).toByteArray().contentEquals(byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10))) fail("FILE_PNG_INVALID")
        writePayload(path, bytes, check, commit, exclusive = true, binary = true)
    }

    private fun writePayload(path: String, bytes: ByteArray, check: () -> Unit, commit: (() -> Unit) -> Unit, exclusive: Boolean, binary: Boolean) {
        check()
        val created = mutableListOf<File>()
        var temporary: File? = null
        try {
            val file = resolve(path, createParents = true, created = created)
            val kind = ops.kind(file)
            if (exclusive && kind != null) fail("FILE_EXISTS")
            if (kind != null && kind != ProjectDataKind.FILE) fail("FILE_PATH_INVALID")
            val live = usage(data, check)
            val oldTextBytes = if (kind == ProjectDataKind.FILE && !isBinary(file)) file.length() else 0L
            if (live.textBytes - oldTextBytes + (if (binary) 0 else bytes.size) > MAX_TEXT_BYTES) fail("FILE_QUOTA")
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
                ops.withQuotaLock(projectDirectory) {
                    check()
                    resolve(path)
                    if (exclusive && ops.kind(file) != null) fail("FILE_EXISTS")
                    if (ops.kind(pending) != ProjectDataKind.FILE || pending.canonicalFile != pending.absoluteFile) fail("FILE_PATH_INVALID")
                    val current = usage(data, check, ignored = pending)
                    val replaced = if (ops.kind(file) == ProjectDataKind.FILE) file.length() else 0L
                    val replacedText = if (replaced > 0 && !isBinary(file)) replaced else 0L
                    if (current.bytes - replaced + bytes.size > MAX_TOTAL_BYTES ||
                        current.textBytes - replacedText + (if (binary) 0 else bytes.size) > MAX_TEXT_BYTES ||
                        (ops.kind(file) == null && current.entries >= MAX_ENTRIES)) fail("FILE_QUOTA")
                    ops.moveAtomic(pending, file)
                }
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

    fun fileSize(path: String, check: () -> Unit): Long {
        check()
        val file = resolve(path)
        if (ops.kind(file) != ProjectDataKind.FILE) fail("FILE_NOT_FOUND")
        val size = file.length()
        if (size !in 0..MAX_TOTAL_BYTES) fail("FILE_TOO_LARGE")
        return size
    }

    fun modifiedTime(path: String, check: () -> Unit): Long {
        check()
        val file=resolve(path)
        if(ops.kind(file)!=ProjectDataKind.FILE) fail("FILE_NOT_FOUND")
        val time=file.lastModified()
        check()
        if(ops.kind(file)!=ProjectDataKind.FILE || time !in 0..253402300799999L) fail("FILE_UNAVAILABLE")
        return time
    }

    fun directoryStats(path: String, check: () -> Unit): String {
        val directory = resolve(path)
        val totals = usage(directory, check)
        return "{\"files\":${totals.entries-totals.directories},\"directories\":${totals.directories},\"bytes\":${totals.bytes}}"
    }

    fun toBase64(path: String, check: () -> Unit): String {
        if (fileSize(path, check) > 49152) fail("FILE_TOO_LARGE")
        val file = resolve(path)
        val output = ByteArrayOutputStream()
        ops.input(file).use { input ->
            val buffer = ByteArray(8192)
            while (true) {
                check()
                val count = input.read(buffer)
                if (count < 0) break
                if (output.size() + count > 49152) fail("FILE_TOO_LARGE")
                output.write(buffer,0,count)
            }
        }
        return StrictBase64.encode(output.toByteArray())
    }

    fun fromBase64(path: String, value: String, check: () -> Unit, commit: (() -> Unit) -> Unit) {
        val bytes = try { StrictBase64.decode(value) } catch (_: IllegalArgumentException) { fail("FILE_REQUEST_INVALID") }
        writePayload(path, bytes, check, commit, exclusive = true, binary = com.autoscript.runtime.api.RuntimeBinarySignature.matches(bytes))
    }

    fun zip(path: String, destination: String, check: () -> Unit, commit: (() -> Unit) -> Unit) {
        if (!destination.endsWith(".zip", ignoreCase = true)) fail("FILE_PATH_INVALID")
        val directory = resolve(path)
        if (ops.kind(directory) != ProjectDataKind.DIRECTORY) fail("FILE_NOT_FOUND")
        val bytes = ByteArrayOutputStream()
        var expanded = 0L
        var entries = 0
        val limited = object : OutputStream() {
            override fun write(value: Int) { check(); if (bytes.size() >= 4 * 1024 * 1024) fail("FILE_TOO_LARGE"); bytes.write(value) }
            override fun write(buffer: ByteArray, offset: Int, length: Int) {
                check(); if (bytes.size() + length > 4 * 1024 * 1024) fail("FILE_TOO_LARGE")
                bytes.write(buffer,offset,length)
            }
        }
        java.util.zip.ZipOutputStream(limited).use { zip ->
            fun append(current: File, prefix: String, depth: Int) {
                check()
                if (depth > 16 || ops.kind(current) != ProjectDataKind.DIRECTORY || current.canonicalFile != current.absoluteFile) fail("FILE_PATH_INVALID")
                val children = current.listFiles() ?: fail("FILE_UNAVAILABLE")
                if (children.size > 128) fail("FILE_QUOTA")
                for (child in children.sortedBy { it.name }) {
                    check()
                    if (++entries > 128 || child.canonicalFile != child.absoluteFile) fail("FILE_QUOTA")
                    val relative = prefix + child.name
                    parts(relative,false)
                    when(ops.kind(child)) {
                        ProjectDataKind.DIRECTORY -> {
                            zip.putNextEntry(java.util.zip.ZipEntry("$relative/")); zip.closeEntry()
                            append(child,"$relative/",depth+1)
                        }
                        ProjectDataKind.FILE -> {
                            val entry=java.util.zip.ZipEntry(relative)
                            zip.putNextEntry(entry)
                            ops.input(child).use { input ->
                                val buffer = ByteArray(8192)
                                while(true) {
                                    check(); val count=input.read(buffer); if(count<0) break
                                    expanded+=count; if(expanded>8L*1024*1024) fail("FILE_TOO_LARGE")
                                    zip.write(buffer,0,count)
                                }
                            }
                            zip.closeEntry()
                            if(entry.size>maxOf(1024L,entry.compressedSize)*100) fail("FILE_QUOTA")
                        }
                        else -> fail("FILE_PATH_INVALID")
                    }
                }
            }
            append(directory,"",0)
        }
        if (expanded > maxOf(1024L,bytes.size().toLong()) * 100) fail("FILE_QUOTA")
        writePayload(destination,bytes.toByteArray(),check,commit,exclusive=true,binary=true)
    }

    fun unzip(path: String, destination: String, check: () -> Unit, commit: (() -> Unit) -> Unit) {
        if (!path.endsWith(".zip",ignoreCase=true)) fail("FILE_PATH_INVALID")
        if (fileSize(path,check)>4L*1024*1024) fail("FILE_TOO_LARGE")
        val source=resolve(path)
        val created=mutableListOf<File>()
        val stagingRoot=File(projectDirectory,".archive-stage")
        ensureDirectory(stagingRoot)
        val archive=File(stagingRoot,"${UUID.randomUUID()}.zip")
        val stage=File(stagingRoot,UUID.randomUUID().toString())
        ensureDirectory(stage); created.add(stage)
        var published=false
        try {
            ops.input(source).use { input -> ops.outputExclusive(archive).use { output ->
                var count=0L; val buffer=ByteArray(8192)
                while(true) { check(); val read=input.read(buffer); if(read<0) break; count+=read
                    if(count>4L*1024*1024) fail("FILE_TOO_LARGE"); output.write(buffer,0,read) }
                ops.sync(output)
            } }
            var expanded=0L
            val names=mutableSetOf<String>()
            java.util.zip.ZipFile(archive).use { zip ->
                if(zip.size()>128) fail("FILE_QUOTA")
                val rows=zip.entries()
                while(rows.hasMoreElements()) {
                    check(); val entry=rows.nextElement()
                    val name=entry.name.removeSuffix("/")
                    val segments=parts(name,false)
                    parts("$destination/$name",false)
                    if(!names.add(name) || entry.method !in setOf(java.util.zip.ZipEntry.STORED,java.util.zip.ZipEntry.DEFLATED) ||
                        entry.size !in 0..8L*1024*1024 || entry.compressedSize<0 || entry.crc<0 ||
                        entry.size>maxOf(1024L,entry.compressedSize)*100) fail("FILE_REQUEST_INVALID")
                    var target=stage
                    for((index,part) in segments.withIndex()) {
                        target=File(target,part)
                        if(index!=segments.lastIndex || entry.isDirectory) {
                            if(!target.exists()) { ensureDirectory(target); created.add(target) }
                            if(ops.kind(target)!=ProjectDataKind.DIRECTORY) fail("FILE_PATH_INVALID")
                        }
                    }
                    if(entry.isDirectory) { if(entry.size!=0L) fail("FILE_REQUEST_INVALID"); continue }
                    if(ops.kind(target)!=null || target.canonicalFile!=target.absoluteFile) fail("FILE_EXISTS")
                    ops.outputExclusive(target).use { output ->
                        created.add(target)
                        zip.getInputStream(entry).use { input ->
                            val crc=java.util.zip.CRC32(); val buffer=ByteArray(8192); var size=0L
                            while(true) { check(); val count=input.read(buffer); if(count<0) break
                                size+=count; expanded+=count
                                if(size>entry.size || expanded>8L*1024*1024 || expanded>maxOf(1024L,archive.length())*100) fail("FILE_TOO_LARGE")
                                crc.update(buffer,0,count); output.write(buffer,0,count)
                            }
                            if(size!=entry.size || crc.value!=entry.crc) fail("FILE_REQUEST_INVALID")
                        }; ops.sync(output)
                    }
                }
            }
            val stageUsage=usage(stage,check)
            if(stageUsage.entries>128) fail("FILE_QUOTA")
            val parents=mutableListOf<File>()
            try {
                val target=resolve(destination,createParents=true,created=parents)
                commit { ops.withQuotaLock(projectDirectory) {
                    check(); resolve(destination)
                    if(ops.kind(target)!=null) fail("FILE_EXISTS")
                    val current=usage(data,check)
                    if(current.bytes+stageUsage.bytes>MAX_TOTAL_BYTES || current.textBytes+stageUsage.textBytes>MAX_TEXT_BYTES ||
                        current.entries+stageUsage.entries+1>MAX_ENTRIES) fail("FILE_QUOTA")
                    ops.moveAtomic(stage,target); published=true
                } }
            } finally { parents.asReversed().forEach { it.delete() } }
        } finally {
            archive.delete()
            if(!published) created.asReversed().forEach { it.delete() }
        }
    }

    fun remove(path: String, check: () -> Unit, commit: (() -> Unit) -> Unit) {
        check()
        val file = resolve(path)
        if (ops.kind(file) != ProjectDataKind.FILE) fail("FILE_NOT_FOUND")
        val binary=isBinary(file)
        if (file.length() > (if(binary) MAX_PNG_BYTES else MAX_FILE_BYTES)) fail("FILE_TOO_LARGE")
        val deleted = usage(trash, check)
        val record = path.toByteArray(Charsets.UTF_8)
        if (deleted.bytes + file.length() + record.size > MAX_TOTAL_BYTES || deleted.textBytes + (if(binary) 0 else file.length()) + record.size > MAX_TEXT_BYTES || deleted.entries + 3 > MAX_ENTRIES) fail("FILE_QUOTA")
        val entry = File(trash, UUID.randomUUID().toString())
        ensureDirectory(entry)
        val metadata = File(entry, "path")
        val payload = File(entry, "file")
        try {
            ops.outputExclusive(metadata).use { it.write(record); ops.sync(it) }
            commit {
                ops.withQuotaLock(projectDirectory) {
                    check(); resolve(path)
                    if (ops.kind(file) != ProjectDataKind.FILE) fail("FILE_NOT_FOUND")
                    val currentBinary=isBinary(file)
                    if (file.length() > (if(currentBinary) MAX_PNG_BYTES else MAX_FILE_BYTES)) fail("FILE_TOO_LARGE")
                    val current = usage(trash, check)
                    if (current.bytes + file.length() > MAX_TOTAL_BYTES || current.textBytes + (if(currentBinary) 0 else file.length()) > MAX_TEXT_BYTES || current.entries >= MAX_ENTRIES)
                        fail("FILE_QUOTA")
                    ops.moveAtomic(file, payload)
                }
            }
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
        val binary=isBinary(payload)
        if (payload.length() > (if(binary) MAX_PNG_BYTES else MAX_FILE_BYTES)) fail("FILE_TOO_LARGE")
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
        if (live.bytes + payload.length() > MAX_TOTAL_BYTES || live.textBytes + (if(binary) 0 else payload.length()) > MAX_TEXT_BYTES || live.entries >= MAX_ENTRIES) fail("FILE_QUOTA")
        commit {
            ops.withQuotaLock(projectDirectory) {
                check(); resolve(path)
                if (ops.kind(destination) != null) fail("FILE_EXISTS")
                if (ops.kind(payload) != ProjectDataKind.FILE || payload.canonicalFile != payload.absoluteFile)
                    fail("FILE_PATH_INVALID")
                val currentBinary=isBinary(payload)
                if (payload.length() > (if(currentBinary) MAX_PNG_BYTES else MAX_FILE_BYTES)) fail("FILE_TOO_LARGE")
                val current = usage(data, check)
                if (current.bytes + payload.length() > MAX_TOTAL_BYTES || current.textBytes + (if(currentBinary) 0 else payload.length()) > MAX_TEXT_BYTES || current.entries >= MAX_ENTRIES)
                    fail("FILE_QUOTA")
                ops.moveAtomic(payload, destination)
            }
        }
        metadata.delete(); entry.delete()
        return path
    }

    /** Missing safe prefixes are absence, but an existing link/file prefix is never followed. */
    fun exists(path: String, directory: Boolean, check: () -> Unit): Boolean {
        check()
        if (ops.kind(data) != ProjectDataKind.DIRECTORY || data.canonicalFile != data.absoluteFile) fail("FILE_PATH_INVALID")
        var current = data
        val segments = parts(path, false)
        for ((index, part) in segments.withIndex()) {
            check(); current = File(current, part)
            if (current.canonicalFile != current.absoluteFile) fail("FILE_PATH_INVALID")
            val kind = ops.kind(current) ?: return false
            if (kind == ProjectDataKind.UNSAFE || (index != segments.lastIndex && kind != ProjectDataKind.DIRECTORY)) fail("FILE_PATH_INVALID")
            if (index == segments.lastIndex) return kind == if (directory) ProjectDataKind.DIRECTORY else ProjectDataKind.FILE
        }
        return false
    }

    fun mkdir(path: String, check: () -> Unit, commit: (() -> Unit) -> Unit) {
        check()
        val created = mutableListOf<File>()
        var completed = false
        try {
            val directory = resolve(path, createParents = true, created = created)
            commit {
                ops.withQuotaLock(projectDirectory) {
                    check(); resolve(path)
                    if (ops.kind(directory) == null) { ensureDirectory(directory); created.add(directory) }
                    if (ops.kind(directory) != ProjectDataKind.DIRECTORY) fail("FILE_PATH_INVALID")
                    usage(data, check)
                    completed = true
                }
            }
        } finally { if (!completed) created.asReversed().forEach { it.delete() } }
    }

    fun move(path: String, destination: String, check: () -> Unit, commit: (() -> Unit) -> Unit) {
        read(path, check) // Only bounded ordinary UTF-8 files, never directories or links.
        val created = mutableListOf<File>()
        try {
            val source = resolve(path)
            val target = resolve(destination, createParents = true, created = created)
            if (ops.kind(target) != null) fail("FILE_EXISTS")
            usage(data, check)
            commit {
                ops.withQuotaLock(projectDirectory) {
                    check(); resolve(path); resolve(destination)
                    if (ops.kind(source) != ProjectDataKind.FILE) fail("FILE_NOT_FOUND")
                    if (ops.kind(target) != null) fail("FILE_EXISTS")
                    read(path, check)
                    usage(data, check)
                    ops.moveAtomic(source, target)
                }
            }
        } finally { created.asReversed().forEach { it.delete() } }
    }

    fun rmdir(path: String, check: () -> Unit, commit: (() -> Unit) -> Unit) {
        check()
        val directory = resolve(path)
        if (ops.kind(directory) != ProjectDataKind.DIRECTORY) fail("FILE_NOT_FOUND")
        commit {
            check(); resolve(path)
            if (ops.kind(directory) != ProjectDataKind.DIRECTORY) fail("FILE_PATH_INVALID")
            if (directory.listFiles()?.isEmpty() != true) fail("FILE_DIRECTORY_NOT_EMPTY")
            if (!directory.delete()) fail("FILE_UNAVAILABLE")
        }
    }

    companion object {
        const val MAX_FILE_BYTES = 65_536
        const val MAX_TOTAL_BYTES = 64L * 1024 * 1024
        const val MAX_TEXT_BYTES = 512L * 1024
        const val MAX_PNG_BYTES = 20 * 1024 * 1024
        const val MAX_ENTRIES = 256
    }
}
