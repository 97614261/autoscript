package com.autoscript.studio

import android.graphics.Bitmap
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
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
import com.autoscript.runtime.client.RuntimeClient
import com.autoscript.runtime.client.ScreenshotPreviewResult
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.io.File
import java.io.FileInputStream
import kotlinx.coroutines.Dispatchers
import androidx.compose.runtime.collectAsState
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive


/**
 * Local Runner packaging and build logs only; fixed template identity is displayed read-only.
 * Real APK construction uses a device-private signing key. No online publishing placeholder.
 */
@Composable
internal fun ProjectPackageScreen(
    snapshot: ProjectSnapshot,
    runtimeClient: RuntimeClient,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val projectId = snapshot.manifest.projectId
    val controller = remember(context) { DevicePackagingController(context) }
    val components = remember(context) { RunnerComponentRepository(context) }
    var dependencyPlan by remember(projectId) { mutableStateOf<RunnerDependencyPlan?>(null) }
    var dependencyError by remember(projectId) { mutableStateOf<String?>(null) }
    var dependencyRevision by remember(projectId) { mutableIntStateOf(0) }
    val dependencyRuntime by runtimeClient.runtimeStates.collectAsState()
    val dependencies = dependencyPlan ?: RunnerDependencyPlan(false, false)
    LaunchedEffect(projectId, snapshot.manifest, snapshot.flowSources, dependencyRuntime.phase, dependencyRuntime.sessionGeneration, dependencyRevision) {
        dependencyPlan = null; dependencyError = null
        try {
            dependencyPlan = if (snapshot.manifest.sourceMode == com.autoscript.project.store.ProjectSourceMode.LUA) {
                RunnerDependencyPlan.fromCapabilities(snapshot.manifest.capabilities)
            } else withContext(Dispatchers.IO) {
                val compiled = runtimeClient.compileVisualProject(projectId)
                val generation = (compiled as? com.autoscript.runtime.client.VisualCompileResult.Success)?.generationId
                    ?: error("PACKAGING_COMPILE_UNAVAILABLE")
                val requirements = requireNotNull(RuntimeProjectPlan.fromVisualSnapshot(snapshot, generation).requiredVisionComponents)
                currentCoroutineContext().ensureActive()
                RunnerDependencyPlan.fromCompiledRequirements(requirements)
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Throwable) { dependencyError = "脚本需求检查未完成，请检查积木与运行连接后刷新；不会按默认能力勾选推断组件" }
    }
    val scope = rememberCoroutineScope()
    var packagingJob by remember(projectId) { mutableStateOf<Job?>(null) }
    var busy by remember(projectId) { mutableStateOf(false) }
    var exporting by remember(projectId) { mutableStateOf(false) }
    var result by remember(projectId) { mutableStateOf<DeviceApkPackageResult?>(null) }
    var progressTicket by remember(projectId) { mutableStateOf<Long?>(null) }
    var exportFile by remember(projectId) { mutableStateOf<File?>(null) }
    var componentJob by remember(projectId) { mutableStateOf<Job?>(null) }
    var componentBusy by remember(projectId) { mutableStateOf(false) }
    var inventory by remember(projectId) { mutableStateOf<List<RunnerComponentEntry>>(emptyList()) }
    var selectedComponent by remember(projectId) { mutableStateOf<String?>(null) }
    var deleteComponent by remember(projectId) { mutableStateOf<String?>(null) }
    var importProject by remember { mutableStateOf<String?>(null) }
    val componentAvailability = runnerCapabilityAvailability(dependencies, inventory, selectedComponent)
    val templateReady = dependencyPlan != null && runnerTemplateReady(dependencies, inventory, selectedComponent, controller.templateAvailable)
    fun leave() { controller.cancel(); packagingJob?.cancel(); componentJob?.cancel(); importProject = null; onBack() }
    BackHandler(onBack = ::leave)
    DisposableEffect(controller) { onDispose { controller.cancel() } }
    var selectedTab by remember(projectId) { mutableIntStateOf(0) }
    val appName = DevicePackagingController.DISPLAY_NAME
    val packageName = DevicePackagingController.APPLICATION_ID
    val outputPath = result?.file?.name ?: "生成后选择导出位置"
    var notice by remember(projectId) { mutableStateOf<String?>(null) }
    var logs by remember(projectId) { mutableStateOf<List<String>>(emptyList()) }
    val tabs = listOf("打包", "日志输出")
    val timestamp = remember { SimpleDateFormat("HH:mm:ss", Locale.getDefault()) }
    val clipboard = LocalClipboardManager.current

    fun refreshComponents() {
        componentJob?.cancel()
        componentBusy = true
        componentJob = scope.launch {
            try {
                inventory = components.inventory()
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                notice = "离线组件读取未完成；原文件保留，请稍后刷新"
            } finally { componentBusy = false }
        }
    }
    LaunchedEffect(projectId) { refreshComponents() }
    DisposableEffect(projectId) { onDispose { componentJob?.cancel() } }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val owner = importProject
        importProject = null
        if (uri != null && owner == projectId && !busy && !exporting && !componentBusy) {
            componentBusy = true
            componentJob = scope.launch {
                try {
                    components.import(uri)
                    inventory = components.inventory()
                    notice = "组件已验签并导入；请选择与项目组合匹配的模板"
                } catch (error: Exception) {
                    if (error is CancellationException) throw error
                    notice = "导入未通过签名、兼容或预算检查；未替换任何已有组件"
                } finally { componentBusy = false }
            }
        }
    }

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
                item { PackageSectionTitle("独立运行包", first = true) }
                item {
                    PackageCard {
                        Text(snapshot.manifest.name, fontSize = 14.sp, fontWeight = FontWeight.Bold, color = AutoScriptPalette.TextPrimary)
                        Text("携带本项目冻结脚本、界面和已声明资源。", fontSize = 11.sp,
                            color = AutoScriptPalette.TextSecondary, modifier = Modifier.padding(top = 4.dp))
                        if (dependencyPlan == null) Text(dependencyError ?: "正在编译检查实际组件需求…", fontSize = 11.sp,
                            color = AutoScriptPalette.Accent, modifier = Modifier.padding(top = 6.dp))
                        else if (!templateReady) Text("${when (componentAvailability) {
                            RunnerCapabilityAvailability.MISSING -> "未导入所需离线组件"
                            RunnerCapabilityAvailability.INCOMPATIBLE -> "已有组件版本或协议不兼容"
                            RunnerCapabilityAvailability.CORRUPT -> "已有组件校验失败"
                            RunnerCapabilityAvailability.AVAILABLE -> "请选择组件"
                        }} · 下载暂未开放。当前仍可编辑和运行项目。",
                            fontSize = 11.sp, color = AutoScriptPalette.Accent, modifier = Modifier.padding(top = 6.dp))
                    }
                }
                item { PackageSectionTitle("离线 Runner 组件") }
                item {
                    PackageCard {
                        Text("${if (dependencyPlan == null) "需求待检查" else "需要 " + dependencies.family.name.lowercase()} · 协议${com.autoscript.runtime.api.RuntimeProtocol.VERSION} / API${RunnerComponentDescriptor.API} · Android7+", fontSize = 11.sp,
                            color = AutoScriptPalette.TextPrimary)
                        Text("状态：${when (componentAvailability) {
                            RunnerCapabilityAvailability.AVAILABLE -> if (selectedComponent == null) "有匹配模板，请选择" else "所选打包模板可用"
                            RunnerCapabilityAvailability.MISSING -> "未导入"
                            RunnerCapabilityAvailability.INCOMPATIBLE -> "版本或协议不兼容"
                            RunnerCapabilityAvailability.CORRUPT -> "内容校验失败"
                        }}", fontSize = 10.sp, color = AutoScriptPalette.TextSecondary)
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(if (componentBusy) "检查中…" else "导入本地 APK", fontSize = 12.sp, color = AutoScriptPalette.Accent,
                                modifier = Modifier.clickable(enabled = !busy && !exporting && !componentBusy) {
                                    importProject = projectId
                                    importLauncher.launch(arrayOf("application/vnd.android.package-archive", "application/octet-stream"))
                                }.padding(vertical = 8.dp))
                            Text("刷新", fontSize = 12.sp, color = AutoScriptPalette.Accent,
                                modifier = Modifier.clickable(enabled = !busy && !exporting && !componentBusy) { dependencyRevision++; refreshComponents() }.padding(8.dp))
                            if (componentBusy) Text("取消", fontSize = 12.sp, color = AutoScriptPalette.Accent,
                                modifier = Modifier.clickable { componentJob?.cancel() }.padding(8.dp))
                        }
                        if (inventory.isEmpty() && !componentBusy) Text("暂无已导入模板。仅接受当前智构发行证书签名的离线模板。", fontSize = 10.sp,
                            color = AutoScriptPalette.TextSecondary)
                        inventory.forEach { entry ->
                            val descriptor = entry.verified?.descriptor
                            val compatible = dependencyPlan != null && descriptor?.family == dependencies.family
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Text(if (descriptor == null) "${if (entry.state == RunnerComponentState.INCOMPATIBLE) "版本不兼容" else "内容校验失败"} · ${entry.id.take(8)} · 原文件保留" else
                                    "${if (selectedComponent == entry.id) "●" else "○"} ${descriptor.family.name.lowercase()} · ${descriptor.abis.joinToString(" / ")} · ${requireNotNull(entry.verified).size / 1_048_576}MiB${if (compatible) "" else " · 组合不符"}",
                                    fontSize = 11.sp, color = if (selectedComponent == entry.id) AutoScriptPalette.Accent else AutoScriptPalette.TextSecondary,
                                    modifier = Modifier.weight(1f).clickable(enabled = compatible && !busy && !componentBusy) { selectedComponent = entry.id }.padding(vertical = 6.dp))
                                Text("删除", fontSize = 11.sp, color = AutoScriptPalette.TextSecondary,
                                    modifier = Modifier.clickable(enabled = !busy && !componentBusy) { deleteComponent = entry.id }.padding(8.dp))
                            }
                        }
                        Text("模板仅用于打包，不代表智构已安装对应识图组件；不自动安装、联网或改脚本能力。", fontSize = 10.sp,
                            color = AutoScriptPalette.TextSecondary)
                    }
                }
                item { PackageSectionTitle("固定安装身份") }
                item {
                    PackageCard {
                        PackageInfoRow("桌面名称", appName)
                        PackageInfoRow("包名", packageName)
                        PackageInfoRow("版本", "${DevicePackagingController.VERSION_NAME} · ${DevicePackagingController.VERSION_CODE}")
                        PackageInfoRow("输出", outputPath)
                        Text("同包名运行包不能同时安装，安装另一个项目会更新现有运行包。",
                            fontSize = 11.sp, color = AutoScriptPalette.Accent, modifier = Modifier.padding(top = 6.dp))
                        Text("使用本设备私有密钥签名；其他设备生成的同包名 APK 可能无法直接覆盖安装。名称、图标和版本使用 Runner 模板。",
                            fontSize = 10.sp, color = AutoScriptPalette.TextSecondary, modifier = Modifier.padding(top = 4.dp))
                    }
                }
                item { PackageSectionTitle("打包范围") }
                item {
                    PackageCard {
                        PackageInfoRow(if (snapshot.manifest.sourceMode == com.autoscript.project.store.ProjectSourceMode.LUA) "旧Lua声明推断" else "编译需求",
                            if (dependencyPlan == null) "等待检查" else dependencies.componentLabels.joinToString("、"))
                        Text("组件需求与脚本权限声明独立；不代表组件已安装或设备已支持。", fontSize = 10.sp,
                            color = AutoScriptPalette.TextSecondary, modifier = Modifier.padding(bottom = 4.dp))
                        Text("插件编译为冻结 Lua，资源随包携带；不携带编辑器。", fontSize = 11.sp, color = AutoScriptPalette.TextPrimary)
                        Text("不提供在线热更新、外部扩展 APK 或资源加密。", fontSize = 10.sp,
                            color = AutoScriptPalette.TextSecondary, modifier = Modifier.padding(top = 4.dp))
                    }
                }
            }
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
                    if (busy) "取消打包" else if (dependencyPlan == null) "请先检查脚本需求" else if (templateReady) "开始打包 APK" else "请选择打包组件",
                    color = AutoScriptPalette.AccentDark,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.weight(1f).height(44.dp)
                        .background(Color(0xFFDCE7FF), RoundedCornerShape(12.dp))
                        .clickable(enabled = !exporting && !componentBusy && (busy || templateReady)) {
                            if (busy) { progressTicket = null; controller.cancel(); packagingJob?.cancel(); notice = "正在取消打包并清理暂存文件" }
                            else {
                                busy = true; selectedTab = 1; notice = null
                                appendLog("开始设备打包：${snapshot.manifest.name} → $packageName")
                                val ticket = System.nanoTime()
                                progressTicket = ticket
                                packagingJob = scope.launch {
                                    try {
                                        val packaged = controller.build(snapshot, selectedComponent, expectedDependencies = dependencies) { line -> scope.launch {
                                            if (progressTicket == ticket && busy) appendLog(line)
                                        } }
                                        result = packaged
                                        notice = "APK 构建完成并已验签；点击导出选择保存位置"
                                        appendLog("构建成功：${packaged.file.name}", "设备签名证书 SHA-256：${packaged.certificateSha256}")
                                        appendLog(*packaged.sizeReport.text().lines().toTypedArray())
                                        packaged.upgradeWarning?.let { notice = it; appendLog(it) }
                                    } catch (_: CancellationException) {
                                        notice = "已取消打包"; appendLog("打包已取消，暂存输出已清理")
                                    } catch (error: Exception) {
                                        notice = error.message ?: "APK 构建失败"
                                        appendLog("构建失败：${notice}")
                                    } catch (_: LinkageError) {
                                        notice = "发布引擎未能加载，请重新安装完整 Studio"; appendLog(requireNotNull(notice))
                                    } finally { progressTicket = null; busy = false; packagingJob = null }
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
    deleteComponent?.let { id ->
        StudioConfirmDialog(onDismissRequest = { deleteComponent = null }, compact = true,
            title = { Text("删除离线组件") },
            text = { Text("仅删除所选模板 ${id.take(12)}。项目、图片、脚本和已生成运行包不受影响；需要时可重新导入。", fontSize = 12.sp) },
            actions = listOf(StudioConfirmAction("取消", { deleteComponent = null }),
                StudioConfirmAction("删除", {
                    deleteComponent = null
                    if (!busy && !componentBusy) {
                        componentBusy = true
                        componentJob = scope.launch {
                            try {
                                components.remove(id)
                                if (selectedComponent == id) selectedComponent = null
                                inventory = components.inventory()
                                notice = "所选离线组件已删除，可重新导入；项目数据未变更"
                            } catch (error: Exception) {
                                if (error is CancellationException) throw error
                                notice = "删除未完成，请刷新检查；不会删除其他文件"
                            } finally { componentBusy = false }
                        }
                    }
                }, enabled = !busy && !componentBusy, destructive = true)))
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

/** Fixed template information is not an editable packaging form. */
@Composable
private fun PackageInfoRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, fontSize = 10.sp, color = AutoScriptPalette.TextSecondary, modifier = Modifier.width(58.dp))
        Text(value, fontSize = 11.sp, color = AutoScriptPalette.TextPrimary, modifier = Modifier.weight(1f))
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
                    "暂无日志\n开始打包后会在这里显示详细过程",
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
    insertionTarget: ImageWorkbenchInsertionTarget? = null,
) {
    BackHandler(onBack = onBack)
    var settingsVisible by remember(snapshot.manifest.projectId) { mutableStateOf(false) }
    var previewBitmap by remember(snapshot.manifest.projectId) { mutableStateOf<Bitmap?>(null) }
    var previewImagePath by remember(snapshot.manifest.projectId) { mutableStateOf<String?>(null) }
    var previewMessage by remember(snapshot.manifest.projectId) { mutableStateOf<String?>(null) }
    var previewPending by remember(snapshot.manifest.projectId) { mutableStateOf(false) }
    var previewSaving by remember(snapshot.manifest.projectId) { mutableStateOf(false) }
    var workbenchOpen by remember(snapshot.manifest.projectId) { mutableStateOf(false) }
    var previewEpoch by remember(snapshot.manifest.projectId) { mutableLongStateOf(0L) }
    var previewJob by remember(snapshot.manifest.projectId) { mutableStateOf<Job?>(null) }
    var choosingScreenshotFolder by remember(snapshot.manifest.projectId) { mutableStateOf(false) }
    var browsingFolders by remember(snapshot.manifest.projectId) { mutableStateOf(false) }
    var currentImageFolder by remember(snapshot.manifest.projectId) { mutableStateOf("") }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val context = LocalContext.current
    val latestPreviewBitmap by rememberUpdatedState(previewBitmap)
    DisposableEffect(snapshot.manifest.projectId) {
        onDispose {
            previewEpoch++
            previewJob?.cancel()
            latestPreviewBitmap?.recycle()
        }
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
                                    previewMessage = "3秒后读取屏幕，请切换到目标应用"
                                    val ticket = ++previewEpoch
                                    previewJob = scope.launch {
                                        var decoded: Bitmap? = null
                                        try {
                                            delay(3_000)
                                            when (val result = withContext(Dispatchers.IO) { runtimeClient.capturePreview() }) {
                                                is ScreenshotPreviewResult.Success -> {
                                                    try {
                                                        withContext(Dispatchers.IO) { decoded = decodeWorkbenchBitmap(result.file) }
                                                    } finally { result.file.delete() }
                                                    ensureActive()
                                                    if (ticket == previewEpoch) {
                                                        previewBitmap?.recycle()
                                                        previewBitmap = decoded
                                                        previewImagePath = null
                                                        decoded = null
                                                        previewMessage = previewBitmap?.let { "预览已更新：${it.width}×${it.height}px" }
                                                    }
                                                }
                                                is ScreenshotPreviewResult.Unavailable -> {
                                                    if (ticket == previewEpoch) previewMessage = result.message
                                                }
                                            }
                                        } catch (cancelled: CancellationException) { throw cancelled }
                                        catch (_: Exception) { if (ticket == previewEpoch) previewMessage = "截图无效或超过预览预算；已有图片保留" }
                                        finally { decoded?.recycle(); if (ticket == previewEpoch) { previewPending = false; previewJob = null } }
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
                            Row(
                                Modifier.fillMaxWidth().padding(top = 8.dp),
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                Text("打开图片工作台 · 处理 / 测试 / 保存", fontSize = 11.sp, color = AutoScriptPalette.Accent,
                                    modifier = Modifier.weight(1f).border(1.dp, AutoScriptPalette.Accent, RoundedCornerShape(6.dp))
                                        .clickable(enabled = !previewPending && !previewSaving) { workbenchOpen = true }
                                        .padding(vertical = 7.dp), textAlign = TextAlign.Center)
                            }
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
                Surface(Modifier.fillMaxWidth().clickable(enabled = !previewPending && !previewSaving && !workbenchOpen) {
                    previewPending = true
                    val ticket = ++previewEpoch
                    previewJob = scope.launch {
                        var decoded: Bitmap? = null
                        try {
                            withContext(Dispatchers.IO) { decoded = decodeWorkbenchBitmap(imageRecognitionFile(snapshot, path)) }
                            ensureActive()
                            if (ticket == previewEpoch) {
                                previewBitmap?.recycle()
                                previewBitmap = decoded
                                previewImagePath = path
                                decoded = null
                                workbenchOpen = true
                                previewMessage = "已打开项目图片；处理不会覆盖原文件"
                            }
                        } catch (cancelled: CancellationException) { throw cancelled }
                        catch (_: Exception) { if (ticket == previewEpoch) previewMessage = "图片不可读或超过预览预算" }
                        finally { decoded?.recycle(); if (ticket == previewEpoch) { previewPending = false; previewJob = null } }
                    }
                }, shape = RoundedCornerShape(14.dp), color = Color.White) {
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
    if (workbenchOpen) previewBitmap?.let { source ->
        ImageProcessingWorkbench(source, snapshot, store, runtimeClient, onSnapshotChanged,
            onDismiss = { workbenchOpen = false }, originalImagePath = previewImagePath, insertionTarget = insertionTarget)
    }
    if (browsingFolders) ImageTemplateSaveDialog(
        imagePaths = images, store = store, projectId = snapshot.manifest.projectId, folderOnly = true,
        initialFolder = currentImageFolder,
        onDismiss = { browsingFolders = false },
        onSave = { folder, _ -> currentImageFolder = folder; browsingFolders = false },
    )
    if (choosingScreenshotFolder) {
        ImageTemplateSaveDialog(
            pixelSize = previewBitmap?.let { it.width to it.height },
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
                    val sourceCopy = runCatching { bitmap.copy(Bitmap.Config.ARGB_8888, false) }.getOrNull()
                    if (sourceCopy == null) previewMessage = "无法准备截图，原图保留"
                    else {
                        previewSaving = true
                        scope.launch {
                            try {
                                editorOperation({ previewMessage = it.message ?: "截图资源保存失败" }, { previewSaving = false }) {
                                    val (updated, path) = withContext(Dispatchers.IO) {
                                        cropAndImportTemplate(store, snapshot, sourceCopy,
                                            ImageToolCodeGen.Roi(0, 0, sourceCopy.width, sourceCopy.height), context, folder, name)
                                    }
                                    onSnapshotChanged(updated)
                                    previewMessage = "已保存原像素项目图片：$path"
                                }
                            } finally { sourceCopy.recycle() }
                        }.invokeOnCompletion { sourceCopy.recycle() }
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
