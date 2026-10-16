package com.autoscript.studio

import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.util.UUID
import java.util.concurrent.CancellationException
import java.util.concurrent.locks.ReentrantLock

internal class RunnerComponentCompatibilityException : IllegalArgumentException("组件版本、运行协议或 ABI 不兼容")
internal enum class RunnerComponentState { READY, INCOMPATIBLE, CORRUPT }
internal data class RunnerComponentEntry(val id: String, val verified: VerifiedRunnerComponent?, val problem: String?,
    val state: RunnerComponentState = RunnerComponentState.READY)

/** Immutable private packaging artifacts, not an executable runtime plugin directory. */
internal class RunnerComponentStore(
    private val directory: File,
    private val trustedCertificates: Set<String>,
    private val identity: (File) -> RunnerComponentIdentity,
) {
    fun inventory(checkpoint: () -> Unit): List<RunnerComponentEntry> = locked {
        files().filter { it.name.matches(Regex("[0-9a-f]{64}\\.apk")) }.map { file ->
            checkpoint()
            try {
                val verified = validate(file, checkpoint)
                require(file.name == "${verified.sha256}.apk")
                RunnerComponentEntry(verified.sha256, verified, null)
            } catch (error: Exception) {
                if (error is CancellationException || error is InterruptedException) throw error
                checkpoint()
                val incompatible = error is RunnerComponentCompatibilityException
                RunnerComponentEntry(file.name.removeSuffix(".apk"), null,
                    if (incompatible) "组件版本、运行协议或 ABI 不兼容" else "签名、描述或内容校验失败",
                    if (incompatible) RunnerComponentState.INCOMPATIBLE else RunnerComponentState.CORRUPT)
            }
        }
    }

    fun import(open: () -> InputStream, checkpoint: () -> Unit): VerifiedRunnerComponent = locked {
        files()
        val staging = File(directory, "incoming-${UUID.randomUUID()}.part")
        require(staging.createNewFile())
        try {
            open().use { input -> FileOutputStream(staging).use { output ->
                val buffer = ByteArray(65_536); var size = 0L
                while (true) {
                    checkpoint(); val count = input.read(buffer); if (count < 0) break
                    size += count
                    require(size <= DevicePackagingController.MAX_TEMPLATE_BYTES) { "组件超过512MiB预算" }
                    require(directory.usableSpace > count + 16_777_216L) { "组件暂存空间不足" }
                    output.write(buffer, 0, count)
                }
                output.flush(); output.fd.sync()
            } }
            val verified = validate(staging, checkpoint)
            val target = File(directory, "${verified.sha256}.apk")
            if (target.exists()) {
                require(validate(target, checkpoint) == verified) { "已有同名组件内容无效，不会覆盖" }
                return@locked verified
            }
            val existing = files().filter { it != staging }
            require(existing.size < 8 && existing.sumOf { it.length() } + verified.size <= 1_073_741_824L) { "最多8个组件、总计1GiB；已有文件不会自动删除" }
            checkpoint()
            require(staging.renameTo(target)) { "组件原子提交失败" }
            verified
        } finally { staging.delete() }
    }

    fun freeze(id: String, family: RunnerTemplateFamily, target: File, checkpoint: () -> Unit): VerifiedRunnerComponent = locked {
        require(id.matches(Regex("[0-9a-f]{64}")) && !target.exists())
        val source = File(directory, "$id.apk")
        require(files().contains(source)) { "选定组件已不存在" }
        require((target.parentFile?.usableSpace ?: 0L) >= source.length() + 16_777_216L) { "组件冻结暂存空间不足" }
        require(target.createNewFile())
        try {
            source.inputStream().use { input -> FileOutputStream(target).use { output ->
                val buffer = ByteArray(65_536); var size = 0L
                while (true) {
                    checkpoint(); val count = input.read(buffer); if (count < 0) break
                    size += count; require(size <= DevicePackagingController.MAX_TEMPLATE_BYTES)
                    output.write(buffer, 0, count)
                }
                output.flush(); output.fd.sync()
            } }
            val verified = validate(target, checkpoint)
            require(verified.sha256 == id && verified.descriptor.family == family) { "组件内容或所需组合已变化" }
            checkpoint()
            verified
        } catch (error: Throwable) { target.delete(); throw error }
    }

    fun remove(id: String, checkpoint: () -> Unit) = locked {
        require(id.matches(Regex("[0-9a-f]{64}")))
        val target = File(directory, "$id.apk")
        require(files().contains(target)) { "组件已不存在，请刷新列表" }
        checkpoint()
        require(target.delete()) { "组件删除失败，原文件保留" }
    }

    private fun validate(file: File, checkpoint: () -> Unit) = RunnerComponentValidator.validate(file, trustedCertificates, identity, checkpoint)

    private fun files(): List<File> {
        require(directory.mkdirs() || directory.isDirectory)
        require(directory.absoluteFile == directory.canonicalFile) { "组件目录不能是链接" }
        val children = requireNotNull(directory.listFiles()).toList()
        require(children.size <= 32) { "组件目录超过文件数量预算" }
        require(children.all { it.absoluteFile == it.canonicalFile && it.isFile }) { "组件目录包含未知文件结构；保留原数据" }
        require(children.sumOf { it.length() } <= 1_610_612_736L) { "组件目录超过读取预算" }
        return children
    }

    private fun <T> locked(action: () -> T): T {
        check(lock.tryLock()) { "组件正在读取或打包，请稍后重试" }
        return try { action() } finally { lock.unlock() }
    }
    companion object { private val lock = ReentrantLock() }
}
