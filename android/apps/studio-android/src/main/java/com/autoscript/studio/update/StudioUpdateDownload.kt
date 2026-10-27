package com.autoscript.studio

import java.io.File
import java.io.RandomAccessFile
import java.security.MessageDigest
import java.util.UUID

internal const val MAX_STUDIO_UPDATE_BYTES = 256L * 1024 * 1024
private val ownedUpdateName = Regex("candidate-[a-f0-9]{32}\\.(?:part|apk)")

/** Only the updater's private cache is touched; projects and browser downloads are unrelated. */
internal object StudioUpdateDownload {
    suspend fun download(site: StudioUpdateSite, release: StudioUpdateRelease, directory: File,
        verify: (File, () -> Unit) -> Unit, progress: (Long) -> Unit): File {
        require(release.bytes in 22..MAX_STUDIO_UPDATE_BYTES) { "Update exceeds cache budget" }
        return StudioUpdateClient.perform(300_000L, discard = { it.delete() }) { request ->
            withCacheLease(directory) {
                val previous = requireNotNull(directory.listFiles())
                require(previous.size <= 16)
                previous.forEach { item ->
                    require(item.name == "owner.lock" || ownedUpdateName.matches(item.name))
                    require(item.canonicalFile == item.absoluteFile && item.isFile)
                }
                previous.filter { ownedUpdateName.matches(it.name) }.forEach { check(it.delete()) }
                require(directory.usableSpace >= release.bytes + 8L * 1024 * 1024)
                val identity = UUID.randomUUID().toString().replace("-", "")
                val temporary = File(directory, "candidate-$identity.part")
                val ready = File(directory, "candidate-$identity.apk")
                try {
                    val connection = request.open(site.apk(release), "application/vnd.android.package-archive")
                    require(connection.contentLengthLong == -1L || connection.contentLengthLong == release.bytes)
                    check(temporary.createNewFile())
                    val digest = MessageDigest.getInstance("SHA-256")
                    var total = 0L
                    var lastProgress = 0L
                    connection.inputStream.use { input -> temporary.outputStream().use { output ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            request.checkpoint()
                            val count = input.read(buffer)
                            if (count < 0) break
                            total += count
                            require(total <= release.bytes)
                            output.write(buffer, 0, count); digest.update(buffer, 0, count)
                            val now = System.nanoTime()
                            if (now - lastProgress >= 150_000_000L) { progress(total); lastProgress = now }
                        }
                    } }
                    require(total == release.bytes && digest.digest().hexUpdate() == release.sha256) { "Update digest mismatch" }
                    request.checkpoint()
                    verify(temporary, request::checkpoint)
                    request.checkpoint()
                    check(temporary.renameTo(ready))
                    progress(total)
                    ready
                } catch (failure: Throwable) {
                    temporary.delete(); ready.delete()
                    throw failure
                }
            }
        }
    }

    fun <T> withCacheLease(directory: File, action: () -> T): T {
        require(directory.canonicalFile == directory.absoluteFile)
        check(directory.isDirectory || directory.mkdirs())
        val lock = File(directory, "owner.lock")
        require(lock.canonicalFile == lock.absoluteFile && (!lock.exists() || lock.isFile))
        RandomAccessFile(lock, "rw").use { access ->
            requireNotNull(access.channel.tryLock()) { "Update cache is busy" }.use { return action() }
        }
    }

    fun verifyBytes(file: File, release: StudioUpdateRelease, checkpoint: () -> Unit) {
        require(ownedUpdateName.matches(file.name) && file.isFile && file.canonicalFile == file.absoluteFile)
        require(file.length() == release.bytes && release.bytes <= MAX_STUDIO_UPDATE_BYTES)
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                checkpoint()
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        require(digest.digest().hexUpdate() == release.sha256)
    }
}

internal fun ByteArray.hexUpdate(): String = joinToString("") { "%02x".format(it.toInt() and 255) }
