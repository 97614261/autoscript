package com.autoscript.runtime.service

import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.util.UUID
import com.autoscript.runtime.api.RuntimeBinarySignature

/** Frozen, bounded copies. Paths are logical names, never script-provided filesystem roots. */
internal class RuntimeAudioResources(cacheDirectory: File) {
    // Android can alias the app cache through /data/user/0; keep final-directory symlink checks.
    private val directory = File(requireNotNull(cacheDirectory.absoluteFile.parentFile).canonicalFile, cacheDirectory.name)
    private val gate = Any()
    private var epoch = 0L
    private val files = mutableMapOf<String, File>()
    private var bytes = 0L

    private val usable = runCatching {
        if (directory.exists()) {
            require(directory.canonicalFile == directory.absoluteFile && directory.isDirectory)
            val stale = requireNotNull(directory.listFiles())
            require(stale.size <= 512)
            stale.filter { Regex("[0-9a-f-]{36}").matches(it.name) && it.canonicalFile == it.absoluteFile && it.isFile }
                .forEach { require(it.delete()) }
        }
        true
    }.getOrDefault(false)

    fun register(path: String, input: InputStream, valid: () -> Boolean) {
        check(usable) { "audio resource cache unavailable" }
        require(validPath(path))
        val ticket = synchronized(gate) { epoch }
        require(directory.mkdirs() || directory.isDirectory)
        require(directory.canonicalFile == directory.absoluteFile)
        val pending = File(directory, UUID.randomUUID().toString())
        require(pending.createNewFile())
        var published = false
        try {
            FileOutputStream(pending).use { output ->
                val buffer = ByteArray(8192)
                var count = 0L
                while (true) {
                    check(valid())
                    val read = input.read(buffer)
                    if (read < 0) break
                    count += read
                    require(count <= 20L * 1024 * 1024)
                    output.write(buffer, 0, read)
                }
                require(count > 0)
                output.fd.sync()
            }
            val header = FileInputStream(pending).use { stream ->
                val buffer = ByteArray(32)
                var count = 0
                while (count < buffer.size) {
                    val read = stream.read(buffer, count, buffer.size - count)
                    if (read <= 0) break
                    count += read
                }
                buffer.copyOf(count)
            }
            require(RuntimeBinarySignature.matches(header) && !header.take(8).toByteArray().contentEquals(byteArrayOf(-119,80,78,71,13,10,26,10)) && !(header.size >= 2 && header[0] == 80.toByte() && header[1] == 75.toByte()))
            synchronized(gate) {
                check(epoch == ticket && valid())
                require(path !in files && files.size < 256 && bytes + pending.length() <= 64L * 1024 * 1024)
                files[path] = pending
                bytes += pending.length()
                published = true
            }
        } finally { if (!published) pending.delete() }
    }

    fun open(path: String): FileInputStream = synchronized(gate) {
        require(validPath(path))
        FileInputStream(files[path] ?: throw ProjectDataFailure("FILE_NOT_FOUND"))
    }

    fun clear() = synchronized(gate) {
        epoch++
        files.values.forEach { it.delete() }
        files.clear(); bytes = 0
    }

    companion object {
        fun validPath(path: String): Boolean = path.toByteArray(Charsets.UTF_8).size <= 256 &&
            path.startsWith("assets/audio/") && path.substringAfterLast('.').lowercase() in setOf("wav","mp3","ogg","flac","m4a") &&
            path.split('/').let { parts -> parts.size <= 16 && parts.all { it.isNotEmpty() && !it.startsWith('.') && !it.endsWith('.') && !it.endsWith(' ') && it.none { c -> c.isISOControl() || c in "\\:*?\"<>|" } } }
    }
}
