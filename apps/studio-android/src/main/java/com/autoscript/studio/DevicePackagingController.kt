package com.autoscript.studio

import android.content.Context
import android.system.Os
import android.system.OsConstants
import com.autoscript.project.store.ProjectSnapshot
import com.autoscript.project.store.ProjectSourceMode
import com.autoscript.project.store.ProjectStore
import com.autoscript.studio.compiler.ReleasePreparation
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

internal data class DeviceApkPackageResult(val file: File, val releaseId: String, val certificateSha256: String, val upgradeWarning: String?)

/** One bounded device build. Existing packages and project sources are never overwritten. */
internal class DevicePackagingController(context: Context) {
    private val appContext = context.applicationContext
    private class Work(val id: Long) { val cancelled = AtomicBoolean(false); val start = System.nanoTime() }

    fun cancel() { active.get()?.let { it.cancelled.set(true); runCatching { ReleasePreparation.cancel(it.id) } } }

    suspend fun build(snapshot: ProjectSnapshot, onProgress: (String) -> Unit): DeviceApkPackageResult {
        val produced = AtomicReference<File?>(null)
        return try {
            withContext(Dispatchers.IO) {
                val work = Work(System.nanoTime().coerceAtLeast(1L))
                check(active.compareAndSet(null, work)) { "已有打包任务正在执行" }
                val coroutine = currentCoroutineContext()
                val checkpoint = {
                    coroutine.ensureActive()
                    if (work.cancelled.get()) throw CancellationException("已取消打包")
                    check(System.nanoTime() - work.start < 120_000_000_000L) { "设备打包超过120秒，请缩减资源后重试" }
                }
                val staging = File(appContext.cacheDir, "device-apk-${UUID.randomUUID()}")
                var committed: File? = null
                try {
                    check(staging.mkdirs()) { "无法创建打包暂存目录" }
                    checkpoint()
                    onProgress("正在冻结项目配置、Lua/插件和已声明资源")
                    val project = File(staging, "project")
                    snapshotProject(snapshot, project, checkpoint)
                    val release = File(staging, "release")
                    val preparation = JSONObject(ReleasePreparation.prepare(work.id, project.path, release.path))
                    checkpoint()
                    check(preparation.optString("status") == "success") { "发布冻结失败；请先检查脚本、插件连接和资源声明" }
                    val manifest = preparation.getJSONObject("release")
                    require(manifest.getString("applicationId") == APPLICATION_ID && manifest.getInt("versionCode") == VERSION_CODE &&
                        manifest.getString("versionName") == VERSION_NAME && manifest.getString("projectId") == snapshot.manifest.projectId)
                    val releaseId = manifest.getString("releaseId").also { require(it.matches(Regex("[0-9a-f]{64}"))) }
                    onProgress("正在载入独立 Runner 模板")
                    val template = File(staging, "runner-template.apk")
                    val metadata = JSONObject(appContext.assets.open("packaging/runner-template.json").use { it.readBytes().toString(Charsets.UTF_8) })
                    require(metadata.getInt("formatVersion") == 1 && metadata.getString("applicationId") == APPLICATION_ID &&
                        metadata.getInt("versionCode") == VERSION_CODE && metadata.getString("versionName") == VERSION_NAME)
                    val templateSize = metadata.getLong("size").also { require(it in 1..MAX_TEMPLATE_BYTES) }
                    val releaseSize = release.walkTopDown().filter { it.isFile }.sumOf { it.length() }
                    require(templateSize + releaseSize + 1_048_576L <= 1_073_741_824L) { "模板与发布资源超过 APK 大小预算" }
                    require(staging.usableSpace >= templateSize + 2 * (templateSize + releaseSize) + 16_777_216L) { "设备剩余空间不足，请至少预留三份 APK 的暂存空间" }
                    val hash = MessageDigest.getInstance("SHA-256")
                    var size = 0L
                    appContext.assets.open("packaging/runner-template.apk").use { input -> FileOutputStream(template).use { output ->
                        val buffer = ByteArray(65_536)
                        while (true) { checkpoint(); val count = input.read(buffer); if (count < 0) break
                            size += count; require(size <= MAX_TEMPLATE_BYTES); output.write(buffer, 0, count); hash.update(buffer, 0, count) }
                    } }
                    require(size == templateSize && hash.digest().hex() == metadata.getString("sha256")) { "Runner 模板摘要无效" }
                    @Suppress("DEPRECATION") val info = requireNotNull(appContext.packageManager.getPackageArchiveInfo(template.path, 0))
                    @Suppress("DEPRECATION") require(info.packageName == APPLICATION_ID && info.versionCode == VERSION_CODE && info.versionName == VERSION_NAME)
                    onProgress("正在重建 APK；原生库按16KB对齐")
                    val unsigned = File(staging, "unsigned.apk")
                    RunnerApkArchive.rebuild(template, release, unsigned, checkpoint)
                    onProgress("正在使用设备私有密钥生成 APK v2 签名")
                    checkpoint()
                    val identity = DeviceApkSigningKey.loadOrCreate()
                    checkpoint()
                    val signed = File(staging, "signed.apk")
                    ApkV2Signer.sign(unsigned, signed, identity, checkpoint)
                    RunnerApkArchive.verify(signed, checkpoint)
                    val signature = ApkV2Signer.verify(signed, checkpoint)
                    require(signature.certificateSha256 == MessageDigest.getInstance("SHA-256").digest(identity.certificate.encoded).hex()) { "APK 签名身份与设备密钥不一致" }
                    val upgradeWarning = installedUpgradeWarning(signature.certificateSha256)
                    checkpoint()
                    val packages = File(appContext.filesDir, "packages").also { require(it.mkdirs() || it.isDirectory) }
                    val destination = File(packages, "${snapshot.manifest.projectId}-${releaseId.take(12)}-${UUID.randomUUID()}.apk")
                    require(!destination.exists() && signed.renameTo(destination)) { "无法提交已验签 APK" }
                    committed = destination
                    produced.set(destination)
                    checkpoint()
                    onProgress("APK 已生成并通过内容摘要、签名和 ABI 对齐校验")
                    DeviceApkPackageResult(destination, releaseId, signature.certificateSha256, upgradeWarning)
                } catch (error: Throwable) {
                    committed?.delete()
                    throw error
                } finally {
                    val root = appContext.cacheDir.canonicalFile
                    if (staging.canonicalPath.startsWith(root.path + File.separator) && staging.name.startsWith("device-apk-")) staging.deleteRecursively()
                    active.compareAndSet(work, null)
                }
            }
        } catch (error: Throwable) {
            // withContext may discard a completed IO result when its UI continuation is cancelled.
            produced.get()?.delete()
            throw error
        }
    }

    @Suppress("DEPRECATION")
    private fun installedUpgradeWarning(certificateSha256: String): String? {
        val installed = try {
            appContext.packageManager.getPackageInfo(APPLICATION_ID, android.content.pm.PackageManager.GET_SIGNATURES)
        } catch (_: android.content.pm.PackageManager.NameNotFoundException) { return null }
        val signatures = installed.signatures.orEmpty()
        return if (signatures.size == 1 && MessageDigest.getInstance("SHA-256").digest(signatures[0].toByteArray()).hex() == certificateSha256) null
        else "已安装 Runner 的证书不同，不能原位升级；APK 已保留，智构不会自动卸载或清除任何应用数据"
    }

    private fun snapshotProject(snapshot: ProjectSnapshot, target: File, checkpoint: () -> Unit) {
        require(target.mkdirs())
        val root = snapshot.directory.canonicalFile
        val manifestFile = File(root, "project.json")
        require(OsConstants.S_ISREG(Os.lstat(manifestFile.path).st_mode))
        require(manifestFile.length() in 1..1_048_576)
        val manifestBytes = readBounded(manifestFile, 1_048_576, checkpoint)
        require(ProjectStore(requireNotNull(root.parentFile)).matchesManifestSnapshot(snapshot, manifestBytes)) { "项目配置已变化，请重新打开项目后打包" }
        File(target, "project.json").writeBytes(manifestBytes)
        var total = manifestBytes.size.toLong()
        fun source(path: String, text: String) {
            checkpoint()
            val file = projectFileWithinRoot(target, path)
            require(file.parentFile?.mkdirs() == true || file.parentFile?.isDirectory == true)
            val bytes = text.toByteArray(Charsets.UTF_8)
            require(bytes.size <= if (snapshot.manifest.sourceMode == ProjectSourceMode.LUA) 16_777_216 else 67_108_864)
            val saved = projectFileWithinRoot(root, path)
            rejectSymlinks(root, path)
            require(OsConstants.S_ISREG(Os.lstat(saved.path).st_mode) && saved.length() == bytes.size.toLong()) { "项目源码已变化，请重新打开项目后打包" }
            saved.inputStream().use { input ->
                val buffer = ByteArray(65_536); var offset = 0
                while (true) {
                    checkpoint(); val count = input.read(buffer); if (count < 0) break
                    require(offset + count <= bytes.size && (0 until count).all { buffer[it] == bytes[offset + it] }) { "项目源码已变化，请重新打开项目后打包" }
                    offset += count
                }
                require(offset == bytes.size) { "项目源码已变化，请重新打开项目后打包" }
            }
            total += bytes.size; require(total <= 536_870_912L)
            file.writeBytes(bytes)
        }
        if (snapshot.manifest.sourceMode == ProjectSourceMode.LUA) snapshot.manifest.luaFiles.forEach { path ->
            source(path, snapshot.luaSources[path] ?: if (path == snapshot.manifest.entryPoint) requireNotNull(snapshot.luaSource) else error("Lua 文件未加载"))
        } else snapshot.manifest.flows.forEach { flow -> source(flow.path, requireNotNull(snapshot.flowSources[flow.flowId])) }
        snapshot.manifest.resources.forEach { resource ->
            checkpoint()
            val path = resource.get("path").asString
            val inputFile = projectFileWithinRoot(root, path)
            rejectSymlinks(root, path)
            require(OsConstants.S_ISREG(Os.lstat(inputFile.path).st_mode)) { "资源不是普通文件" }
            val maximum = if (resource.get("kind").asString == "image") 33_554_432L else 8_388_608L
            require(inputFile.length() in 1..maximum)
            val outputFile = projectFileWithinRoot(target, path)
            require(outputFile.parentFile?.mkdirs() == true || outputFile.parentFile?.isDirectory == true)
            inputFile.inputStream().use { input -> FileOutputStream(outputFile).use { output ->
                val buffer = ByteArray(65_536); var size = 0L
                while (true) { checkpoint(); val count = input.read(buffer); if (count < 0) break
                    size += count; total += count; require(size <= maximum && total <= 536_870_912L); output.write(buffer, 0, count) }
                require(size > 0L)
            } }
        }
        checkpoint()
        require(manifestFile.length() == manifestBytes.size.toLong() && readBounded(manifestFile, 1_048_576, checkpoint).contentEquals(manifestBytes)) { "项目配置在冻结期间变化，请重新打开项目后打包" }
    }

    private fun rejectSymlinks(root: File, path: String) {
        var checked = File(root, path)
        while (checked != root) {
            require(!OsConstants.S_ISLNK(Os.lstat(checked.path).st_mode)) { "项目文件不能是符号链接" }
            checked = requireNotNull(checked.parentFile)
        }
    }

    private fun readBounded(file: File, maximum: Int, checkpoint: () -> Unit): ByteArray {
        val bytes = ByteArrayOutputStream()
        file.inputStream().use { input ->
            val buffer = ByteArray(65_536)
            while (true) {
                checkpoint(); val count = input.read(buffer); if (count < 0) break
                require(bytes.size() <= maximum - count) { "项目清单超过大小预算" }
                bytes.write(buffer, 0, count)
            }
        }
        return bytes.toByteArray()
    }
    companion object {
        private val active = AtomicReference<Work?>(null)
        const val APPLICATION_ID = "com.autoscript.runner"
        const val VERSION_CODE = 1
        const val VERSION_NAME = "0.1.0"
        const val DISPLAY_NAME = "自动化Runner"
        const val MAX_TEMPLATE_BYTES = 536_870_912L
    }
}
