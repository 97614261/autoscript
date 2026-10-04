package com.autoscript.studio

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.autoscript.core.designsystem.AutoScriptPalette
import com.autoscript.core.model.RuntimeConnectionState
import com.autoscript.core.model.RuntimeRootState
import com.autoscript.project.store.ProjectSnapshot
import com.autoscript.project.store.ProjectStore
import com.autoscript.project.store.ProjectResourceKind
import com.autoscript.runtime.client.RuntimeClient
import com.autoscript.runtime.client.ScreenshotPreviewResult
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import kotlin.math.sqrt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

private const val MAX_TEMPLATE_EDGE = 4_096
private const val MAX_TEMPLATE_PIXELS = 4_194_304L

/**
 * `activity_apk_build.xml` + `page_apk_update.xml` + `page_apk_build_log.xml`：64dp 头、36dp Tab、
 * 四个分区（应用外观 / 安装信息 / 代码与资源保护 / 随包插件）、固定底部“开始打包 APK”。
 * 使用独立Runner模板与设备私有密钥构建真实APK；固定模板属性在界面明确锁定。
 */
@Composable
internal fun ProjectPackageScreen(
    snapshot: ProjectSnapshot,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val projectId = snapshot.manifest.projectId
    val controller = remember(context) { DevicePackagingController(context) }
    val scope = rememberCoroutineScope()
    var packagingJob by remember(projectId) { mutableStateOf<Job?>(null) }
    var busy by remember(projectId) { mutableStateOf(false) }
    var exporting by remember(projectId) { mutableStateOf(false) }
    var result by remember(projectId) { mutableStateOf<DeviceApkPackageResult?>(null) }
    var exportFile by remember(projectId) { mutableStateOf<File?>(null) }
    fun leave() { controller.cancel(); packagingJob?.cancel(); onBack() }
    BackHandler(onBack = ::leave)
    DisposableEffect(controller) { onDispose { controller.cancel() } }
    var selectedTab by remember(projectId) { mutableIntStateOf(0) }
    val appName = DevicePackagingController.DISPLAY_NAME
    val packageName = DevicePackagingController.APPLICATION_ID
    val outputPath = result?.file?.name ?: "生成后选择导出位置"
    var notice by remember(projectId) { mutableStateOf<String?>(null) }
    var logs by remember(projectId) { mutableStateOf<List<String>>(emptyList()) }
    val tabs = listOf("打包", "更新", "日志输出")
    val timestamp = remember { SimpleDateFormat("HH:mm:ss", Locale.getDefault()) }
    val clipboard = LocalClipboardManager.current

    fun appendLog(vararg lines: String) {
        val stamp = timestamp.format(Date())
        logs = (logs + lines.map { "[$stamp] $it" }).takeLast(100)
    }

    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/vnd.android.package-archive")) { uri ->
        val file = exportFile
        if (uri == null || file == null) { exporting = false; exportFile = null }
        else scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val coroutine = currentCoroutineContext()
                    val deadline = System.nanoTime() + 120_000_000_000L
                    FileInputStream(file).use { input -> requireNotNull(context.contentResolver.openOutputStream(uri, "w")).use { output ->
                        val buffer = ByteArray(65_536)
                        while (true) {
                            coroutine.ensureActive(); check(System.nanoTime() < deadline) { "APK 导出超时" }
                            val count = input.read(buffer); if (count < 0) break
                            output.write(buffer, 0, count)
                        }
                        output.flush()
                    } }
                }
                notice = "已导出 APK，可从系统文件管理器安装"
                appendLog("已导出已验签的 Runner APK")
            } catch (error: Exception) {
                withContext(kotlinx.coroutines.NonCancellable + Dispatchers.IO) {
                    // CreateDocument creates a new document; only remove this failed export, never the private APK.
                    runCatching { android.provider.DocumentsContract.deleteDocument(context.contentResolver, uri) }
                }
                if (error is CancellationException) throw error
                notice = "APK 导出未完成，请重新选择位置"
                appendLog("导出失败；私有目录中的完整 APK 已保留")
            } finally { exporting = false; exportFile = null }
        }
    }

    Column(modifier = modifier.fillMaxSize().background(AutoScriptPalette.PageBackground)) {
        Row(
            modifier = Modifier.fillMaxWidth().height(64.dp).padding(start = 12.dp, end = 18.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                painterResource(R.drawable.package_back_20),
                contentDescription = "返回",
                tint = Color.Unspecified,
                modifier = Modifier.size(42.dp).clickable(onClick = ::leave).padding(11.dp),
            )
            Text(
                "打包应用",
                modifier = Modifier.weight(1f).padding(start = 8.dp),
                fontSize = 19.sp,
                fontWeight = FontWeight.Bold,
                color = AutoScriptPalette.TextPrimary,
            )
            Text(
                "APK",
                color = AutoScriptPalette.Accent,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
                modifier = Modifier.size(width = 50.dp, height = 28.dp)
                    .background(AutoScriptPalette.AccentSoft, RoundedCornerShape(9.dp))
                    .padding(top = 7.dp),
            )
        }
        Row(Modifier.fillMaxWidth().height(36.dp).background(AutoScriptPalette.PageBackground)) {
            tabs.forEachIndexed { index, label ->
                Column(Modifier.weight(1f).fillMaxSize().clickable { selectedTab = index }) {
                    Text(
                        label,
                        color = if (selectedTab == index) AutoScriptPalette.Accent else AutoScriptPalette.TextSecondary,
                        fontSize = 12.sp,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.weight(1f).fillMaxWidth().padding(top = 10.dp),
                    )
                    Box(Modifier.fillMaxWidth().height(2.dp).background(if (selectedTab == index) AutoScriptPalette.Accent else Color.Transparent))
                }
            }
        }
        notice?.let {
            Text(it, color = AutoScriptPalette.Accent, fontSize = 11.sp, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp))
        }
        when (selectedTab) {
            0 -> LazyColumn(
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(start = 16.dp, top = 4.dp, end = 16.dp, bottom = 22.dp),
            ) {
                item { PackageSectionTitle("应用外观", first = true) }
                item {
                    PackageCard {
                        Row(Modifier.fillMaxWidth().height(68.dp), verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(68.dp), contentAlignment = Alignment.Center) {
                                Box(
                                    Modifier.size(46.dp)
                                        .background(AutoScriptPalette.AccentSoft, RoundedCornerShape(12.dp))
                                        .border(1.dp, Color(0xFFC7D8FF), RoundedCornerShape(12.dp)),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Icon(painterResource(android.R.drawable.sym_def_app_icon), contentDescription = "Runner 模板默认图标", tint = Color.Unspecified, modifier = Modifier.size(24.dp))
                                }
                            }
                            Column(Modifier.weight(1f).padding(start = 13.dp)) {
                                Text("桌面图标", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = AutoScriptPalette.TextPrimary)
                                Text("独立 Runner 模板默认图标 · 固定", fontSize = 10.sp, color = AutoScriptPalette.TextSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp))
                            }
                            // 参考 bt_apk_reset_icon 是 36dp 高 + gravity=center，只有左右内边距。
                            // 原来用 padding(vertical = 11.dp) 去“居中”，实际是把内容框压到 36-22=14dp，
                            // 10sp 文本的行高放不下，字被上下裁掉，看起来就是一团重叠的笔画。
                            // 正确做法是 wrapContentHeight 居中，不要用纵向 padding 挤内容。
                            Text(
                                "恢复默认",
                                fontSize = 10.sp,
                                color = AutoScriptPalette.TextSecondary,
                                maxLines = 1,
                                softWrap = false,
                                modifier = Modifier.height(36.dp).clickable { notice = "已是默认图标" }
                                    .padding(horizontal = 10.dp).wrapContentHeight(),
                            )
                        }
                        Row(
                            Modifier.fillMaxWidth().padding(top = 12.dp).height(42.dp)
                                .background(AutoScriptPalette.AccentSoft, RoundedCornerShape(10.dp))
                                .clickable(enabled = false) {},
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(painterResource(R.drawable.package_folder_open_20), contentDescription = null, tint = Color.Unspecified, modifier = Modifier.size(18.dp))
                            Text("自定义图标暂未开放", color = AutoScriptPalette.TextSecondary, fontSize = 12.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 7.dp))
                        }
                    }
                }
                item { PackageSectionTitle("安装信息") }
                item {
                    PackageCard {
                        PackageInput("桌面名称（模板固定）", appName, "", enabled = false) {}
                        Text("脚本项目：${snapshot.manifest.name}", fontSize = 11.sp, color = AutoScriptPalette.TextSecondary, modifier = Modifier.padding(top = 6.dp))
                        Row(Modifier.fillMaxWidth().padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text("应用包名", fontSize = 10.sp, color = AutoScriptPalette.TextSecondary, modifier = Modifier.weight(1f))
                            Text(
                                "模板固定",
                                fontSize = 10.sp,
                                color = AutoScriptPalette.Accent,
                                maxLines = 1,
                                softWrap = false,
                                modifier = Modifier.height(28.dp)
                                    .padding(horizontal = 9.dp).wrapContentHeight(),
                            )
                        }
                        PackageInput(null, packageName, "", enabled = false) {}
                        Text("版本 ${DevicePackagingController.VERSION_NAME} · versionCode ${DevicePackagingController.VERSION_CODE}（固定）", fontSize = 11.sp, color = AutoScriptPalette.TextSecondary, modifier = Modifier.padding(top = 6.dp))
                        Text("输出文件", fontSize = 10.sp, color = AutoScriptPalette.TextSecondary, modifier = Modifier.padding(top = 12.dp))
                        PackageInput(null, outputPath, "", enabled = false) {}
                        Text("APK 使用设备私有密钥；同包名脚本通过系统更新安装。", fontSize = 11.sp, color = AutoScriptPalette.TextSecondary, modifier = Modifier.padding(top = 6.dp))
                    }
                }
                item { PackageSectionTitle("代码与资源保护") }
                item {
                    PackageCard(horizontalOnly = true) {
                        Row(Modifier.fillMaxWidth().height(52.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text("混淆 / 资源加密", modifier = Modifier.weight(1f).padding(end = 12.dp), fontSize = 13.sp, fontWeight = FontWeight.Bold, color = AutoScriptPalette.TextPrimary)
                            Text("未开放", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = AutoScriptPalette.TextSecondary)
                        }
                        Box(Modifier.fillMaxWidth().height(1.dp).background(Color(0x0D14203A)))
                        Row(
                            Modifier.fillMaxWidth().height(52.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text("插件编译为冻结 Lua", modifier = Modifier.weight(1f).padding(end = 12.dp), fontSize = 13.sp, fontWeight = FontWeight.Bold, color = AutoScriptPalette.TextPrimary)
                            IosSwitch(checked = true, enabled = false) {}
                        }
                    }
                }
                item { PackageSectionTitle("随包插件") }
                item {
                    PackageCard(padding = PaddingValues(start = 14.dp, top = 12.dp, end = 14.dp, bottom = 12.dp)) {
                        Row(
                            Modifier.fillMaxWidth().height(32.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(painterResource(R.drawable.package_java_plugin_24), contentDescription = null, tint = Color.Unspecified, modifier = Modifier.size(24.dp))
                            Text("Android 扩展插件 APK", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = AutoScriptPalette.TextPrimary, modifier = Modifier.weight(1f).padding(start = 9.dp))
                            Text("未开放", color = AutoScriptPalette.TextSecondary, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }
                        Text(
                            "只携带本项目冻结脚本与资源；外部扩展插件 APK 未开放。",
                            fontSize = 11.sp,
                            lineHeight = 15.sp,
                            color = AutoScriptPalette.TextSecondary,
                            modifier = Modifier.padding(top = 7.dp),
                        )
                    }
                }
            }
            1 -> PackageUpdatePage(Modifier.weight(1f))
            else -> PackageLogPage(
                logs = logs,
                modifier = Modifier.weight(1f),
                onCopy = {
                    clipboard.setText(AnnotatedString(logs.joinToString("\n")))
                    notice = "日志已复制"
                },
            )
        }
        Surface(color = Color.White) {
            Row(Modifier.fillMaxWidth().padding(start = 14.dp, top = 10.dp, end = 14.dp, bottom = 12.dp)) {
                Text(
                    if (busy) "取消打包" else "开始打包 APK",
                    color = AutoScriptPalette.AccentDark,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.weight(1f).height(44.dp)
                        .background(Color(0xFFDCE7FF), RoundedCornerShape(12.dp))
                        .clickable(enabled = !exporting) {
                            if (busy) { controller.cancel(); packagingJob?.cancel(); notice = "正在取消打包并清理暂存文件" }
                            else {
                                busy = true; selectedTab = 2; notice = null
                                appendLog("开始设备打包：${snapshot.manifest.name} → $packageName")
                                packagingJob = scope.launch {
                                    try {
                                        val packaged = controller.build(snapshot) { line -> scope.launch { appendLog(line) } }
                                        result = packaged
                                        notice = "APK 构建完成并已验签；点击导出选择保存位置"
                                        appendLog("构建成功：${packaged.file.name}", "设备签名证书 SHA-256：${packaged.certificateSha256}")
                                        packaged.upgradeWarning?.let { notice = it; appendLog(it) }
                                    } catch (_: CancellationException) {
                                        notice = "已取消打包"; appendLog("打包已取消，暂存输出已清理")
                                    } catch (error: Exception) {
                                        notice = error.message ?: "APK 构建失败"
                                        appendLog("构建失败：${notice}")
                                    } catch (_: LinkageError) {
                                        notice = "发布引擎未能加载，请重新安装完整 Studio"; appendLog(requireNotNull(notice))
                                    } finally { busy = false; packagingJob = null }
                                }
                            }
                        }
                        .padding(top = 14.dp),
                )
                if (result != null && !busy) Text(
                    if (exporting) "导出中…" else "导出 APK", color = AutoScriptPalette.AccentDark, fontSize = 13.sp,
                    fontWeight = FontWeight.Bold, textAlign = TextAlign.Center,
                    modifier = Modifier.padding(start = 8.dp).height(44.dp).weight(1f)
                        .background(Color(0xFFDCE7FF), RoundedCornerShape(12.dp)).clickable(enabled = !exporting) {
                            exportFile = result?.file; exporting = true
                            exportLauncher.launch("${snapshot.manifest.name.replace(Regex("[\\\\/:*?\"<>|]"), "_")}.apk")
                        }.wrapContentHeight(),
                )
                if (result != null && !busy) Text(
                    "分享 APK", color = AutoScriptPalette.AccentDark, fontSize = 13.sp,
                    fontWeight = FontWeight.Bold, textAlign = TextAlign.Center,
                    modifier = Modifier.padding(start = 8.dp).height(44.dp).weight(1f)
                        .background(Color(0xFFDCE7FF), RoundedCornerShape(12.dp)).clickable(enabled = !exporting) {
                            try {
                                val file = requireNotNull(result).file
                                require(file.isFile && file.canonicalPath.startsWith(File(context.filesDir, "packages").canonicalPath + File.separator))
                                val uri = androidx.core.content.FileProvider.getUriForFile(context, "${context.packageName}.packages", file)
                                val intent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                                    type = "application/vnd.android.package-archive"
                                    putExtra(android.content.Intent.EXTRA_STREAM, uri)
                                    clipData = android.content.ClipData.newUri(context.contentResolver, "Runner APK", uri)
                                    addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                }
                                context.startActivity(android.content.Intent.createChooser(intent, "分享已验签 Runner APK"))
                            } catch (_: Exception) { notice = "无法打开分享菜单；已生成的 APK 仍可导出" }
                        }.wrapContentHeight(),
                )
            }
        }
    }
}

@Composable
private fun PackageSectionTitle(title: String, first: Boolean = false) {
    Text(
        title,
        fontSize = 15.sp,
        fontWeight = FontWeight.Bold,
        color = AutoScriptPalette.TextPrimary,
        modifier = Modifier.padding(start = 4.dp, top = if (first) 4.dp else 18.dp),
    )
}

/** `bg_project_surface`：白底圆角卡，默认 14dp 内边距。 */
@Composable
private fun PackageCard(
    horizontalOnly: Boolean = false,
    padding: PaddingValues = if (horizontalOnly) PaddingValues(horizontal = 14.dp) else PaddingValues(14.dp),
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        shape = RoundedCornerShape(14.dp),
        color = Color.White,
    ) {
        Column(Modifier.padding(padding), content = content)
    }
}

/** `bg_apk_input`：46dp 浅灰底圆角输入框，13sp。 */
@Composable
private fun PackageInput(label: String?, value: String, hint: String, enabled: Boolean = true, onValueChange: (String) -> Unit) {
    label?.let { Text(it, fontSize = 10.sp, color = AutoScriptPalette.TextSecondary) }
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        singleLine = true,
        enabled = enabled,
        textStyle = TextStyle(fontSize = 13.sp, color = AutoScriptPalette.TextPrimary),
        modifier = Modifier.fillMaxWidth().padding(top = if (label != null) 6.dp else 4.dp).height(46.dp)
            .background(AutoScriptPalette.PageBackground, RoundedCornerShape(11.dp))
            .padding(horizontal = 12.dp),
        decorationBox = { inner ->
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.CenterStart) {
                if (value.isEmpty()) Text(hint, fontSize = 13.sp, color = AutoScriptPalette.TextSecondary)
                inner()
            }
        },
    )
}

/** `IosSwitchView` 45×26：圆角轨道 + 22dp 白色滑块。 */
@Composable
internal fun IosSwitch(checked: Boolean, enabled: Boolean, onCheckedChange: (Boolean) -> Unit) {
    val track = when {
        !enabled -> Color(0xFFE5E7EB)
        checked -> AutoScriptPalette.Accent
        else -> Color(0xFFD1D5DB)
    }
    Box(
        Modifier.size(width = 45.dp, height = 26.dp)
            .background(track, RoundedCornerShape(13.dp))
            .clickable(enabled = enabled) { onCheckedChange(!checked) }
            .padding(2.dp),
        contentAlignment = if (checked) Alignment.CenterEnd else Alignment.CenterStart,
    ) {
        Box(Modifier.size(22.dp).background(Color.White, RoundedCornerShape(11.dp)))
    }
}

/** `page_apk_update.xml`：热更新发布表单；本地版没有后台，控件保留但全部禁用并说明原因。 */
@Composable
private fun PackageUpdatePage(modifier: Modifier) {
    Column(
        modifier.verticalScroll(rememberScrollState()).padding(start = 16.dp, top = 18.dp, end = 16.dp, bottom = 24.dp),
    ) {
        Text("发布项目更新", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = AutoScriptPalette.TextPrimary)
        Surface(Modifier.fillMaxWidth().padding(top = 10.dp), shape = RoundedCornerShape(14.dp), color = Color.White) {
            Column(Modifier.padding(16.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PackageVersionBox("安装包版本", "--", Modifier.weight(1f))
                    PackageVersionBox("热更新版本", "--", Modifier.weight(1f))
                }
                Text("热更新日期：--", fontSize = 10.sp, color = AutoScriptPalette.TextSecondary, modifier = Modifier.padding(top = 8.dp))
                Text("仅生成并上传热更新包，不会重新打包或签名 APK", fontSize = 10.sp, color = AutoScriptPalette.TextSecondary, modifier = Modifier.padding(top = 8.dp))
                Text("更新说明", fontSize = 10.sp, color = AutoScriptPalette.TextSecondary, modifier = Modifier.padding(top = 16.dp))
                Box(
                    Modifier.fillMaxWidth().padding(top = 6.dp).height(92.dp)
                        .background(Color.White, RoundedCornerShape(10.dp))
                        .border(1.dp, AutoScriptPalette.Border, RoundedCornerShape(10.dp))
                        .padding(15.dp),
                ) {
                    Text("向用户说明本次更新内容", fontSize = 12.sp, color = AutoScriptPalette.TextSecondary)
                }
                Row(Modifier.fillMaxWidth().padding(top = 8.dp).height(52.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("允许获取更新", modifier = Modifier.weight(1f).padding(end = 12.dp), fontSize = 12.sp, fontWeight = FontWeight.Bold, color = AutoScriptPalette.TextPrimary)
                    IosSwitch(checked = false, enabled = false) {}
                }
                Box(Modifier.fillMaxWidth().height(1.dp).background(Color(0x0D14203A)))
                Row(Modifier.fillMaxWidth().height(52.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f).padding(end = 12.dp)) {
                        Text("强制更新", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = AutoScriptPalette.TextPrimary)
                        Text("更新完成前不允许启动项目", fontSize = 9.sp, color = AutoScriptPalette.TextSecondary, modifier = Modifier.padding(top = 3.dp))
                    }
                    IosSwitch(checked = false, enabled = false) {}
                }
                Text(
                    "发布热更新",
                    color = Color.White,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp).height(44.dp)
                        .background(AutoScriptPalette.Accent.copy(alpha = .35f), RoundedCornerShape(12.dp))
                        .padding(top = 13.dp),
                )
                Text("在线更新服务未配置，本地版不提供热更新发布。", fontSize = 10.sp, color = AutoScriptPalette.TextSecondary, modifier = Modifier.padding(top = 8.dp))
            }
        }
    }
}

@Composable
private fun PackageVersionBox(label: String, value: String, modifier: Modifier) {
    Column(
        modifier.height(72.dp).background(AutoScriptPalette.PageBackground, RoundedCornerShape(11.dp)).padding(horizontal = 12.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Text(label, fontSize = 10.sp, color = AutoScriptPalette.TextSecondary)
        Text(value, fontSize = 16.sp, fontWeight = FontWeight.Bold, color = AutoScriptPalette.TextPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 3.dp))
    }
}

/** `page_apk_build_log.xml`：深色控制台，44dp 头（标题 + 复制日志），空态 11sp 灰字。 */
@Composable
private fun PackageLogPage(logs: List<String>, modifier: Modifier, onCopy: () -> Unit) {
    Column(modifier.fillMaxWidth().background(Color(0xFF181C23))) {
        Row(Modifier.fillMaxWidth().height(44.dp).padding(start = 12.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("日志输出", color = Color(0xFFE6EDF3), fontSize = 12.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            Text(
                "复制日志",
                color = AutoScriptPalette.Accent,
                fontSize = 10.sp,
                modifier = Modifier.height(32.dp).clickable(enabled = logs.isNotEmpty(), onClick = onCopy).padding(horizontal = 10.dp, vertical = 9.dp),
            )
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(Color(0xFF2A313C)))
        if (logs.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(horizontal = 20.dp), contentAlignment = Alignment.Center) {
                Text(
                    "暂无日志\n开始打包或发布更新后会在这里显示详细过程",
                    color = Color(0xFF7D8590),
                    fontSize = 11.sp,
                    lineHeight = 17.sp,
                    textAlign = TextAlign.Center,
                )
            }
        } else {
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(top = 6.dp, bottom = 8.dp)) {
                items(logs) { line ->
                    Text(
                        line,
                        color = Color(0xFFC9D1D9),
                        fontSize = 11.sp,
                        lineHeight = 16.sp,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 3.dp),
                    )
                }
            }
        }
    }
}

@Composable
internal fun ImageToolsScreen(
    snapshot: ProjectSnapshot,
    store: ProjectStore,
    runtimeClient: RuntimeClient,
    runtimeState: RuntimeConnectionState,
    onSnapshotChanged: (ProjectSnapshot) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    BackHandler(onBack = onBack)
    var settingsVisible by remember(snapshot.manifest.projectId) { mutableStateOf(false) }
    var previewBitmap by remember(snapshot.manifest.projectId) { mutableStateOf<Bitmap?>(null) }
    var previewMessage by remember(snapshot.manifest.projectId) { mutableStateOf<String?>(null) }
    var previewPending by remember(snapshot.manifest.projectId) { mutableStateOf(false) }
    var previewSaving by remember(snapshot.manifest.projectId) { mutableStateOf(false) }
    var choosingScreenshotFolder by remember(snapshot.manifest.projectId) { mutableStateOf(false) }
    var browsingFolders by remember(snapshot.manifest.projectId) { mutableStateOf(false) }
    var currentImageFolder by remember(snapshot.manifest.projectId) { mutableStateOf("") }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val context = LocalContext.current
    val latestPreviewBitmap by rememberUpdatedState(previewBitmap)
    DisposableEffect(snapshot.manifest.projectId) {
        onDispose { latestPreviewBitmap?.recycle() }
    }
    val images = snapshot.manifest.resources.mapNotNull { resource ->
        resource.takeIf { it.get("kind")?.asString == "image" }?.get("path")?.asString
    }
    val visibleImages = images.filter { it.removePrefix("assets/images/").substringBeforeLast('/', "") == currentImageFolder }
    val rootStatus = when (runtimeState.rootState) {
        RuntimeRootState.READY -> "Root 已就绪"
        RuntimeRootState.STARTING -> "Root 正在连接"
        RuntimeRootState.FAILED -> "Root 连接失败"
        RuntimeRootState.STOPPED -> "Root 尚未启动"
        RuntimeRootState.UNKNOWN -> "Root 状态未知"
    }
    Column(modifier.fillMaxSize().background(AutoScriptPalette.PageBackground)) {
        UtilityLightHeader("图片与标注", "项目图片 ${images.size} 张", onBack) {
            Text("文件夹", color = AutoScriptPalette.Accent, fontSize = 11.sp,
                modifier = Modifier.clickable { browsingFolders = true }.padding(horizontal = 6.dp, vertical = 6.dp))
            Text(
                "导入",
                color = AutoScriptPalette.Accent,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(start = 10.dp).clickable { settingsVisible = true }.padding(horizontal = 6.dp, vertical = 6.dp),
            )
        }
        Text(
            "当前位置：${currentImageFolder.ifEmpty { "图片根目录" }} · ${visibleImages.size} 张。文件夹可选择位置或新建；截图仅在主动点击后执行。",
            modifier = Modifier.fillMaxWidth().background(Color.White).padding(horizontal = 16.dp, vertical = 10.dp),
            fontSize = 11.sp,
            color = AutoScriptPalette.TextSecondary,
        )
        LazyColumn(
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item {
                Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp), color = Color.White) {
                    Column(Modifier.padding(14.dp)) {
                        Text("截图工作区", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = AutoScriptPalette.TextPrimary)
                        Text(
                            "$rootStatus；点击后给你 3 秒切换到目标应用。脚本运行时不会抢占 Root 截图通道。",
                            fontSize = 11.sp,
                            lineHeight = 15.sp,
                            color = AutoScriptPalette.TextSecondary,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                        Text(
                            if (previewPending) "正在截图..." else "截取当前屏幕",
                            color = if (previewPending) AutoScriptPalette.TextSecondary else Color.White,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth().padding(top = 12.dp).height(42.dp)
                                .background(
                                    if (previewPending) Color(0xFFF3F4F6) else AutoScriptPalette.Accent,
                                    RoundedCornerShape(10.dp),
                                )
                                .clickable(enabled = !previewPending && !previewSaving) {
                                    previewPending = true
                                    previewMessage = "正在读取当前屏幕"
                                    scope.launch {
                                        when (val result = withContext(Dispatchers.IO) { runtimeClient.capturePreview() }) {
                                            is ScreenshotPreviewResult.Success -> {
                                                val decoded = withContext(Dispatchers.IO) {
                                                    BitmapFactory.decodeFile(result.file.path)
                                                }
                                                result.file.delete()
                                                previewBitmap?.recycle()
                                                previewBitmap = decoded
                                                previewMessage = if (decoded == null) "截图文件无法解码" else "预览已更新：${decoded.width} x ${decoded.height}"
                                            }
                                            is ScreenshotPreviewResult.Unavailable -> {
                                                previewMessage = result.message
                                            }
                                        }
                                        previewPending = false
                                    }
                                }
                                .padding(top = 12.dp),
                        )
                        previewMessage?.let { message ->
                            Text(message, fontSize = 10.sp, color = AutoScriptPalette.TextSecondary, modifier = Modifier.padding(top = 8.dp))
                        }
                        previewBitmap?.let { bitmap ->
                            Image(
                                bitmap = bitmap.asImageBitmap(),
                                contentDescription = "当前屏幕预览",
                                contentScale = ContentScale.FillBounds,
                                modifier = Modifier.fillMaxWidth().padding(top = 10.dp)
                                    .aspectRatio(bitmap.width.toFloat() / bitmap.height.toFloat())
                                    .pointerInput(bitmap) {
                                        detectTapGestures { offset ->
                                            val x = (offset.x / size.width * bitmap.width)
                                                .toInt()
                                                .coerceIn(0, bitmap.width - 1)
                                            val y = (offset.y / size.height * bitmap.height)
                                                .toInt()
                                                .coerceIn(0, bitmap.height - 1)
                                            val color = bitmap.getPixel(x, y) and 0x00ffffff
                                            previewMessage = "取色：($x, $y) · #${color.toString(16).padStart(6, '0').uppercase()}"
                                        }
                                    },
                            )
                            Text(
                                if (previewSaving) "正在保存图片资源..." else "保存整张截图到项目图片",
                                color = if (previewSaving) AutoScriptPalette.TextSecondary else AutoScriptPalette.Accent,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.fillMaxWidth().padding(top = 10.dp).height(38.dp)
                                    .background(Color(0xFFF0F6FF), RoundedCornerShape(9.dp))
                                    .clickable(enabled = !previewSaving) {
                                        choosingScreenshotFolder = true
                                    }
                                    .padding(top = 11.dp),
                            )
                        }
                    }
                }
            }
            if (visibleImages.isEmpty()) {
                item {
                    Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp), color = Color.White) {
                        Text("当前文件夹暂无图片，点右上角“导入”，或截图后保存到这里。", fontSize = 12.sp, color = AutoScriptPalette.TextSecondary, modifier = Modifier.padding(18.dp))
                    }
                }
            }
            items(visibleImages, key = { it }) { path ->
                Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp), color = Color.White) {
                    Row(Modifier.padding(horizontal = 13.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(34.dp).background(AutoScriptPalette.AccentSoft, RoundedCornerShape(10.dp)), contentAlignment = Alignment.Center) {
                            Icon(painterResource(R.drawable.editor_recognition_preview_24), contentDescription = null, tint = Color.Unspecified, modifier = Modifier.size(20.dp))
                        }
                        Column(Modifier.weight(1f).padding(start = 10.dp)) {
                            Text(path.substringAfterLast('/'), fontSize = 14.sp, fontWeight = FontWeight.Bold, color = AutoScriptPalette.TextPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(path, fontSize = 9.sp, color = AutoScriptPalette.TextSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp))
                        }
                    }
                }
            }
        }
    }
    if (browsingFolders) ImageTemplateSaveDialog(
        imagePaths = images, store = store, projectId = snapshot.manifest.projectId, folderOnly = true,
        initialFolder = currentImageFolder,
        onDismiss = { browsingFolders = false },
        onSave = { folder, _ -> currentImageFolder = folder; browsingFolders = false },
    )
    if (choosingScreenshotFolder) {
        ImageTemplateSaveDialog(
            store = store,
            projectId = snapshot.manifest.projectId,
            imagePaths = images,
            initialFolder = currentImageFolder,
            defaultName = "screen-${System.currentTimeMillis()}.png",
            onDismiss = { choosingScreenshotFolder = false },
            onSave = { folder, name ->
                choosingScreenshotFolder = false
                val bitmap = previewBitmap
                if (bitmap == null) {
                    previewMessage = "请先截图"
                } else {
                                        previewSaving = true
                                        scope.launch {
                                            val result = withContext(Dispatchers.IO) {
                                                runCatching {
                                                    val scale = minOf(
                                                        1.0,
                                                        MAX_TEMPLATE_EDGE.toDouble() / bitmap.width,
                                                        MAX_TEMPLATE_EDGE.toDouble() / bitmap.height,
                                                        sqrt(MAX_TEMPLATE_PIXELS.toDouble() / (bitmap.width.toLong() * bitmap.height)),
                                                    )
                                                    val exportWidth = (bitmap.width * scale).toInt().coerceAtLeast(1)
                                                    val exportHeight = (bitmap.height * scale).toInt().coerceAtLeast(1)
                                                    val exported = if (exportWidth == bitmap.width && exportHeight == bitmap.height) {
                                                        bitmap
                                                    } else {
                                                        Bitmap.createScaledBitmap(bitmap, exportWidth, exportHeight, true)
                                                    }
                                                    val temporary = File.createTempFile(
                                                        "captured-screen-",
                                                        ".png",
                                                        context.cacheDir,
                                                    )
                                                    try {
                                                        FileOutputStream(temporary).use { output ->
                                                            require(exported.compress(Bitmap.CompressFormat.PNG, 100, output))
                                                            output.flush()
                                                            output.fd.sync()
                                                        }
                                                        FileInputStream(temporary).use { input ->
                                                            store.importResource(
                                                                projectId = snapshot.manifest.projectId,
                                                                kind = ProjectResourceKind.IMAGE,
                                                                sourceName = name,
                                                                source = input,
                                                                expectedResourcePaths = snapshot.manifest.resources
                                                                    .mapNotNull { it.get("path")?.asString }
                                                                    .toSet(),
                                                                imageDirectory = folder,
                                                            )
                                                        }
                                                    } finally {
                                                        temporary.delete()
                                                        if (exported !== bitmap) exported.recycle()
                                                    }
                                                }
                                            }
                                            result.onSuccess {
                                                onSnapshotChanged(it)
                                                previewMessage = "已保存为项目图片：${it.manifest.resources.last().get("path").asString}"
                                            }.onFailure {
                                                previewMessage = it.message ?: "截图资源保存失败"
                                            }
                                            previewSaving = false
                                        }
                }
            },
        )
    }
    if (settingsVisible) {
        ProjectSettingsDialog(
            snapshot = snapshot,
            store = store,
            onSnapshotChanged = onSnapshotChanged,
            onDismiss = { settingsVisible = false },
        )
    }
}

private fun suggestedApplicationId(projectName: String, projectId: String): String {
    val nameSuffix = projectName.lowercase().filter(Char::isLetterOrDigit).take(30)
    val suffix = nameSuffix.ifEmpty { projectId.lowercase().filter(Char::isLetterOrDigit).takeLast(20).ifEmpty { "project" } }
    return "com.yibian.app.$suffix"
}

@Composable
internal fun RecordingWorkspaceScreen(
    snapshot: ProjectSnapshot,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    BackHandler(onBack = onBack)
    Column(modifier.fillMaxSize().background(AutoScriptPalette.PageBackground)) {
        UtilityLightHeader("动作录制", "未录制", onBack)
        Text(
            snapshot.manifest.name + " · 录制会把确认的点击、滑动、按键和等待转换为积木草稿",
            modifier = Modifier.fillMaxWidth().background(Color.White).padding(horizontal = 16.dp, vertical = 10.dp),
            fontSize = 11.sp,
            color = AutoScriptPalette.TextSecondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        LazyColumn(
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item {
                Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp), color = Color.White) {
                    Column(Modifier.padding(14.dp)) {
                        Text("悬浮控制预览", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = AutoScriptPalette.TextPrimary)
                        Row(
                            Modifier.padding(top = 10.dp).background(Color(0xE6111827), RoundedCornerShape(18.dp)).padding(horizontal = 12.dp, vertical = 8.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Text("● REC", color = Color(0xFFFF6B6B), fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            Text("拖动", color = Color.White, fontSize = 12.sp)
                            Text("停止", color = Color.White, fontSize = 12.sp)
                        }
                        Text(
                            "参考产品收起编辑面板后显示“按下拖动窗口 / 单击关闭录制”的悬浮提示。当前 Runtime 尚未提供录制事件流，这里只展示交互边界，不产生动作。",
                            fontSize = 11.sp,
                            lineHeight = 15.sp,
                            color = AutoScriptPalette.TextSecondary,
                            modifier = Modifier.padding(top = 8.dp),
                        )
                    }
                }
            }
            item { Text("动作类型", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = AutoScriptPalette.TextPrimary, modifier = Modifier.padding(start = 4.dp, top = 6.dp)) }
            items(listOf("点击与长按", "滑动手势", "设备按键", "等待与循环", "寻图后操作")) { label ->
                Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp), color = Color.White) {
                    Text(label, fontSize = 13.sp, color = AutoScriptPalette.TextPrimary, modifier = Modifier.padding(horizontal = 14.dp, vertical = 13.dp))
                }
            }
            item {
                Text(
                    "录制后端尚未开放",
                    color = AutoScriptPalette.TextSecondary,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().height(42.dp).background(Color(0xFFF3F4F6), RoundedCornerShape(10.dp)).padding(top = 12.dp),
                )
            }
        }
    }
}
