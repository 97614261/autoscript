package com.autoscript.studio

import com.autoscript.project.store.ProjectSnapshot
import com.autoscript.project.store.ProjectSourceMode
import com.autoscript.project.store.MAX_LUA_SOURCE_BYTES
import com.autoscript.runtime.api.RuntimeProtocol
import com.autoscript.runtime.client.RuntimeProjectResource
import com.autoscript.runtime.client.RuntimeProjectResourceKind
import java.io.File
import java.security.MessageDigest
import com.google.gson.JsonParser

internal data class RuntimeProjectPlan(
    val luaSource: ByteArray,
    val resources: List<RuntimeProjectResource>,
    val designWidth: Int,
    val designHeight: Int,
    val scaleMode: Int,
) {
    companion object {
        fun fromSnapshot(snapshot: ProjectSnapshot, source: String): RuntimeProjectPlan {
            require(snapshot.manifest.sourceMode == ProjectSourceMode.LUA) {
                "当前批次只能运行手写Lua项目"
            }
            return create(snapshot, source.toByteArray(Charsets.UTF_8))
        }

        fun fromVisualSnapshot(
            snapshot: ProjectSnapshot,
            expectedGenerationId: String,
        ): RuntimeProjectPlan {
            require(snapshot.manifest.sourceMode == ProjectSourceMode.VISUAL) {
                "当前项目不是可视化项目"
            }
            val generated = containedDirectory(snapshot.directory, "generated")
            val lua = readBounded(File(generated, "main.lua"), MAX_LUA_SOURCE_BYTES)
            val sourceMap = readBounded(File(generated, "source-map.json"), MAX_SOURCE_MAP_BYTES)
            val recordBytes = readBounded(File(generated, "generation.json"), MAX_RECORD_BYTES)
            val record = JsonParser.parseString(recordBytes.toString(Charsets.UTF_8)).asJsonObject
            val generationId = record.requiredString("generationId")
            require(generationId == expectedGenerationId) { "生成代次已变化，请重新编译" }
            require(record.requiredString("luaDigest") == sha256(lua)) { "生成Lua摘要不匹配" }
            require(record.requiredString("sourceMapDigest") == sha256(sourceMap)) {
                "source map摘要不匹配"
            }
            val sourceMapGeneration = JsonParser.parseString(
                sourceMap.toString(Charsets.UTF_8),
            ).asJsonObject.requiredString("generationId")
            require(sourceMapGeneration == generationId) { "source map代次不匹配" }
            val expectedHeader = "-- generationId: $generationId\n".toByteArray(Charsets.UTF_8)
            require(lua.indexOf(expectedHeader) >= 0) { "生成Lua代次不匹配" }
            return create(snapshot, lua)
        }

        private fun create(snapshot: ProjectSnapshot, luaSource: ByteArray): RuntimeProjectPlan {
            require(luaSource.size <= MAX_LUA_SOURCE_BYTES) { "Lua源码超过16 MiB" }
            val projectRoot = snapshot.directory.canonicalFile
            require(projectRoot.isDirectory) { "项目目录不存在" }
            val resources = snapshot.manifest.resources.map { declaration ->
                val kindText = declaration.get("kind")?.asString
                    ?: throw IllegalArgumentException("资源缺少kind")
                val path = declaration.get("path")?.asString
                    ?: throw IllegalArgumentException("资源缺少path")
                require(isCanonicalResourcePath(path)) { "资源路径无效：$path" }
                val file = File(projectRoot, path).canonicalFile
                require(file.path.startsWith(projectRoot.path + File.separator) && file != projectRoot) {
                    "资源路径越界：$path"
                }
                require(file.isFile && file.canRead()) { "资源不存在或不可读：$path" }
                RuntimeProjectResource(
                    kind = when (kindText) {
                        "image" -> RuntimeProjectResourceKind.IMAGE
                        "glyphDictionary" -> RuntimeProjectResourceKind.GLYPH_DICTIONARY
                        else -> throw IllegalArgumentException("不支持的资源类型：$kindText")
                    },
                    path = path,
                    file = file,
                )
            }.sortedBy(RuntimeProjectResource::path)
            val design = snapshot.manifest.design
            return RuntimeProjectPlan(
                luaSource = luaSource,
                resources = resources,
                designWidth = design.width,
                designHeight = design.height,
                scaleMode = when (design.scaleMode) {
                    "letterbox" -> RuntimeProtocol.SCALE_LETTERBOX
                    "crop" -> RuntimeProtocol.SCALE_CROP
                    "stretch" -> RuntimeProtocol.SCALE_STRETCH
                    else -> throw IllegalArgumentException("不支持的缩放模式：${design.scaleMode}")
                },
            )
        }

        private fun isCanonicalResourcePath(path: String): Boolean =
            path.length in 1..256 &&
                '\\' !in path &&
                '\u0000' !in path &&
                path.split('/').all { it.isNotEmpty() && it != "." && it != ".." }

        private fun containedDirectory(rootDirectory: File, relativePath: String): File {
            val root = rootDirectory.canonicalFile
            val directory = File(root, relativePath).canonicalFile
            require(directory.path.startsWith(root.path + File.separator) && directory.isDirectory) {
                "生成目录不存在或越界"
            }
            return directory
        }

        private fun readBounded(file: File, maximum: Int): ByteArray {
            require(file.isFile && file.canRead()) { "生成文件不存在：${file.name}" }
            require(file.length() in 1..maximum.toLong()) { "生成文件大小无效：${file.name}" }
            return file.readBytes().also {
                require(it.size <= maximum) { "生成文件超过大小上限：${file.name}" }
            }
        }

        private fun com.google.gson.JsonObject.requiredString(name: String): String =
            get(name)?.takeIf { it.isJsonPrimitive }?.asString
                ?.takeIf(String::isNotEmpty)
                ?: throw IllegalArgumentException("generation记录缺少$name")

        private fun sha256(bytes: ByteArray): String =
            MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") {
                (it.toInt() and 0xff).toString(16).padStart(2, '0')
            }

        private fun ByteArray.indexOf(needle: ByteArray): Int {
            if (needle.isEmpty()) return 0
            for (start in 0..size - needle.size) {
                if (needle.indices.all { offset -> this[start + offset] == needle[offset] }) return start
            }
            return -1
        }

        private const val MAX_SOURCE_MAP_BYTES = 16 * 1024 * 1024
        private const val MAX_RECORD_BYTES = 64 * 1024
    }
}
