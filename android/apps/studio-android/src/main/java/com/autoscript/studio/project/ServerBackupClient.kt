package com.autoscript.studio

import java.io.Closeable
import java.io.File
import java.io.InputStream
import java.net.HttpURLConnection
import java.security.MessageDigest
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit
import com.google.gson.*
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import java.io.StringReader

internal data class ServerBackupRecord(val id: String, val label: String, val bytes: Long, val sha256: String, val createdAt: Long)

/** Separate personal key and endpoint; never uses the APK publisher's credentials. */
internal class ServerBackupClient(origin: String, private val key: String) : Closeable {
    private val site = StudioUpdateSite.parse(origin)
    private val cancelled = AtomicBoolean()
    private val deadline = System.nanoTime() + 300_000_000_000L
    @Volatile private var connection: HttpURLConnection? = null
    private val root = "/api/project-backup/v1"
    private val emptyHash = ServerBackupCrypto.sha(byteArrayOf())
    var supportsReplacement: Boolean = false
        private set
    companion object {
        private val watchdog = ScheduledThreadPoolExecutor(1) { task -> Thread(task, "project-backup-timeout").apply { isDaemon = true } }
            .apply { removeOnCancelPolicy = true }
    }

    init { require(ServerBackupCrypto.validKey(key)) { "BACKUP_CONFIG_INVALID" } }
    fun check() {
        if (cancelled.get() || Thread.currentThread().isInterrupted) throw CancellationException()
        require(System.nanoTime() < deadline) { "BACKUP_TIMEOUT" }
    }
    override fun close() { cancelled.set(true); connection?.disconnect() }

    fun list(): List<ServerBackupRecord> {
        supportsReplacement = false
        val value = json(request("GET", "$root/backups"))
        require(value["protocol"]?.asInt == 1)
        val records = value.getAsJsonArray("records")
        require(records.size() <= 32)
        return records.map { record(it.asJsonObject) }.also { items ->
            require(items.map { it.id }.toSet().size == items.size && items.sumOf { it.bytes } <= 2L * 1024 * 1024 * 1024)
            require(value["bytes"].asLong == items.sumOf { it.bytes })
            supportsReplacement = value.getAsJsonArray("capabilities")?.any { it.isJsonPrimitive && it.asString == "replace" } == true
        }
    }
    fun upload(file: File, id: String, label: String, replacing: ServerBackupRecord? = null): ServerBackupRecord {
        check()
        require(replacing == null || replacing.id == id) { "BACKUP_REPLACE_TARGET_INVALID" }
        check(replacing == null || supportsReplacement) { "BACKUP_REPLACE_UNSUPPORTED" }
        val size = file.length()
        require(size in 36..ServerBackupCrypto.MAX_ENCRYPTED)
        val hash = hash(file)
        val meta = JsonObject().apply { addProperty("id", id); addProperty("label", label); addProperty("bytes", size); addProperty("sha256", hash) }
        val bytes = request("POST", if (replacing == null) "$root/backups" else "$root/backups/$id/replace",
            revision = replacing?.let { "\"${it.sha256}\"" }.orEmpty(),
            metadata = ServerBackupCrypto.base64(meta.toString().toByteArray()), file = file, bodyHash = hash)
        return record(json(bytes)["record"].asJsonObject).also {
            require(it.id == id && it.label == label && it.bytes == size && it.sha256 == hash)
        }
    }
    fun download(record: ServerBackupRecord, file: File) {
        request("GET", "$root/backups/${record.id}", revision = "\"${record.sha256}\"", destination = file, expected = record)
    }
    fun delete(record: ServerBackupRecord) {
        val result = json(request("POST", "$root/backups/${record.id}/delete", revision = "\"${record.sha256}\""))
        require(result["ok"]?.asBoolean == true && result["id"]?.asString == record.id)
    }
    fun hash(file: File): String = file.inputStream().use { input ->
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(65536)
        while (true) {
            check()
            val count = input.read(buffer)
            if (count < 0) break
            digest.update(buffer, 0, count)
        }
        ServerBackupCrypto.hex(digest.digest())
    }
    private fun json(bytes: ByteArray): JsonObject = JsonReader(StringReader(bytes.toString(Charsets.UTF_8))).use { reader ->
        reader.strictness = Strictness.STRICT
        val value = readBackupJson(reader, 0).asJsonObject
        require(reader.peek() == JsonToken.END_DOCUMENT)
        value
    }
    private fun record(obj: JsonObject): ServerBackupRecord {
        require(obj.keySet() == setOf("id", "label", "bytes", "sha256", "createdAt"))
        fun text(name: String): String { val item = obj[name].asJsonPrimitive; require(item.isString); return item.asString }
        fun integer(name: String): Long { val item = obj[name].asJsonPrimitive; require(item.isNumber && Regex("0|[1-9][0-9]{0,15}").matches(item.toString())); return item.asLong }
        return ServerBackupRecord(text("id"), text("label"), integer("bytes"), text("sha256"), integer("createdAt")).also {
            require(Regex("[a-f0-9]{32}").matches(it.id) && Regex("[a-f0-9]{64}").matches(it.sha256))
            require(it.bytes in 36..ServerBackupCrypto.MAX_ENCRYPTED && it.createdAt in 0..9007199254740991L)
            ServerBackupCrypto.decryptName(it.label, key, it.id)
        }
    }

    private fun request(method: String, path: String, revision: String = "", metadata: String = "", file: File? = null,
                        bodyHash: String = emptyHash, destination: File? = null, expected: ServerBackupRecord? = null): ByteArray {
        val nonce = ServerBackupCrypto.id()
        val challenge = json(exchange("GET", "$root/challenge", nonce, "-"))
        require(challenge["protocol"]?.asInt == 1 && challenge["nonce"]?.asString == nonce)
        require(challenge["validForSeconds"]?.asInt == 120)
        val ticket = challenge["challenge"].asString
        require(Regex("[a-f0-9]{64}").matches(ticket))
        return exchange(method, path, nonce, ticket, revision, metadata, file, bodyHash, destination, expected)
    }

    private fun exchange(method: String, path: String, nonce: String, ticket: String, revision: String = "", metadata: String = "",
                         file: File? = null, bodyHash: String = emptyHash, destination: File? = null,
                         expected: ServerBackupRecord? = null): ByteArray {
        check()
        val type = if (file == null) "" else "application/octet-stream"
        val length = file?.length() ?: 0L
        val signature = ServerBackupCrypto.mac(key, "zhigou-publish-request-v1", method, path, nonce, ticket,
            type, length.toString(), ServerBackupCrypto.sha(metadata.toByteArray(Charsets.US_ASCII)), revision, bodyHash)
        val conn = site.origin.resolve(path).toURL().openConnection() as HttpURLConnection
        connection = conn
        val lastProgress = java.util.concurrent.atomic.AtomicLong(System.nanoTime())
        val timeout = watchdog.scheduleAtFixedRate({
            if (System.nanoTime() >= deadline || System.nanoTime() - lastProgress.get() >= 15_000_000_000L) conn.disconnect()
        }, 1, 1, TimeUnit.SECONDS)
        try {
            check()
            conn.instanceFollowRedirects = false
            conn.useCaches = false
            conn.connectTimeout = 15000; conn.readTimeout = 15000
            conn.requestMethod = method
            conn.setRequestProperty("Accept-Encoding", "identity")
            conn.setRequestProperty("X-Publish-Nonce", nonce)
            conn.setRequestProperty("X-Publish-Challenge", ticket)
            conn.setRequestProperty("X-Publish-Signature", signature)
            conn.setRequestProperty("X-Publish-Body-Sha256", bodyHash)
            if (metadata.isNotEmpty()) conn.setRequestProperty("X-Release-Metadata", metadata)
            if (revision.isNotEmpty()) conn.setRequestProperty("If-Match", revision)
            if (method == "POST") {
                conn.doOutput = true
                conn.setFixedLengthStreamingMode(length)
                // Avoid HttpURLConnection's implicit form content type on zero-body POST.
                conn.setRequestProperty("Content-Type", type)
                conn.outputStream.use { output -> file?.inputStream()?.use { input -> copy(input) { bytes, count ->
                    output.write(bytes, 0, count); lastProgress.set(System.nanoTime())
                } } }
            }
            val status = conn.responseCode
            lastProgress.set(System.nanoTime())
            check()
            val maximum = if (status == 200 && destination != null) requireNotNull(expected).bytes else 128L * 1024
            val declared = conn.getHeaderField("Content-Length")?.toLongOrNull()
            require(declared != null && declared in 0..maximum) { "BACKUP_RESPONSE_INVALID" }
            require(conn.getHeaderField("Content-Encoding").let { it == null || it == "identity" }) { "BACKUP_RESPONSE_INVALID" }
            val contentType = conn.contentType?.substringBefore(';')?.trim()
            require(contentType == if (status == 200 && destination != null) "application/octet-stream" else "application/json") { "BACKUP_RESPONSE_INVALID" }
            val digest = MessageDigest.getInstance("SHA-256")
            val memory = java.io.ByteArrayOutputStream()
            val output = if (status == 200 && destination != null) destination.outputStream() else memory
            var received = 0L
            output.use { sink ->
                (if (status >= 400) conn.errorStream else conn.inputStream).use { source ->
                    requireNotNull(source) { "BACKUP_RESPONSE_INVALID" }
                    copy(source) { bytes, count ->
                        received += count
                        lastProgress.set(System.nanoTime())
                        require(received <= declared) { "BACKUP_RESPONSE_TOO_LARGE" }
                        digest.update(bytes, 0, count); sink.write(bytes, 0, count)
                    }
                }
            }
            require(received == declared) { "BACKUP_INCOMPLETE" }
            val hash = ServerBackupCrypto.hex(digest.digest())
            val responseSignature = ServerBackupCrypto.mac(key, "zhigou-publish-response-v1", method, path, nonce, signature, status.toString(), hash)
            val signatures = conn.headerFields.filterKeys { it?.equals("X-Publish-Signature", true) == true }.values.flatten()
            require(signatures.size == 1 && MessageDigest.isEqual(responseSignature.toByteArray(Charsets.US_ASCII), signatures.single().toByteArray(Charsets.US_ASCII))) { "BACKUP_SIGNATURE_INVALID" }
            if (status != 200) {
                val code = runCatching { json(memory.toByteArray())["code"]?.asString }.getOrNull()
                error(code?.takeIf { Regex("[A-Z_]{1,64}").matches(it) } ?: "BACKUP_SERVER_REJECTED")
            }
            if (destination != null) require(received == expected!!.bytes && hash == expected.sha256) { "BACKUP_DIGEST_MISMATCH" }
            return memory.toByteArray()
        } finally {
            timeout.cancel(false)
            conn.disconnect()
            connection = null
        }
    }

    private fun copy(input: InputStream, write: (ByteArray, Int) -> Unit) {
        val buffer = ByteArray(65536)
        while (true) {
            check()
            val count = input.read(buffer)
            if (count < 0) break
            require(count > 0)
            check(); write(buffer, count)
        }
    }
}

private fun readBackupJson(reader: JsonReader, depth: Int): JsonElement {
    require(depth <= 6)
    return when (reader.peek()) {
        JsonToken.BEGIN_OBJECT -> JsonObject().apply {
            reader.beginObject()
            while (reader.hasNext()) {
                require(size() < 16)
                val key = reader.nextName(); require(key.length <= 64 && !has(key))
                add(key, readBackupJson(reader, depth + 1))
            }
            reader.endObject()
        }
        JsonToken.BEGIN_ARRAY -> JsonArray().apply {
            reader.beginArray()
            while (reader.hasNext()) { require(size() < 32); add(readBackupJson(reader, depth + 1)) }
            reader.endArray()
        }
        JsonToken.STRING -> JsonPrimitive(reader.nextString().also { require(it.length <= 2800) })
        JsonToken.NUMBER -> JsonPrimitive(java.math.BigDecimal(reader.nextString().also { require(it.length <= 24) }))
        JsonToken.BOOLEAN -> JsonPrimitive(reader.nextBoolean())
        else -> error("BACKUP_RESPONSE_INVALID")
    }
}
