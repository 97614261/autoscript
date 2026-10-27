package com.autoscript.studio

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

internal const val AUTO_UPDATE_INTERVAL_MS = 6L * 60 * 60 * 1000
internal fun studioAutoUpdateDue(now: Long, last: Long): Boolean = last <= 0 || now < last || now - last >= AUTO_UPDATE_INTERVAL_MS

internal data class StudioUpdateState(
    val source: String, val automatic: Boolean, val foreground: Boolean = false,
    val checking: Boolean = false, val downloading: Boolean = false, val verifying: Boolean = false,
    val release: StudioUpdateRelease? = null, val site: StudioUpdateSite? = null,
    val message: String? = null, val prompt: Boolean = false, val downloaded: Long = 0,
    val ready: Boolean = false, val installRequested: Boolean = false, val needsPermission: Boolean = false,
    val epoch: Long = 0,
) { val busy: Boolean get() = checking || downloading || verifying }

/** One root-owned foreground session shared by startup checking and the About card. */
internal class StudioUpdateController(context: Context, private val scope: CoroutineScope) : AutoCloseable {
    private val app = context.applicationContext
    private val preferences = app.getSharedPreferences("studio-update", 0)
    private val mutable = MutableStateFlow(StudioUpdateState(
        preferences.getString("site", BuildConfig.UPDATE_SITE).orEmpty(), preferences.getBoolean("automatic", true)))
    val state = mutable.asStateFlow()
    // Android may expose the trusted cache root through /data/user/0 -> /data/data.
    private val directory = File(app.cacheDir.canonicalFile, "studio-updates")
    private var job: Job? = null
    private var readyFile: File? = null
    private var automaticCheck = false

    fun setSource(value: String) {
        if (state.value.busy) return
        mutable.update { it.copy(source = value.take(512), release = null, site = null, ready = false, message = null, prompt = false) }
    }
    fun setAutomatic(enabled: Boolean) {
        preferences.edit().putBoolean("automatic", enabled).apply()
        mutable.update { it.copy(automatic = enabled) }
        if (!enabled && automaticCheck && state.value.checking) cancel()
    }
    fun foreground() {
        val permissionGranted = Build.VERSION.SDK_INT < 26 || app.packageManager.canRequestPackageInstalls()
        mutable.update { it.copy(foreground = true, needsPermission = it.needsPermission && !permissionGranted) }
        val value = state.value
        if (!value.automatic || value.busy || value.prompt || value.ready) return
        val site = runCatching { StudioUpdateSite.parse(value.source.trim()) }.getOrNull() ?: return
        val key = "${site.origin}|${BuildConfig.VERSION_CODE}"
        val now = System.currentTimeMillis()
        if (preferences.getString("last-auto-key", null) != key || studioAutoUpdateDue(now, preferences.getLong("last-auto-at", 0))) {
            check(manual = false)
        }
    }
    fun background() {
        mutable.update { it.copy(foreground = false) }
        if (state.value.busy) cancel()
    }
    fun cancel() {
        job?.cancel(); job = null
        mutable.update { it.copy(checking = false, downloading = false, verifying = false, epoch = it.epoch + 1,
            installRequested = false, message = "更新操作已取消，未安装，也未修改项目") }
    }
    fun dismiss() {
        if (state.value.busy) cancel()
        mutable.update { it.copy(prompt = false, installRequested = false) }
    }
    fun check(manual: Boolean = true) {
        if (state.value.busy) return
        val site = runCatching { StudioUpdateSite.parse(state.value.source.trim()) }.getOrNull()
        if (site == null) { mutable.update { it.copy(message = "请输入站点根地址，不含账号、路径或参数") }; return }
        preferences.edit().putString("site", site.origin.toASCIIString()).apply()
        automaticCheck = !manual
        val epoch = state.value.epoch + 1
        mutable.update { it.copy(source = site.origin.toASCIIString(), checking = true, epoch = epoch,
            release = null, site = null, ready = false, needsPermission = false, message = "正在检查更新…") }
        job = scope.launch {
            var completed = false
            try {
                val release = StudioUpdateClient.check(site, Build.SUPPORTED_ABIS.toList(), Build.VERSION.SDK_INT)
                val relation = release?.let { studioUpdateRelation(it, BuildConfig.VERSION_CODE) }
                mutable.update { if (it.epoch != epoch) it else it.copy(release = release, site = site,
                    prompt = relation == StudioUpdateRelation.NEWER && it.foreground,
                    message = when (relation) {
                        StudioUpdateRelation.NEWER -> "有新版本可用，确认后开始下载，安装仍需系统确认"
                        StudioUpdateRelation.SAME_VERSION -> "当前已是最新版本 ${BuildConfig.VERSION_NAME}"
                        StudioUpdateRelation.SITE_OLDER -> "当前版本比下载站更新，无需更新"
                        null -> "站点尚未提供适合此设备的安装包"
                    }) }
                completed = true
            } catch (_: TimeoutCancellationException) {
                mutable.update { if (it.epoch != epoch) it else it.copy(message = "检查超时，可稍后手动重试") }
                completed = true
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { mutable.update { if (it.epoch != epoch) it else it.copy(message =
                if (manual) "检查失败，请核对网络和站点发布清单" else "自动检查未完成，可在这里手动重试") }; completed = true }
            finally {
                if (completed && state.value.epoch == epoch) preferences.edit()
                    .putString("last-auto-key", "${site.origin}|${BuildConfig.VERSION_CODE}")
                    .putLong("last-auto-at", System.currentTimeMillis()).apply()
                mutable.update { if (it.epoch != epoch) it else it.copy(checking = false) }
            }
        }
    }
    fun showPrompt() { if (state.value.release != null) mutable.update { it.copy(prompt = true) } }
    fun download() {
        val snapshot = state.value
        val release = snapshot.release ?: return
        val site = snapshot.site ?: return
        if (snapshot.busy || studioUpdateRelation(release, BuildConfig.VERSION_CODE) != StudioUpdateRelation.NEWER) return
        if (release.bytes > MAX_STUDIO_UPDATE_BYTES) {
            mutable.update { it.copy(message = "安装包超过 256 MiB，请使用下载页") }; return
        }
        val epoch = snapshot.epoch + 1
        mutable.update { it.copy(downloading = true, ready = false, epoch = epoch, prompt = true,
            downloaded = 0, needsPermission = false, message = "正在下载更新…") }
        job = scope.launch {
            try {
                val file = StudioUpdateDownload.download(site, release, directory,
                    verify = { apk, checkpoint ->
                        mutable.update { if (it.epoch != epoch) it else it.copy(downloading = false, verifying = true,
                            downloaded = release.bytes, message = "下载完成，正在校验完整性和安装签名…") }
                        verifyStudioUpdateApk(app, apk, release, checkpoint)
                    },
                    progress = { bytes -> mutable.update { if (it.epoch != epoch) it else it.copy(downloaded = bytes) } })
                readyFile = file
                mutable.update { if (it.epoch != epoch) it else it.copy(ready = true,
                    installRequested = it.foreground, message = "安装包校验通过，正在准备系统安装确认") }
            } catch (_: TimeoutCancellationException) {
                mutable.update { if (it.epoch != epoch) it else it.copy(message = "下载超时，不完整文件不会安装，请重试") }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { mutable.update { if (it.epoch != epoch) it else it.copy(message =
                "下载或校验失败，未交给安装器；请重试或使用下载页") } }
            finally { mutable.update { if (it.epoch != epoch) it else it.copy(downloading = false, verifying = false) } }
        }
    }
    fun install(context: Context, allowed: () -> Boolean) {
        mutable.update { it.copy(installRequested = false) }
        val snapshot = state.value
        if (!snapshot.ready || snapshot.busy || !snapshot.foreground) return
        if (!allowed()) { mutable.update { it.copy(message = "请先保存编辑并停止脚本，再点“确认安装”") }; return }
        if (Build.VERSION.SDK_INT >= 26 && !app.packageManager.canRequestPackageInstalls()) {
            mutable.update { it.copy(needsPermission = true, message = "请点“去授权”，在系统中允许智构安装应用；返回后点“确认安装”") }; return
        }
        val file = readyFile ?: return
        val release = snapshot.release ?: return
        val epoch = snapshot.epoch + 1
        mutable.update { it.copy(verifying = true, epoch = epoch, needsPermission = false, message = "正在复核安装包…") }
        job = scope.launch {
            try {
                StudioUpdateClient.perform(30_000L) { request -> StudioUpdateDownload.withCacheLease(directory) {
                    StudioUpdateDownload.verifyBytes(file, release, request::checkpoint)
                    verifyStudioUpdateApk(app, file, release, request::checkpoint)
                } }
                if (!state.value.foreground || state.value.epoch != epoch) return@launch
                if (!allowed()) {
                    mutable.update { it.copy(message = "脚本已开始运行，请停止并保存后再安装") }
                    return@launch
                }
                val uri = FileProvider.getUriForFile(app, "${app.packageName}.packages", file)
                context.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
                mutable.update { it.copy(message = "已打开系统安装器；安装结果以系统提示为准") }
            } catch (_: TimeoutCancellationException) {
                mutable.update { if (it.epoch != epoch) it else it.copy(message = "安装包复核超时，请重试") }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { mutable.update { if (it.epoch != epoch) it else it.copy(message =
                "无法校验或打开安装器，请重新下载；不会卸载旧版或清除数据") } }
            finally { mutable.update { if (it.epoch != epoch) it else it.copy(verifying = false) } }
        }
    }
    fun openInstallPermission(context: Context) {
        if (Build.VERSION.SDK_INT < 26) return
        runCatching { context.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${app.packageName}"))) }
            .onFailure { mutable.update { it.copy(message = "无法打开安装来源设置，请到系统应用设置中手动允许") } }
    }
    override fun close() {
        cancel()
        readyFile = null
        mutable.update { it.copy(foreground = false, prompt = false, ready = false, needsPermission = false) }
    }
}
