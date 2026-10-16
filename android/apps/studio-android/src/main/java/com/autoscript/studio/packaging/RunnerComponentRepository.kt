package com.autoscript.studio

import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest

internal class RunnerComponentRepository(context: Context) {
    private val app = context.applicationContext

    @Suppress("DEPRECATION")
    private fun store(): RunnerComponentStore {
        val publisher = app.packageManager.getPackageInfo(app.packageName, PackageManager.GET_SIGNATURES).signatures.orEmpty()
        require(publisher.size == 1) { "无法确认单一智构发行证书" }
        val trust = setOf(MessageDigest.getInstance("SHA-256").digest(publisher.single().toByteArray()).hex())
        return RunnerComponentStore(File(app.filesDir.canonicalFile, "runner-components"), trust) { file ->
            val info = requireNotNull(app.packageManager.getPackageArchiveInfo(file.path, 0)) { "无法读取组件 Android 清单" }
            RunnerComponentIdentity(info.packageName, info.versionCode, info.versionName, requireNotNull(info.applicationInfo).minSdkVersion)
        }
    }

    suspend fun inventory() = operation { checkpoint -> store().inventory(checkpoint) }
    suspend fun remove(id: String) = operation { checkpoint -> store().remove(id, checkpoint) }
    suspend fun import(uri: Uri): VerifiedRunnerComponent = importOfflineComponent(app, uri) { open, checkpoint -> store().import(open, checkpoint) }

    // Called only inside the device packager's IO transaction and deadline.
    fun freeze(id: String, family: RunnerTemplateFamily, target: File, checkpoint: () -> Unit) =
        store().freeze(id, family, target, checkpoint)

    private suspend fun <T> operation(action: (() -> Unit) -> T): T = withContext(Dispatchers.IO) {
        val coroutine = currentCoroutineContext()
        val started = System.nanoTime()
        action {
            coroutine.ensureActive()
            check(System.nanoTime() - started < 120_000_000_000L) { "组件检查超时，请使用本地文件重试" }
        }
    }

}
