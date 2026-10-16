package com.autoscript.studio

import android.content.Context
import android.net.Uri
import android.system.Os
import android.system.OsConstants
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

/** Explicit document import into private runtime data, never a generated project resource. */
internal fun importRuntimeAudio(context: Context, projectId: String, uri: Uri): String {
    require(Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,63}").matches(projectId))
    val mime = context.contentResolver.getType(uri).orEmpty().lowercase()
    val extension = when (mime) {
        "audio/wav", "audio/x-wav", "audio/vnd.wave" -> "wav"
        "audio/mpeg", "audio/mp3" -> "mp3"
        "audio/ogg", "application/ogg" -> "ogg"
        "audio/flac", "audio/x-flac" -> "flac"
        "audio/mp4", "audio/x-m4a" -> "m4a"
        else -> error("请选择WAV、MP3、OGG、FLAC或M4A音频")
    }
    var directory = context.filesDir.canonicalFile
    for (segment in listOf("script-data", projectId, "data", "audio")) {
        directory = File(directory, segment)
        if (!directory.exists()) require(directory.mkdir() || directory.isDirectory)
        require(directory.canonicalFile == directory.absoluteFile && OsConstants.S_ISDIR(Os.lstat(directory.path).st_mode))
    }
    val name = "${UUID.randomUUID()}.$extension"
    val temporary = File(directory, ".audio-import-${UUID.randomUUID()}")
    val target = File(directory, name)
    require(temporary.createNewFile())
    var published = false
    try {
        requireNotNull(context.contentResolver.openInputStream(uri)).use { input ->
            FileOutputStream(temporary).use { output ->
                val buffer = ByteArray(8192)
                var bytes = 0L
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    bytes += count
                    require(bytes <= 20L * 1024 * 1024) { "音频最大20MiB" }
                    output.write(buffer, 0, count)
                }
                require(bytes > 0) { "音频文件为空" }
                output.fd.sync()
            }
        }
        // link refuses an existing destination; unlink the private staging name before returning.
        val prefix = java.io.FileInputStream(temporary).use { input ->
            val bytes = ByteArray(32)
            var count = 0
            while (count < bytes.size) { val read = input.read(bytes, count, bytes.size - count); if (read <= 0) break; count += read }
            bytes.copyOf(count)
        }
        require(com.autoscript.project.store.projectAudioExtension(prefix) != null) { "音频文件格式无效" }
        val data = requireNotNull(directory.parentFile)
        com.autoscript.runtime.api.RuntimeDataQuotaLock.withLock(requireNotNull(data.parentFile)) {
            var entries = 0
            var total = 0L
            fun count(current: File, depth: Int) {
                require(depth <= 16)
                val children = requireNotNull(current.listFiles())
                require(children.size <= 257)
                for (child in children) {
                    if (child == temporary) continue
                    require(child.canonicalFile == child.absoluteFile)
                    val stat = Os.lstat(child.path)
                    entries++
                    require(entries < 256) { "私有数据文件数量已满" }
                    if (OsConstants.S_ISDIR(stat.st_mode)) count(child, depth + 1)
                    else {
                        require(OsConstants.S_ISREG(stat.st_mode) && stat.st_nlink == 1L)
                        total += stat.st_size
                        require(total <= 64L * 1024 * 1024 - temporary.length()) { "私有数据总量超过64MiB" }
                    }
                }
            }
            count(data, 0)
            Os.link(temporary.path, target.path)
            published = true
            require(temporary.delete()) { "无法提交音频" }
        }
        return "audio/$name"
    } catch (error: Exception) {
        if (published) target.delete()
        throw error
    } finally { temporary.delete() }
}
