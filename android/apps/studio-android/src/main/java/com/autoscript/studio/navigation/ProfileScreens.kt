package com.autoscript.studio

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.sp
import com.autoscript.core.designsystem.AutoScriptPalette
import androidx.compose.ui.unit.dp
import com.autoscript.core.designsystem.AutoScriptDimens

/** Local help and application information; reserved account routes have no visible entry. */
internal enum class ProfileSubpage {
    LOGIN,
    REGISTER,
    LEGAL,
    DOCS,
    UPDATE,
    GUIDE,
}

@Composable
internal fun ProfileSubpageScreen(
    page: ProfileSubpage,
    onBack: () -> Unit,
    onOpenSubpage: (ProfileSubpage) -> Unit,
    modifier: Modifier = Modifier,
    onOpenFunctionDocs: () -> Unit = {},
) {
    BackHandler(onBack = onBack)
    when (page) {
        ProfileSubpage.LOGIN -> LoginBoundaryScreen(onBack, { onOpenSubpage(ProfileSubpage.REGISTER) }, modifier)
        ProfileSubpage.REGISTER -> RegisterBoundaryScreen(onBack, { onOpenSubpage(ProfileSubpage.LOGIN) }, modifier)
        ProfileSubpage.LEGAL -> LegalDocumentsScreen(onBack, modifier)
        ProfileSubpage.DOCS -> DeveloperDocsScreen(onBack, modifier)
        ProfileSubpage.UPDATE -> UpdateStatusScreen(onBack, onOpenFunctionDocs, { onOpenSubpage(ProfileSubpage.LEGAL) }, modifier)
        ProfileSubpage.GUIDE -> StudioGuideScreen(onBack, modifier)
    }
}

@Composable
private fun LoginBoundaryScreen(onBack: () -> Unit, onRegister: () -> Unit, modifier: Modifier) {
    var identity by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var rememberPassword by remember { mutableStateOf(false) }
    var autoLogin by remember { mutableStateOf(true) }
    ProfilePageShell("账号登录", onBack, modifier, blueHeader = true) {
        item {
            Column(Modifier.padding(horizontal = 24.dp, vertical = 34.dp)) {
                Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically, modifier = Modifier.padding(bottom = 20.dp)) {
                    Card(shape = RoundedCornerShape(16.dp), colors = androidx.compose.material3.CardDefaults.cardColors(containerColor = androidx.compose.ui.graphics.Color.White)) { Text("A", modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp), color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.headlineSmall) }
                    Column(Modifier.padding(start = 14.dp)) { Text("欢迎回来", style = MaterialTheme.typography.headlineMedium); Text("登录后继续使用 AutoScript", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp)) }
                }
                Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp), colors = androidx.compose.material3.CardDefaults.cardColors(containerColor = androidx.compose.ui.graphics.Color.White)) {
                    Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text("账号", style = MaterialTheme.typography.labelLarge)
                        OutlinedTextField(value = identity, onValueChange = { identity = it }, singleLine = true, placeholder = { Text("邮箱或手机号") }, shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth())
                        Text("密码", style = MaterialTheme.typography.labelLarge)
                        OutlinedTextField(value = password, onValueChange = { password = it }, singleLine = true, placeholder = { Text("请输入密码") }, trailingIcon = { Text("显示", color = MaterialTheme.colorScheme.primary) }, shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth())
                        Row { Row(Modifier.weight(1f)) { Checkbox(rememberPassword, { rememberPassword = it }); Text("记住密码", modifier = Modifier.padding(top = 12.dp), style = MaterialTheme.typography.labelMedium) }; Row(Modifier.weight(1f)) { Checkbox(autoLogin, { autoLogin = it }); Text("自动登录", modifier = Modifier.padding(top = 12.dp), style = MaterialTheme.typography.labelMedium) } }
                        Button(onClick = {}, modifier = Modifier.fillMaxWidth()) { Text("登录") }
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) { TextButton(onClick = {}) { Text("忘记密码") }; Text("│", color = MaterialTheme.colorScheme.outlineVariant); TextButton(onClick = onRegister) { Text("注册账号") } }
                    }
                }
                Text("账号服务尚未配置，输入内容不会上传或保存。", modifier = Modifier.fillMaxWidth().padding(top = 14.dp), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun RegisterBoundaryScreen(onBack: () -> Unit, onLogin: () -> Unit, modifier: Modifier) {
    var email by remember { mutableStateOf("") }
    var verification by remember { mutableStateOf("") }
    var nickname by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var confirmation by remember { mutableStateOf("") }
    ProfilePageShell("注册账号", onBack, modifier, blueHeader = true) {
        item {
            Column(Modifier.padding(horizontal = 24.dp, vertical = 34.dp)) {
                Text("创建账号", style = MaterialTheme.typography.headlineMedium)
                Text("使用邮箱验证码注册，昵称可以稍后再设置。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp, bottom = 20.dp))
                Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp), colors = androidx.compose.material3.CardDefaults.cardColors(containerColor = androidx.compose.ui.graphics.Color.White)) {
                    Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text("邮箱", style = MaterialTheme.typography.labelLarge)
                        OutlinedTextField(value = email, onValueChange = { email = it }, placeholder = { Text("请输入邮箱地址") }, shape = RoundedCornerShape(12.dp), singleLine = true, modifier = Modifier.fillMaxWidth())
                        Text("验证码", style = MaterialTheme.typography.labelLarge)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedTextField(value = verification, onValueChange = { verification = it }, placeholder = { Text("6 位邮箱验证码") }, shape = RoundedCornerShape(12.dp), singleLine = true, modifier = Modifier.weight(1.4f))
                            Button(onClick = {}, modifier = Modifier.weight(1f).padding(top = 4.dp)) { Text("获取验证码") }
                        }
                        Text("昵称  选填", style = MaterialTheme.typography.labelLarge)
                        OutlinedTextField(value = nickname, onValueChange = { nickname = it }, placeholder = { Text("未填写时显示账号") }, shape = RoundedCornerShape(12.dp), singleLine = true, modifier = Modifier.fillMaxWidth())
                        Text("密码", style = MaterialTheme.typography.labelLarge)
                        OutlinedTextField(value = password, onValueChange = { password = it }, placeholder = { Text("8-72 位，包含字母和数字") }, trailingIcon = { Text("显示", color = MaterialTheme.colorScheme.primary) }, shape = RoundedCornerShape(12.dp), singleLine = true, modifier = Modifier.fillMaxWidth())
                        Text("确认密码", style = MaterialTheme.typography.labelLarge)
                        OutlinedTextField(value = confirmation, onValueChange = { confirmation = it }, placeholder = { Text("再次输入密码") }, trailingIcon = { Text("显示", color = MaterialTheme.colorScheme.primary) }, shape = RoundedCornerShape(12.dp), singleLine = true, modifier = Modifier.fillMaxWidth())
                        Button(onClick = {}, modifier = Modifier.fillMaxWidth()) { Text("注册") }
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) { Text("已有账号？", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant); TextButton(onClick = onLogin) { Text("去登录") } }
                    }
                }
                Text("账号服务尚未配置，输入内容不会上传或保存。", modifier = Modifier.fillMaxWidth().padding(top = 14.dp), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun LegalDocumentsScreen(onBack: () -> Unit, modifier: Modifier) {
    var selected by remember { mutableIntStateOf(0) }
    Column(modifier.fillMaxSize().background(androidx.compose.ui.graphics.Color.White)) {
        Row(Modifier.fillMaxWidth().height(64.dp).background(androidx.compose.ui.graphics.Color(0xFF3F6FF5)).padding(horizontal = 12.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("‹", color = androidx.compose.ui.graphics.Color.White, style = MaterialTheme.typography.headlineMedium) }
            Text(if (selected == 0) "用户协议" else "隐私政策", modifier = Modifier.weight(1f), color = androidx.compose.ui.graphics.Color.White, style = MaterialTheme.typography.headlineSmall)
            TextButton(onClick = { selected = 1 - selected }) { Text(if (selected == 0) "隐私" else "协议", color = androidx.compose.ui.graphics.Color.White) }
        }
        Column(
            modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 36.dp, vertical = 48.dp),
            verticalArrangement = Arrangement.spacedBy(22.dp),
        ) {
            Text(
                if (selected == 0) "AutoScript 软件许可及服务协议" else "AutoScript 隐私政策",
                style = MaterialTheme.typography.headlineMedium,
            )
            Text(
                if (selected == 0) {
                    "更新日期：2026年9月13日\n\n本协议明确您在下载、安装和使用 AutoScript 软件过程中享有的权利、应履行的义务以及双方的责任。\n\n请您在使用本软件前认真阅读并充分理解本协议，特别是自动化能力的使用限制、运行风险、责任承担和争议解决等条款。\n\n本软件仅适用于您拥有或获得明确授权的设备与内容。"
                } else {
                    "项目源码、资源、截图与运行日志默认在设备本地处理。应用不会将账号凭据或私密脚本上传至未配置的服务端。\n\n导出备份、通知和悬浮工具由用户主动触发或授权；在线后台启用前会提供独立的隐私告知与授权入口。"
                },
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                "正式上线前仍需由产品运营主体补充完整法律文本、联系方式、数据保存期限和权利请求流程。",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun DeveloperDocsScreen(onBack: () -> Unit, modifier: Modifier) {
    Box(modifier.fillMaxSize()) {
        FunctionLibraryDialog(FunctionCatalog.visualGroups(), emptySet(), onBack, onInsert = {}, documentationOnly = true)
    }
}

@Composable
private fun UpdateStatusScreen(onBack: () -> Unit, onDocs: () -> Unit, onLegal: () -> Unit, modifier: Modifier) {
    val context = LocalContext.current
    val version = remember(context) { runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull() ?: "版本未知" }
    ProfilePageShell("关于智构", onBack, modifier) {
        item {
            Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(AutoScriptDimens.CardRadius), colors = androidx.compose.material3.CardDefaults.cardColors(containerColor = androidx.compose.ui.graphics.Color.White)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                    Text("智构 · 本地自动化工作空间", fontSize = 16.sp)
                    Text(version, color = MaterialTheme.colorScheme.primary, fontSize = 14.sp)
                    Text("当前为本地开发版。在线更新、账号和市场尚未开放；不提供无效检查更新按钮。", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        item { ProfileInfoCard("技术架构", "Kotlin/Compose Studio · Rust 引擎 · PUC Lua 5.4 · 像素视觉与基础字库 OCR") }
        item { ProfileInfoCard("运行边界", "当前阶段仅 Root 后端；Studio 与独立 Runner 分离，项目能力按发布清单授权。") }
        item { ProfileInfoCard("本地存储", "项目保存在应用私有目录，通过备份槽位或 .asproject 文件显式导入导出；脚本、截图和日志不接入云同步。") }
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = onDocs, modifier = Modifier.weight(1f)) { Text("函数文档") }
                TextButton(onClick = onLegal, modifier = Modifier.weight(1f)) { Text("协议与隐私说明") }
            }
        }
    }
}

@Composable
private fun StudioGuideScreen(onBack: () -> Unit, modifier: Modifier) {
    ProfilePageShell("使用指南", onBack, modifier) {
        item { ProfileInfoCard("01 · 准备设备", "打开软件权限检查系统授权、Runner连接、Root就绪和实际输入能力。首版仅支持Root；连接成功不等于Root或截图可用。") }
        item { ProfileInfoCard("02 · 创建与编写", "首页新建或导入项目；在项目页点编辑进入可视化弹窗。插件是独立流程文件，变量和界面由项目统一维护。") }
        item { ProfileInfoCard("03 · 启动与运行", "项目列表启动会先显示设计的脚本界面；编辑器运行直接保存并执行。浮球可暂停、继续或停止，单步是独立调试入口。") }
        item { ProfileInfoCard("04 · 选点与截图", "按键可以进入系统悬浮选点；图像先截图、选范围或保存模板，再配置识别。相机只在主动打开截图模式时出现。") }
        item { ProfileInfoCard("05 · 在其他应用上编辑", "项目点编辑后打开系统悬浮窗口，首次需要允许智构显示在其他应用上层。最小化成为智构悬浮球，切到目标App后点击展开；窗口外仍可操作目标App。关闭前保存，通知可恢复编辑或保存关闭。选点、截图、录制时暂时隐藏编辑窗，草稿保留；编辑运行保留窗口，参数弹窗需先完成或取消再关闭。") }
        item { ProfileInfoCard("06 · 保存与恢复", "先保存草稿，再备份到项目槽位或导出.asproject；导入创建新项目，不覆盖已有数据。旧源码项目仍可保留和导出。") }
    }
}

@Composable
private fun ProfilePageShell(
    title: String,
    onBack: () -> Unit,
    modifier: Modifier,
    blueHeader: Boolean = false,
    trailing: String? = null,
    content: androidx.compose.foundation.lazy.LazyListScope.() -> Unit,
) {
    Column(modifier.fillMaxSize().background(androidx.compose.ui.graphics.Color(0xFFF5F6FA))) {
        Row(Modifier.fillMaxWidth().heightIn(min = 52.dp).background(androidx.compose.ui.graphics.Color.White).padding(end = 14.dp),
            verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("‹", fontSize = 25.sp, color = AutoScriptPalette.Accent) }
            Text(title, fontSize = 17.sp, modifier = Modifier.weight(1f), color = AutoScriptPalette.TextPrimary)
            trailing?.let { Text(it, fontSize = 11.sp, color = AutoScriptPalette.TextSecondary) }
        }
        androidx.compose.material3.HorizontalDivider(color = AutoScriptPalette.DividerSoft)
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), contentPadding = PaddingValues(14.dp),
            verticalArrangement = Arrangement.spacedBy(9.dp), content = content)
    }
}

@Composable
private fun ProfileInfoCard(title: String, text: String) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(AutoScriptDimens.CardRadius),
        colors = androidx.compose.material3.CardDefaults.cardColors(containerColor = androidx.compose.ui.graphics.Color.White),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, fontSize = 13.sp)
            Text(text, fontSize = 12.sp, lineHeight = 18.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
