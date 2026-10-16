package com.autoscript.runtime.vision.components

import com.autoscript.runtime.service.NativeVisionSpi
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonPrimitive
import com.google.gson.Strictness
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import java.io.StringReader
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction

enum class VisionComponentKind(val id: String, val sdkVersion: String, val providerClass: String) {
    OPENCV("opencv", "4.10.0", NativeVisionSpi.OPENCV_FACTORY),
    ONNX("onnx", "1.19.2", NativeVisionSpi.ONNX_FACTORY);

    val applicationId: String get() = "com.zhigou.component.$id"
    val nativeLibraries: Set<String> get() = if (this == OPENCV) {
        setOf("libc++_shared.so", "libopencv_java4.so")
    } else {
        setOf("libonnxruntime.so", "libonnxruntime4j_jni.so")
    }
    val licenses: Set<String> get() = if (this == OPENCV) {
        setOf("assets/vision/OpenCV-LICENSE.txt")
    } else {
        setOf("assets/vision/ONNXRuntime-LICENSE.txt", "assets/vision/PaddleOCR-LICENSE.txt", "assets/vision/NOTICE.txt")
    }
}

class VisionComponentCompatibilityException : IllegalArgumentException("VISION_COMPONENT_INCOMPATIBLE")

data class VisionComponentDescriptor(val kind: VisionComponentKind, val abis: Set<String>) {
    companion object {
        const val PATH = "assets/packaging/vision-component.json"
        const val MODEL_PATH = "assets/vision/en_PP-OCRv4_rec_mobile.onnx"
        const val MAX_BYTES = 8192

        fun parse(bytes: ByteArray): VisionComponentDescriptor {
            require(bytes.size in 1..MAX_BYTES)
            val text = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
            val root = JsonReader(StringReader(text)).use { reader ->
                reader.strictness = Strictness.STRICT
                val value = read(reader, 0)
                require(reader.peek() == JsonToken.END_DOCUMENT && value.isJsonObject)
                value.asJsonObject
            }
            fun text(key: String): String = root.get(key).also {
                require(it != null && it.isJsonPrimitive && it.asJsonPrimitive.isString)
            }.asString
            fun integer(key: String): Int = root.get(key).also {
                require(it != null && it.isJsonPrimitive && it.asJsonPrimitive.isNumber && it.asString.matches(Regex("[0-9]{1,9}")))
            }.asInt
            fun compatible(condition: Boolean) { if (!condition) throw VisionComponentCompatibilityException() }
            val kind = VisionComponentKind.entries.singleOrNull { it.id == text("kind") }
                ?: throw VisionComponentCompatibilityException()
            val keys = setOf("formatVersion", "kind", "componentVersion", "spiVersion", "sdkVersion", "applicationId",
                "versionCode", "versionName", "minSdk", "abis", "providerClass", "licenses") +
                if (kind == VisionComponentKind.ONNX) setOf("model") else emptySet()
            require(root.keySet() == keys)
            compatible(integer("formatVersion") == 1 && integer("componentVersion") == 1 && integer("spiVersion") == NativeVisionSpi.VERSION)
            compatible(integer("versionCode") == 1 && integer("minSdk") == 24 && text("versionName") == kind.sdkVersion)
            compatible(text("applicationId") == kind.applicationId && text("sdkVersion") == kind.sdkVersion)
            compatible(text("providerClass") == kind.providerClass)
            val abis = strings(root.get("abis"), 2)
            compatible("arm64-v8a" in abis && abis.all { it in setOf("arm64-v8a", "x86_64") })
            require(strings(root.get("licenses"), 3) == kind.licenses)
            if (kind == VisionComponentKind.ONNX) {
                val model = root.get("model").also { require(it.isJsonObject) }.asJsonObject
                require(model.keySet() == setOf("path", "size", "sha256"))
                require(model.get("path").let { it.isJsonPrimitive && it.asJsonPrimitive.isString && it.asString == MODEL_PATH })
                require(model.get("sha256").let { it.isJsonPrimitive && it.asJsonPrimitive.isString && it.asString == NativeVisionSpi.MODEL_SHA256 })
                require(model.get("size").let { it.isJsonPrimitive && it.asJsonPrimitive.isNumber && it.asString == NativeVisionSpi.MODEL_BYTES.toString() })
            }
            return VisionComponentDescriptor(kind, abis)
        }

        private fun strings(value: JsonElement, max: Int): Set<String> {
            require(value.isJsonArray && value.asJsonArray.size() in 1..max)
            val items = value.asJsonArray.map { require(it.isJsonPrimitive && it.asJsonPrimitive.isString); it.asString }
            require(items.size == items.toSet().size)
            return items.toSet()
        }

        private fun read(reader: JsonReader, depth: Int): JsonElement {
            require(depth <= 4)
            return when (reader.peek()) {
                JsonToken.BEGIN_OBJECT -> JsonObject().also { obj ->
                    reader.beginObject()
                    while (reader.hasNext()) {
                        val key = reader.nextName()
                        require(key.length <= 64 && !obj.has(key) && obj.size() < 16)
                        obj.add(key, read(reader, depth + 1))
                    }
                    reader.endObject()
                }
                JsonToken.BEGIN_ARRAY -> JsonArray().also { arr ->
                    reader.beginArray()
                    while (reader.hasNext()) { require(arr.size() < 4); arr.add(read(reader, depth + 1)) }
                    reader.endArray()
                }
                JsonToken.STRING -> JsonPrimitive(reader.nextString().also { require(it.length <= 160) })
                JsonToken.NUMBER -> JsonPrimitive(reader.nextString().also { require(it.matches(Regex("[0-9]{1,9}"))) }.toInt())
                else -> throw IllegalArgumentException("VISION_COMPONENT_DESCRIPTOR_INVALID")
            }
        }
    }
}
