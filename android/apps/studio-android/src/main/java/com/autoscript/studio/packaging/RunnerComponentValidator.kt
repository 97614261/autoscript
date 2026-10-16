package com.autoscript.studio

import java.io.File
import java.io.InputStream
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.ZipFile

internal data class RunnerComponentIdentity(val applicationId: String, val versionCode: Int, val versionName: String?, val minSdk: Int)
internal data class VerifiedRunnerComponent(val sha256: String, val size: Long, val descriptor: RunnerComponentDescriptor)

internal object RunnerComponentValidator {
    fun validate(file: File, trustedCertificates: Set<String>, identity: (File) -> RunnerComponentIdentity,
        checkpoint: () -> Unit): VerifiedRunnerComponent {
        checkpoint()
        require(file.isFile && file.length() in 1..DevicePackagingController.MAX_TEMPLATE_BYTES) { "组件文件超过预算" }
        require(trustedCertificates.isNotEmpty()) { "无法读取智构发行证书" }
        require(ApkV2Signer.verify(file, checkpoint).certificateSha256 in trustedCertificates) { "组件签名不是当前智构发行证书" }
        val descriptor = ZipFile(file).use { zip ->
            val entries = zip.entries().asSequence().toList()
            require(entries.size in 1..10_000 && entries.map { it.name }.distinct().size == entries.size)
            var total = 0L
            entries.forEach {
                checkpoint()
                val parts = it.name.removeSuffix("/").split('/')
                require(it.name.length in 1..1024 && '\\' !in it.name && '\u0000' !in it.name && parts.all { part -> part.isNotEmpty() && part != "." && part != ".." })
                require(it.size in 0..268_435_456L); total += it.size; require(total <= 1_073_741_824L)
                require(!it.name.startsWith("assets/autoscript-release/") && !it.name.contains("studio_compiler")) { "模板包含项目或编辑器内容" }
            }
            val entry = requireNotNull(zip.getEntry(RunnerComponentDescriptor.PATH)) { "缺少签名内组件描述" }
            require(entry.size in 1..RunnerComponentDescriptor.MAX_BYTES.toLong())
            val descriptor = zip.getInputStream(entry).use { input ->
                val bytes = ByteArrayOutputStream().also { output ->
                    val buffer = ByteArray(1024)
                    while (true) {
                        checkpoint(); val count = input.read(buffer); if (count < 0) break
                        require(output.size() + count <= RunnerComponentDescriptor.MAX_BYTES)
                        output.write(buffer, 0, count)
                    }
                }.toByteArray()
                require(bytes.size.toLong() == entry.size)
                RunnerComponentDescriptor.parse(bytes)
            }
            val names = entries.map { it.name }.toSet()
            require(RunnerApkArchive.hasRuntimeContent(names, descriptor.family)) { "模板原生库组合不匹配" }
            require(descriptor.abis == names.filter { it.startsWith("lib/") && !it.endsWith('/') }.map { it.split('/')[1] }.toSet()) { "模板 ABI 与描述不匹配" }
            entries.filter { it.name.startsWith("lib/") && !it.isDirectory }.forEach { library ->
                checkpoint()
                require(library.name.split('/').size == 3 && library.name.endsWith(".so") && library.size >= 20)
                val header = ByteArray(20)
                zip.getInputStream(library).use { input ->
                    var offset = 0
                    while (offset < header.size) { val read = input.read(header, offset, header.size - offset); require(read > 0); offset += read }
                }
                require(header.take(6) == listOf(0x7F.toByte(), 69.toByte(), 76.toByte(), 70.toByte(), 2.toByte(), 1.toByte())) { "模板原生库不是64位小端 ELF" }
                val machine = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN).getShort(18).toInt() and 0xFFFF
                require(machine == if (library.name.startsWith("lib/arm64-v8a/")) 183 else 62) { "模板原生库 ABI 内容不匹配" }
            }
            val cv = descriptor.family in setOf(RunnerTemplateFamily.OPENCV, RunnerTemplateFamily.FULL)
            val onnx = descriptor.family in setOf(RunnerTemplateFamily.ONNX, RunnerTemplateFamily.FULL)
            require(("assets/vision/OpenCV-LICENSE.txt" in names) == cv && (RunnerComponentDescriptor.MODEL_PATH in names) == onnx)
            val licenses = buildList {
                if (cv) add("assets/vision/OpenCV-LICENSE.txt")
                if (onnx) { add("assets/vision/ONNXRuntime-LICENSE.txt"); add("assets/vision/PaddleOCR-LICENSE.txt"); add("assets/vision/NOTICE.txt") }
            }
            licenses.forEach { require(zip.getEntry(it).size in 1..1_048_576) { "组件许可文件缺失" } }
            if (onnx) {
                val model = zip.getEntry(RunnerComponentDescriptor.MODEL_PATH)
                require(model.size == 7_653_044L)
                require(zip.getInputStream(model).use { hash(it, model.size, checkpoint).second } == RunnerComponentDescriptor.MODEL_SHA256) { "模型内容摘要不匹配" }
            }
            descriptor
        }
        val manifest = identity(file)
        require(manifest == RunnerComponentIdentity(DevicePackagingController.APPLICATION_ID, DevicePackagingController.VERSION_CODE,
            DevicePackagingController.VERSION_NAME, 24)) { "实际 Android 清单与组件身份不匹配" }
        val (size, sha) = file.inputStream().use { hash(it, DevicePackagingController.MAX_TEMPLATE_BYTES, checkpoint) }
        require(size == file.length()) { "组件读取期间发生变化" }
        checkpoint()
        return VerifiedRunnerComponent(sha, size, descriptor)
    }

    fun hash(input: InputStream, maximum: Long, checkpoint: () -> Unit): Pair<Long, String> {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(65_536)
        var size = 0L
        while (true) {
            checkpoint(); val count = input.read(buffer); if (count < 0) break
            size += count; require(size <= maximum) { "组件超过大小预算" }
            digest.update(buffer, 0, count)
        }
        return size to digest.digest().hex()
    }
}
