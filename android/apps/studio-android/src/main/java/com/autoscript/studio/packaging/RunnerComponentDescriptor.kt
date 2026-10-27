package com.autoscript.studio

import com.autoscript.runtime.api.RuntimeProtocol
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonPrimitive
import com.google.gson.Strictness
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import java.io.StringReader

internal data class RunnerComponentDescriptor(val family: RunnerTemplateFamily, val abis: Set<String>) {
    companion object {
        const val PATH = "assets/packaging/runner-component.json"
        const val MODEL_PATH = "assets/vision/en_PP-OCRv4_rec_mobile.onnx"
        const val MODEL_SHA256 = "e8770c967605983d1570cdf5352041dfb68fa0c21664f49f47b155abd3e0e318"
        const val MAX_BYTES = 8192
        const val API = "1.8"
        const val COMPONENT_VERSION = RuntimeProtocol.RUNNER_TEMPLATE_COMPONENT_VERSION

        fun parse(bytes: ByteArray): RunnerComponentDescriptor {
            require(bytes.size in 1..MAX_BYTES) { "组件描述超过预算" }
            val reader = JsonReader(StringReader(bytes.toString(Charsets.UTF_8))).apply { strictness = Strictness.STRICT }
            val root = reader.use {
                val value = read(it, 0)
                require(it.peek() == JsonToken.END_DOCUMENT)
                require(value.isJsonObject)
                value.asJsonObject
            }
            require(root.keySet() == setOf("formatVersion", "kind", "componentVersion", "runtimeProtocol", "runtimeApi",
                "family", "applicationId", "versionCode", "versionName", "minSdk", "abis", "dependencies"))
            fun text(key: String): String {
                val value = root.get(key)
                require(value.isJsonPrimitive && value.asJsonPrimitive.isString)
                return value.asString
            }
            fun integer(key: String): Int {
                val value = root.get(key)
                require(value.isJsonPrimitive && value.asJsonPrimitive.isNumber && value.asString.matches(Regex("[0-9]{1,9}")))
                return value.asString.toInt()
            }
            fun compatible(condition: Boolean) { if (!condition) throw RunnerComponentCompatibilityException() }
            compatible(integer("formatVersion") == 1 && text("kind") == "runner-template" && integer("componentVersion") == COMPONENT_VERSION)
            compatible(integer("runtimeProtocol") == RuntimeProtocol.VERSION && text("runtimeApi") == API)
            compatible(text("applicationId") == DevicePackagingController.APPLICATION_ID && integer("versionCode") == DevicePackagingController.VERSION_CODE &&
                text("versionName") == DevicePackagingController.VERSION_NAME && integer("minSdk") == 24)
            val family = when (text("family")) {
                "base" -> RunnerTemplateFamily.BASE
                "opencv" -> RunnerTemplateFamily.OPENCV
                "onnx" -> RunnerTemplateFamily.ONNX
                "full" -> RunnerTemplateFamily.FULL
                else -> throw RunnerComponentCompatibilityException()
            }
            val abiValues = root.get("abis").also { require(it.isJsonArray) }.asJsonArray
            require(abiValues.size() in 1..2 && abiValues.all { it.isJsonPrimitive && it.asJsonPrimitive.isString })
            val abis = abiValues.map { it.asString }.toSet()
            require(abis.size == abiValues.size())
            compatible(abis.all { it in setOf("arm64-v8a", "x86_64") })
            val dependencies = root.get("dependencies").also { require(it.isJsonObject) }.asJsonObject
            val cv = family in setOf(RunnerTemplateFamily.OPENCV, RunnerTemplateFamily.FULL)
            val onnx = family in setOf(RunnerTemplateFamily.ONNX, RunnerTemplateFamily.FULL)
            val required = buildSet { if (cv) add("opencv"); if (onnx) { add("onnx"); add("model") } }
            require(dependencies.keySet() == required) { "组件依赖组合不匹配" }
            fun dependency(id: String, version: String, license: String, hash: String? = null) {
                val dep = dependencies.get(id).also { require(it.isJsonObject) }.asJsonObject
                require(dep.keySet() == if (hash == null) setOf("version", "license") else setOf("version", "license", "sha256"))
                fun exact(key: String, expected: String) = require(dep.get(key).let { it.isJsonPrimitive && it.asJsonPrimitive.isString && it.asString == expected })
                exact("version", version); exact("license", license)
                hash?.let { exact("sha256", it) }
            }
            if (cv) dependency("opencv", "4.10.0", "assets/vision/OpenCV-LICENSE.txt")
            if (onnx) {
                dependency("onnx", "1.19.2", "assets/vision/ONNXRuntime-LICENSE.txt")
                dependency("model", "en_PP-OCRv4_rec_mobile", "assets/vision/PaddleOCR-LICENSE.txt", MODEL_SHA256)
            }
            return RunnerComponentDescriptor(family, abis)
        }

        // Reject duplicate keys rather than allowing a JSON library to silently replace them.
        private fun read(reader: JsonReader, depth: Int): JsonElement {
            require(depth <= 4)
            return when (reader.peek()) {
                JsonToken.BEGIN_OBJECT -> JsonObject().also { objectValue ->
                    reader.beginObject()
                    while (reader.hasNext()) {
                        val key = reader.nextName()
                        require(key.length <= 64 && !objectValue.has(key) && objectValue.size() < 24)
                        objectValue.add(key, read(reader, depth + 1))
                    }
                    reader.endObject()
                }
                JsonToken.BEGIN_ARRAY -> JsonArray().also { array ->
                    reader.beginArray()
                    while (reader.hasNext()) { require(array.size() < 8); array.add(read(reader, depth + 1)) }
                    reader.endArray()
                }
                JsonToken.STRING -> JsonPrimitive(reader.nextString().also { require(it.length <= 128) })
                JsonToken.NUMBER -> JsonPrimitive(reader.nextString().also { require(it.matches(Regex("[0-9]{1,9}"))) }.toInt())
                else -> error("非法组件描述类型")
            }
        }
    }
}
