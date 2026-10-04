package com.autoscript.studio

import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/** Rebuild actual compiled Android content; never treats a project ZIP as an APK. */
internal object RunnerApkArchive {
    private const val MAX_APK = 1_073_741_824L
    private const val MAX_ENTRY = 268_435_456L
    private const val RELEASE = "assets/autoscript-release/"
    private val REQUIRED = setOf("AndroidManifest.xml", "resources.arsc", "classes.dex")
    private val NATIVE_RUNTIME = setOf("libengine_jni.so", "libautoscript_root_daemon.so",
        "libopencv_java4.so", "libonnxruntime.so", "libonnxruntime4j_jni.so", "libc++_shared.so")

    internal fun hasRuntimeContent(names: Set<String>): Boolean {
        if (!names.containsAll(REQUIRED)) return false
        val abis = names.filter { it.startsWith("lib/") }.map { it.split('/').getOrNull(1) }.toSet()
        if ("arm64-v8a" !in abis || abis.any { it !in setOf("arm64-v8a", "x86_64") }) return false
        return abis.all { abi -> NATIVE_RUNTIME.all { "lib/$abi/$it" in names } }
    }
    private class Counter(output: OutputStream) : OutputStream() {
        private val delegate = output
        var count = 0L
        override fun write(value: Int) { delegate.write(value); count++ }
        override fun write(bytes: ByteArray, offset: Int, size: Int) { delegate.write(bytes, offset, size); count += size; require(count <= MAX_APK) }
        override fun flush() = delegate.flush()
        override fun close() = delegate.close()
    }

    fun rebuild(template: File, releaseDirectory: File, unsigned: File, checkpoint: () -> Unit) {
        require(!unsigned.exists()) { "不能覆盖已有 APK" }
        checkpoint()
        check(unsigned.createNewFile()) { "不能覆盖已有 APK" }
        try {
            val names = HashSet<String>()
            FileOutputStream(unsigned).use { file ->
                val counter = Counter(BufferedOutputStream(file))
                ZipOutputStream(counter).use { output ->
                    fun add(name: String, size: Long, crc: Long, input: () -> InputStream) {
                        checkpoint()
                        require(validName(name) && names.add(name) && names.size <= 10_000 && size in 0..MAX_ENTRY)
                        val nameBytes = name.toByteArray(Charsets.UTF_8)
                        val alignment = if (name.startsWith("lib/") && name.endsWith(".so")) 16_384 else 4
                        val padding = ((alignment - ((counter.count + 30 + nameBytes.size + 6) % alignment)) % alignment).toInt()
                        val extra = ByteBuffer.allocate(padding + 6).order(ByteOrder.LITTLE_ENDIAN)
                            .putShort(0xD935.toShort()).putShort((padding + 2).toShort()).putShort(alignment.toShort()).array()
                        val entry = ZipEntry(name).apply { method = ZipEntry.STORED; this.size = size; compressedSize = size; this.crc = crc; this.extra = extra; time = 946_684_800_000L }
                        output.putNextEntry(entry)
                        require(counter.count % alignment == 0L) { "APK 条目未按要求对齐" }
                        input().use { source ->
                            val bytes = ByteArray(65_536)
                            var total = 0L
                            while (true) {
                                checkpoint()
                                val count = source.read(bytes)
                                if (count < 0) break
                                total += count; require(total <= size)
                                output.write(bytes, 0, count)
                            }
                            require(total == size)
                        }
                        output.closeEntry()
                    }
                    ZipFile(template).use { source ->
                        val entries = source.entries().asSequence().filterNot { it.isDirectory }.toList()
                        require(entries.size in 1..10_000 && entries.map { it.name }.distinct().size == entries.size)
                        require(hasRuntimeContent(entries.map { it.name }.toSet())) { "Runner 模板缺少完整 Android 运行内容" }
                        require(entries.none { it.name.startsWith(RELEASE) || it.name.contains("studio_compiler") }) { "Runner 模板包含项目或编辑器编译器" }
                        entries.filterNot { it.name.startsWith("META-INF/") }.forEach { entry ->
                            add(entry.name, entry.size, entry.crc) { source.getInputStream(entry) }
                        }
                    }
                    val root = releaseDirectory.canonicalFile
                    val payloads = releaseDirectory.walkTopDown().filter { it.isFile }.toList()
                    require(payloads.size in 2..259)
                    payloads.sortedBy { it.relativeTo(releaseDirectory).invariantSeparatorsPath }.forEach { payload ->
                        checkpoint()
                        require(payload.canonicalPath.startsWith(root.path + File.separator))
                        val name = RELEASE + payload.relativeTo(releaseDirectory).invariantSeparatorsPath
                        val crc = CRC32()
                        payload.inputStream().use { input ->
                            val bytes = ByteArray(65_536)
                            while (true) { checkpoint(); val size = input.read(bytes); if (size < 0) break; crc.update(bytes, 0, size) }
                        }
                        add(name, payload.length(), crc.value, payload::inputStream)
                    }
                    output.finish(); output.flush(); file.fd.sync()
                }
            }
            verify(unsigned, checkpoint)
        } catch (error: Throwable) {
            unsigned.delete()
            throw error
        }
    }

    fun verify(apk: File, checkpoint: () -> Unit = {}) {
        ZipFile(apk).use { zip ->
            val entries = zip.entries().asSequence().toList()
            val names = entries.map { it.name }
            require(names.toSet().size == names.size && hasRuntimeContent(names.toSet()))
            require("${RELEASE}release.json" in names && "${RELEASE}payload/main.lua" in names)
            require(names.none { it.contains("studio_compiler") || it.startsWith("META-INF/") })
        }
        RandomAccessFile(apk, "r").use { input ->
            require(input.length() in 22..MAX_APK)
            input.seek(input.length() - 22)
            val end = ByteArray(22).also(input::readFully)
            val eocd = ByteBuffer.wrap(end).order(ByteOrder.LITTLE_ENDIAN)
            require(eocd.int == 0x06054B50)
            val entries = eocd.getShort(10).toInt() and 0xFFFF
            var position = eocd.getInt(16).toLong() and 0xFFFFFFFFL
            repeat(entries) {
                checkpoint()
                input.seek(position)
                val header = ByteArray(46).also(input::readFully)
                val central = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
                require(central.int == 0x02014B50)
                val nameLength = central.getShort(28).toInt() and 0xFFFF
                val extraLength = central.getShort(30).toInt() and 0xFFFF
                val commentLength = central.getShort(32).toInt() and 0xFFFF
                val name = ByteArray(nameLength).also(input::readFully).toString(Charsets.UTF_8)
                position += 46 + nameLength + extraLength + commentLength
                require(validName(name))
                if (name.startsWith("lib/") && name.endsWith(".so") || name == "resources.arsc") {
                    require(central.getShort(10).toInt() == 0) { "原生库与资源表必须不压缩" }
                    val offset = central.getInt(42).toLong() and 0xFFFFFFFFL
                    input.seek(offset)
                    val local = ByteArray(30).also(input::readFully)
                    val localHeader = ByteBuffer.wrap(local).order(ByteOrder.LITTLE_ENDIAN)
                    require(localHeader.int == 0x04034B50 && localHeader.getShort(8).toInt() == 0)
                    val dataOffset = offset + 30 + (localHeader.getShort(26).toInt() and 0xFFFF) + (localHeader.getShort(28).toInt() and 0xFFFF)
                    require(dataOffset % (if (name == "resources.arsc") 4L else 16_384L) == 0L)
                    if (name != "resources.arsc") {
                        input.seek(dataOffset)
                        val elf = ByteArray(20).also(input::readFully)
                        require(elf.take(4) == listOf(0x7F.toByte(), 'E'.code.toByte(), 'L'.code.toByte(), 'F'.code.toByte()))
                        val machine = ByteBuffer.wrap(elf).order(ByteOrder.LITTLE_ENDIAN).getShort(18).toInt() and 0xFFFF
                        require(machine == if (name.startsWith("lib/arm64-v8a/")) 183 else if (name.startsWith("lib/x86_64/")) 62 else -1)
                    }
                }
            }
        }
    }

    private fun validName(name: String) = name.length in 1..1024 && '\\' !in name && '\u0000' !in name &&
        name.split('/').all { it.isNotEmpty() && it != "." && it != ".." }
}
