package com.autoscript.studio

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import com.google.gson.JsonPrimitive
import com.google.gson.Strictness
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import java.io.StringReader
import java.net.URI

internal data class StudioUpdateSite(val origin: URI) {
    val manifest: URI get() = origin.resolve("/downloads/release.json")
    val downloadPage: URI get() = origin.resolve("/download")
    val encrypted: Boolean get() = origin.scheme == "https"
    fun apk(release: StudioUpdateRelease): URI {
        require(Regex("[0-9A-Za-z][0-9A-Za-z.+-]{0,39}").matches(release.versionName) &&
            Regex("[a-f0-9]{64}").matches(release.sha256))
        return origin.resolve("/downloads/zhigou-${release.versionName}-dev-${release.sha256.take(12)}.apk")
    }

    companion object {
        fun parse(value: String): StudioUpdateSite {
            require(value.length in 1..512 && value.all { it.code in 33..126 })
            val uri = URI(value)
            require(uri.scheme in setOf("http", "https") && !uri.host.isNullOrBlank() &&
                uri.rawUserInfo == null && uri.rawQuery == null && uri.rawFragment == null &&
                uri.rawPath in setOf("", "/") && (uri.port == -1 || uri.port in 1..65535))
            return StudioUpdateSite(URI(uri.scheme, null, uri.host, uri.port, "/", null, null))
        }
    }
}

internal data class StudioUpdateRelease(
    val versionCode: Int,
    val versionName: String,
    val abis: List<String>,
    val bytes: Long,
    val sha256: String,
    val description: String,
    val unverifiedCandidate: Boolean,
)

internal enum class StudioUpdateRelation { NEWER, SAME_VERSION, SITE_OLDER }

internal fun studioUpdateRelation(release: StudioUpdateRelease, installedCode: Int): StudioUpdateRelation = when {
    release.versionCode > installedCode -> StudioUpdateRelation.NEWER
    release.versionCode == installedCode -> StudioUpdateRelation.SAME_VERSION
    else -> StudioUpdateRelation.SITE_OLDER
}

/** Only current + alternatives are eligible. History is never an update source. */
internal fun selectStudioUpdate(bytes: ByteArray, supportedAbis: List<String>, sdk: Int): StudioUpdateRelease? {
    require(bytes.size in 1..65536)
    val reader = JsonReader(StringReader(bytes.toString(Charsets.UTF_8))).apply { strictness = Strictness.STRICT }
    val root = reader.use {
        val value = readUpdateJson(it, 0)
        require(it.peek() == JsonToken.END_DOCUMENT)
        value.asJsonObject
    }
    if (root["available"]?.isJsonPrimitive == true && root["available"].asJsonPrimitive.isBoolean && !root["available"].asBoolean) return null
    val alternatives = root["alternatives"]?.let { require(it.isJsonArray); it.asJsonArray.toList() }.orEmpty()
    require(alternatives.size <= 7)
    val releases = (listOf(root) + alternatives.map { it.asJsonObject }).map { item ->
        fun text(key: String): String {
            val value = requireNotNull(item[key]); require(value.isJsonPrimitive && value.asJsonPrimitive.isString)
            return value.asString
        }
        fun integer(key: String): Long {
            val value = requireNotNull(item[key]); require(value.isJsonPrimitive && value.asJsonPrimitive.isNumber)
            require(Regex("[1-9][0-9]{0,10}").matches(value.toString()))
            return value.asLong
        }
        require(text("owner") == "zhigou-docs-download-v1" && text("applicationId") == BuildConfig.APPLICATION_ID)
        require(item["available"]?.isJsonPrimitive == true && item["available"].asJsonPrimitive.isBoolean && item["available"].asBoolean)
        val version = text("versionName"); require(Regex("[0-9A-Za-z][0-9A-Za-z.+-]{0,39}").matches(version))
        val code = integer("versionCode"); require(code <= Int.MAX_VALUE)
        val size = integer("bytes"); require(size in 22..1024L * 1024 * 1024)
        val sha = text("sha256"); require(Regex("[a-f0-9]{64}").matches(sha))
        val abis = requireNotNull(item["abis"]).asJsonArray.map { require(it.isJsonPrimitive && it.asJsonPrimitive.isString); it.asString }
        require(abis.isNotEmpty() && abis.size == abis.toSet().size && abis.all { it in setOf("arm64-v8a", "x86_64") })
        val path = "/downloads/zhigou-$version-dev-${sha.take(12)}.apk"
        require(text("url") == path && text("checksumUrl") == "$path.sha256")
        require(text("variant") in setOf("debug", "release") && text("profile") == "full")
        val minSdk = integer("minSdk"); require(minSdk in 24..Int.MAX_VALUE)
        val description = item["description"]?.let { text("description") }.orEmpty()
        require(description.length <= 240 && description.none { it.code < 32 })
        minSdk to StudioUpdateRelease(code.toInt(), version, abis, size, sha, description,
            item["status"]?.let { text("status") == "optimized-candidate-unverified" } == true)
    }
    require(releases.drop(1).all { it.second.versionCode == releases.first().second.versionCode &&
        it.second.versionName == releases.first().second.versionName })
    val seen = mutableSetOf<String>()
    require(releases.all { (_, release) -> release.abis.all { seen.add(it) } })
    return supportedAbis.firstNotNullOfOrNull { abi -> releases.firstOrNull { (minSdk, item) -> minSdk <= sdk && abi in item.abis }?.second }
}

private fun readUpdateJson(reader: JsonReader, depth: Int): JsonElement {
    require(depth <= 8)
    return when (reader.peek()) {
        JsonToken.BEGIN_OBJECT -> JsonObject().apply {
            reader.beginObject()
            var count = 0
            while (reader.hasNext()) {
                require(++count <= 40)
                val key = reader.nextName(); require(key.length <= 64 && !has(key))
                add(key, readUpdateJson(reader, depth + 1))
            }
            reader.endObject()
        }
        JsonToken.BEGIN_ARRAY -> JsonArray().apply {
            reader.beginArray()
            while (reader.hasNext()) { require(size() < 8); add(readUpdateJson(reader, depth + 1)) }
            reader.endArray()
        }
        JsonToken.STRING -> JsonPrimitive(reader.nextString().also { require(it.length <= 2048) })
        JsonToken.NUMBER -> JsonPrimitive(java.math.BigDecimal(reader.nextString().also { require(it.length <= 32) }))
        JsonToken.BOOLEAN -> JsonPrimitive(reader.nextBoolean())
        JsonToken.NULL -> { reader.nextNull(); JsonNull.INSTANCE }
        else -> error("Invalid update manifest")
    }
}
