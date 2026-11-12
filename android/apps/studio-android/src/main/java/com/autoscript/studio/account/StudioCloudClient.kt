package com.autoscript.studio

import com.google.gson.JsonObject
import com.google.gson.JsonElement
import com.google.gson.JsonArray
import com.google.gson.JsonPrimitive
import com.google.gson.JsonNull
import com.google.gson.Strictness
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import java.io.StringReader
import java.io.File
import java.net.HttpURLConnection
import java.security.MessageDigest
import java.util.concurrent.CancellationException
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

internal class StudioCloudFailure(val code: Int, val userMessage: String) : Exception(
    if (code == 401) "CLOUD_SESSION_EXPIRED" else "CLOUD_REQUEST_FAILED",
)

internal class StudioCloudClient(
    origin: String,
    allowHttp: Boolean,
    private val session: StudioAccountSession? = null,
) : BackupTransport {
    private val site = StudioUpdateSite.parse(origin)
    private val cancelled = AtomicBoolean()
    private val deadline = System.nanoTime() + TimeUnit.MINUTES.toNanos(5)
    @Volatile private var connection: HttpURLConnection? = null
    override val supportsReplacement: Boolean get() = true
    private val root = "/api/studio/v1"

    init {
        require(site.encrypted || allowHttp) { "CLOUD_HTTPS_REQUIRED" }
        session?.validate()
        require(session == null || StudioUpdateSite.parse(session.origin).origin == site.origin) { "CLOUD_SESSION_INVALID" }
    }

    companion object {
        private val watchdog = ScheduledThreadPoolExecutor(1) { task -> Thread(task, "account-backup-timeout").apply { isDaemon = true } }
            .apply { removeOnCancelPolicy = true }
    }

    override fun check() {
        if (cancelled.get() || Thread.currentThread().isInterrupted) throw CancellationException()
        check(System.nanoTime() < deadline) { "CLOUD_TIMEOUT" }
    }

    override fun close() { cancelled.set(true); connection?.disconnect() }

    fun register(userName: String, password: String, confirmation: String) {
        json("POST", "$root/auth/register", JsonObject().apply {
            addProperty("userName", userName); addProperty("password", password); addProperty("confirmPassword", confirmation)
        })
    }

    fun login(userName: String, password: String, allowHttp: Boolean): StudioAccountSession {
        val data = json("POST", "$root/auth/login", JsonObject().apply {
            addProperty("userName", userName); addProperty("password", password)
        }).getAsJsonObject("data")
        return studioAccountSession(site.origin.toString(), allowHttp, data)
    }

    fun logout() { json("POST", "$root/auth/logout") }

    fun changePassword(oldPassword: String, newPassword: String, confirmation: String) {
        json("POST", "$root/auth/password", JsonObject().apply {
            addProperty("oldPassword", oldPassword); addProperty("newPassword", newPassword)
            addProperty("confirmPassword", confirmation)
        })
    }

    override fun list(): List<ServerBackupRecord> {
        val value = json("GET", "$root/backups?pageNum=1&pageSize=100")
        val rows = value.getAsJsonArray("rows")
        require(rows.size() <= 100 && value["total"].asLong == rows.size().toLong()) { "CLOUD_LIST_CHANGED" }
        return rows.map { record(it.asJsonObject) }.also { items ->
            require(items.map { it.id }.toSet().size == items.size && items.sumOf { it.bytes } <= 2L * 1024 * 1024 * 1024)
        }
    }

    override fun upload(file: File, id: String, label: String, replacing: ServerBackupRecord?): ServerBackupRecord {
        check()
        require(Regex("[a-f0-9]{32}").matches(id) && file.length() in 36..ServerBackupCrypto.MAX_ENCRYPTED)
        require(replacing == null || replacing.id == id)
        val hash = hash(file)
        val metadata = JsonObject().apply {
            addProperty("id", id); addProperty("label", label); addProperty("bytes", file.length()); addProperty("sha256", hash)
        }
        val path = if (replacing == null) "$root/backups" else "$root/backups/$id/replace"
        val result = parse(exchange("POST", path, upload = file,
            metadata = ServerBackupCrypto.base64(metadata.toString().toByteArray(Charsets.UTF_8)), expected = replacing))
        return record(result.getAsJsonObject("data")).also {
            require(it.id == id && it.label == label && it.sha256 == hash && it.bytes == file.length())
        }
    }

    override fun download(record: ServerBackupRecord, file: File) {
        require(Regex("[a-f0-9]{32}").matches(record.id))
        exchange("GET", "$root/backups/${record.id}/content", destination = file, expected = record)
    }

    override fun delete(record: ServerBackupRecord) {
        require(Regex("[a-f0-9]{32}").matches(record.id))
        parse(exchange("DELETE", "$root/backups/${record.id}", expected = record))
    }

    private fun hash(file: File): String = file.inputStream().use { input ->
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(65536)
        while (true) { check(); val n = input.read(buffer); if (n < 0) break; digest.update(buffer, 0, n) }
        ServerBackupCrypto.hex(digest.digest())
    }

    private fun json(method: String, path: String, value: JsonObject? = null): JsonObject =
        parse(exchange(method, path, body = value?.toString()?.toByteArray(Charsets.UTF_8)))

    private fun parse(bytes: ByteArray): JsonObject {
        require(bytes.size in 1..1_048_576) { "CLOUD_RESPONSE_INVALID" }
        val value = JsonReader(StringReader(bytes.toString(Charsets.UTF_8))).use { reader ->
            reader.strictness = Strictness.STRICT
            readStudioCloudJson(reader, 0).asJsonObject.also { require(reader.peek() == JsonToken.END_DOCUMENT) }
        }
        val code = value["code"]?.asInt ?: error("CLOUD_RESPONSE_INVALID")
        if (code != 200) throw StudioCloudFailure(code,
            if (code == 401) "登录已过期，请重新登录" else value["msg"]?.asString?.take(180)?.replace('\n', ' ') ?: "账号服务暂不可用，请稍后重试")
        return value
    }

    private fun record(value: JsonObject): ServerBackupRecord {
        require(value.keySet() == setOf("id", "label", "bytes", "sha256", "createdAt")) { "CLOUD_RESPONSE_INVALID" }
        return ServerBackupRecord(value["id"].asString, value["label"].asString, value["bytes"].asLong,
            value["sha256"].asString, value["createdAt"].asLong).also {
            require(Regex("[a-f0-9]{32}").matches(it.id) && Regex("[a-f0-9]{64}").matches(it.sha256) &&
                it.bytes in 36..ServerBackupCrypto.MAX_ENCRYPTED && it.createdAt > 0) { "CLOUD_RESPONSE_INVALID" }
            ServerBackupCrypto.decryptName(it.label, requireNotNull(session).backupKey, it.id)
        }
    }

    private fun exchange(method: String, path: String, body: ByteArray? = null, upload: File? = null, metadata: String? = null,
                         destination: File? = null, expected: ServerBackupRecord? = null): ByteArray {
        check()
        val conn = site.origin.resolve(path).toURL().openConnection() as HttpURLConnection
        connection = conn
        val lastProgress = AtomicLong(System.nanoTime())
        val timeout = watchdog.scheduleAtFixedRate({
            if (System.nanoTime() >= deadline || System.nanoTime() - lastProgress.get() > TimeUnit.SECONDS.toNanos(30)) conn.disconnect()
        }, 1, 1, TimeUnit.SECONDS)
        try {
            conn.instanceFollowRedirects = false; conn.useCaches = false
            conn.connectTimeout = 12000; conn.readTimeout = 30000; conn.requestMethod = method
            conn.setRequestProperty("Accept-Encoding", "identity")
            session?.let { conn.setRequestProperty("Authorization", "Bearer ${it.token}") }
            expected?.let { conn.setRequestProperty("If-Match", "\"${it.sha256}\"") }
            metadata?.let { conn.setRequestProperty("X-Backup-Metadata", it) }
            if (method == "POST") {
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", if (upload == null) "application/json" else "application/octet-stream")
                conn.setFixedLengthStreamingMode(upload?.length() ?: body?.size?.toLong() ?: 0L)
                conn.outputStream.use { output ->
                    if (upload != null) upload.inputStream().use { input ->
                        val buffer = ByteArray(65536)
                        while (true) { check(); val n = input.read(buffer); if (n < 0) break; output.write(buffer, 0, n); lastProgress.set(System.nanoTime()) }
                    } else if (body != null) output.write(body)
                }
            }
            val status = conn.responseCode
            check()
            require(status !in 300..399) { "CLOUD_REDIRECT_REJECTED" }
            val binary = destination != null && status == 200 && conn.contentType?.substringBefore(';') == "application/octet-stream"
            if (binary) {
                val record = requireNotNull(expected)
                require(conn.contentLengthLong == record.bytes && conn.getHeaderField("ETag") == "\"${record.sha256}\"") { "CLOUD_RESPONSE_INVALID" }
                val digest = MessageDigest.getInstance("SHA-256")
                var total = 0L
                conn.inputStream.use { input -> destination!!.outputStream().use { output ->
                    val buffer = ByteArray(65536)
                    while (true) {
                        check(); val n = input.read(buffer); if (n < 0) break
                        total += n; require(total <= record.bytes) { "CLOUD_RESPONSE_INVALID" }
                        output.write(buffer, 0, n); digest.update(buffer, 0, n); lastProgress.set(System.nanoTime())
                    }
                } }
                require(total == record.bytes && ServerBackupCrypto.hex(digest.digest()) == record.sha256) { "CLOUD_DOWNLOAD_INVALID" }
                return byteArrayOf()
            }
            val bytes = (if (status in 200..299) conn.inputStream else conn.errorStream)?.use { input ->
                val output = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (true) { check(); val n = input.read(buffer); if (n < 0) break
                    require(output.size() + n <= 1_048_576) { "CLOUD_RESPONSE_INVALID" }; output.write(buffer, 0, n); lastProgress.set(System.nanoTime()) }
                output.toByteArray()
            } ?: error("CLOUD_RESPONSE_INVALID")
            if (status != 200 || destination != null) {
                if (conn.contentType?.contains("application/json") == true) parse(bytes)
                error(if (status == 413) "BACKUP_TOO_LARGE" else "CLOUD_HTTP_FAILED")
            }
            return bytes
        } catch (failure: Exception) {
            // A disconnected cancelled request must not turn into a retryable network error.
            check()
            throw failure
        } finally { timeout.cancel(false); conn.disconnect(); connection = null }
    }
}

private fun readStudioCloudJson(reader: JsonReader, depth: Int): JsonElement {
    require(depth <= 6) { "CLOUD_RESPONSE_INVALID" }
    return when (reader.peek()) {
        JsonToken.BEGIN_OBJECT -> JsonObject().apply {
            reader.beginObject()
            while (reader.hasNext()) {
                require(size() < 16)
                val key = reader.nextName(); require(key.length <= 64 && !has(key))
                add(key, readStudioCloudJson(reader, depth + 1))
            }
            reader.endObject()
        }
        JsonToken.BEGIN_ARRAY -> JsonArray().apply {
            reader.beginArray()
            while (reader.hasNext()) { require(size() < 100); add(readStudioCloudJson(reader, depth + 1)) }
            reader.endArray()
        }
        JsonToken.STRING -> JsonPrimitive(reader.nextString().also { require(it.length <= 4096) })
        JsonToken.NUMBER -> JsonPrimitive(java.math.BigDecimal(reader.nextString().also { require(it.length <= 24) }))
        JsonToken.BOOLEAN -> JsonPrimitive(reader.nextBoolean())
        JsonToken.NULL -> { reader.nextNull(); JsonNull.INSTANCE }
        else -> error("CLOUD_RESPONSE_INVALID")
    }
}
