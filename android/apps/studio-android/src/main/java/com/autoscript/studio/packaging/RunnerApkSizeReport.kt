package com.autoscript.studio

import java.io.File
import java.util.Locale
import java.util.zip.ZipFile

/** Counts declared archive sizes without inflating entries or disclosing resource names. */
internal data class RunnerApkSizeReport(
    val apkBytes: Long,
    val components: Map<String, Long>,
    val compressedComponents: Map<String, Long> = emptyMap(),
) {
    val compressedPayloadBytes: Long get() = compressedComponents.values.sum()
    val layoutBytes: Long get() = apkBytes - compressedPayloadBytes
    fun text(): String = buildString {
        appendLine("APK ${mib(apkBytes)} MiB · 以下为条目未压缩大小（不含签名/对齐开销）")
        if (compressedComponents.isNotEmpty()) {
            appendLine("压缩载荷 ${mib(compressedPayloadBytes)} MiB · ZIP头/签名/对齐 ${mib(layoutBytes)} MiB")
        }
        components.forEach { (name, bytes) ->
            appendLine("$name：${mib(bytes)} MiB" + (compressedComponents[name]?.let { "（ZIP ${mib(it)} MiB）" } ?: ""))
        }
    }.trimEnd()

    companion object {
        private fun mib(bytes: Long) = String.format(Locale.ROOT, "%.2f", bytes / 1_048_576.0)
        internal fun category(name: String): String = when {
            name == "assets/packaging/runner-template.apk" -> "内嵌Runner模板"
            name.startsWith("lib/") -> {
                val abi = name.split('/').getOrNull(1) ?: "未知ABI"
                when (name.substringAfterLast('/')) {
                    "libopencv_java4.so" -> "OpenCV · $abi"
                    "libonnxruntime.so", "libonnxruntime4j_jni.so" -> "ONNX Runtime · $abi"
                    else -> "原生库 · $abi"
                }
            }
            name.startsWith("assets/autoscript-release/") -> "项目冻结脚本与资源"
            name.startsWith("assets/") && name.endsWith(".onnx", ignoreCase = true) -> "ONNX模型"
            name.matches(Regex("classes[0-9]*\\.dex")) -> "Android代码"
            else -> "其他资源"
        }
        fun read(file: File, checkpoint: () -> Unit): RunnerApkSizeReport {
            require(file.length() in 1..1_073_741_824L)
            val components = sortedMapOf<String, Long>()
            val compressed = sortedMapOf<String, Long>()
            val names = HashSet<String>()
            var entries = 0
            var total = 0L
            ZipFile(file).use { archive ->
                val enumeration = archive.entries()
                while (enumeration.hasMoreElements()) {
                    checkpoint()
                    val entry = enumeration.nextElement()
                    require(++entries <= 10_000 && entry.size in 0..268_435_456L &&
                        entry.compressedSize in 0..268_435_456L && names.add(entry.name))
                    total += entry.size
                    require(total <= 1_073_741_824L)
                    val category = category(entry.name)
                    components[category] = (components[category] ?: 0L) + entry.size
                    compressed[category] = (compressed[category] ?: 0L) + entry.compressedSize
                }
            }
            checkpoint()
            require(compressed.values.sum() <= file.length()) { "ZIP载荷超过APK大小" }
            return RunnerApkSizeReport(file.length(), components, compressed)
        }
    }
}
