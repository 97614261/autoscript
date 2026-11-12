package com.autoscript.studio

import android.app.Instrumentation
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.*
import com.autoscript.core.designsystem.AutoScriptTheme
import com.autoscript.project.store.*
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.KeyStore
import java.util.UUID

/** Only the local Go service, a separate account namespace and owned project directory are used. */
internal object CloudBackupAcceptanceFixture {
    fun run(test: Instrumentation, activity: ComponentActivity, origin: String, layoutOnly: Boolean = false,
        record: (String, () -> Unit) -> Unit) {
        require(origin.matches(Regex("http://127\\.0\\.0\\.1:[0-9]{2,5}"))) { "QA_LOCAL_ORIGIN_REQUIRED" }
        val suffix = UUID.randomUUID().toString()
        val namespace = "studio-account-qa-$suffix"
        val sessionStore = StudioAccountStore(test.targetContext, namespace)
        val name = "qa_" + suffix.replace("-", "").take(16)
        val password = "Public-QA-2026!"
        val directory = File(test.targetContext.cacheDir, "cloud-backup-qa-$suffix")
        val store = ProjectStore(File(directory, "projects"), idFactory = { "project-qa-${UUID.randomUUID()}" })
        var project = store.createProject("公开云备份测试", ProjectSourceMode.VISUAL, initialCapabilities = setOf("core.task"))
        val flow = project.manifest.flows.single()
        fun source(milliseconds: Int) = JSONObject().put("flowSchemaVersion", 1).put("nodeVersion", 1)
            .put("nodeId", "node-1").put("blockId", flow.rootBlockId).put("parentId", JSONObject.NULL)
            .put("orderKey", "a0").put("depth", 0).put("kind", "task.sleep")
            .put("args", JSONObject().put("milliseconds", milliseconds)).toString() + "\n"
        project = store.saveFlows(project, mapOf(flow.flowId to source(300)))
        val bitmap = Bitmap.createBitmap(16, 12, Bitmap.Config.ARGB_8888).apply { eraseColor(0xff39a5d8.toInt()) }
        val image = ByteArrayOutputStream().also { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }.toByteArray()
        bitmap.recycle()
        project = store.importResource(project.manifest.projectId, ProjectResourceKind.IMAGE, "公开图片.png", image.inputStream(), emptySet())
        var projects by mutableStateOf(store.listProjects())
        var restored = 0
        var revision by mutableIntStateOf(0)
        var first: StudioAccountSession? = null
        var latest: StudioAccountSession? = null
        var other: StudioAccountSession? = null
        val backupIds = linkedSetOf<String>()
        val ui = UsageTestUi(test)
        fun field(label: String, text: String) {
            ui.reveal(label)
            fun editable(): AccessibilityNodeInfo? {
                val anchor = ui.label(label) ?: return null
                val bounds = try { Rect().also(anchor::getBoundsInScreen) } finally { anchor.recycle() }
                return ui.find { it.isEditable && Rect.intersects(bounds, Rect().also(it::getBoundsInScreen)) }
            }
            ui.await("cloud field:$label") { editable()?.let { it.recycle(); true } == true }
            val node = requireNotNull(editable())
            try { check(node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
            })) } finally { node.recycle() }
            ui.await("cloud input committed:$label") { editable()?.let { current -> try {
                if (current.isPassword) current.text?.length == text.length else current.text?.toString() == text
            } finally { current.recycle() } } == true }
        }
        fun login() {
            field("密码", password); ui.reveal("登录"); ui.clickButton("登录")
            ui.wait("账号云备份"); ui.wait("共 ${backupIds.size} 份服务器备份")
            latest = requireNotNull(sessionStore.read())
        }
        fun client(value: StudioAccountSession) = StudioCloudClient(origin, true, value)
        test.runOnMainSync { activity.setContent { AutoScriptTheme {
            key(revision) {
                CloudProjectBackupsScreen(store, projects, { false }, onRestored = {
                    projects = store.listProjects(); restored++
                }, onBack = {}, sessionStore = sessionStore)
            }
        } } }
        try {
            record("cloud-real-ui-register-and-login-local-mysql-redis") {
                ui.wait("账号与云备份")
                if (layoutOnly) ui.screenshot("cloud-final-login")
                field("账号服务器地址", origin); field("账号", name); ui.clickButton("注册账号")
                field("密码", password); field("确认密码", password)
                ui.reveal("允许 HTTP 调试（账号密码会明文传输）")
                val checkbox = requireNotNull(ui.find { it.isCheckable && !it.isChecked })
                val bounds = try { Rect().also(checkbox::getBoundsInScreen) } finally { checkbox.recycle() }
                ui.tap(bounds); ui.reveal("注册"); ui.clickButton("注册")
                ui.wait("注册成功，请登录。"); login(); first = latest
                check(first!!.userName == name && first!!.origin.trimEnd('/') == origin)
            }
            record("cloud-keystore-session-encryption-and-reopen") {
                val saved = requireNotNull(first)
                val values = test.targetContext.getSharedPreferences(namespace, Context.MODE_PRIVATE).all.values
                check(values.none { it.toString().contains(saved.token) || it.toString().contains(saved.backupKey) })
                test.runOnMainSync { revision++ }
                ui.wait("账号云备份"); ui.wait("共 0 份服务器备份")
                check(sessionStore.read() == saved)
                if (layoutOnly) ui.screenshot("cloud-final-account")
            }
            if (layoutOnly) return
            record("cloud-real-ui-encrypted-script-and-image-upload") {
                ui.clickButton("选择本机项目"); ui.clickButton("公开云备份测试")
                ui.clickButton("上传备份"); ui.clickButton("新增备份")
                ui.wait("已新增备份；不包含未保存草稿和运行数据。")
                val uploaded = client(requireNotNull(latest)).use { it.list().single() }
                backupIds += uploaded.id
                check(ServerBackupCrypto.decryptName(uploaded.label, latest!!.backupKey, uploaded.id) == "公开云备份测试")
                ui.screenshot("cloud-backup-uploaded")
            }
            record("cloud-real-ui-overwrite-keeps-one-snapshot") {
                val before = client(requireNotNull(latest)).use { it.list().single() }
                project = store.saveFlows(project, mapOf(flow.flowId to source(450)))
                ui.clickButton("上传备份"); ui.clickButton("确认覆盖")
                ui.wait("已覆盖所选备份；其余备份保留。")
                val after = client(requireNotNull(latest)).use { it.list().single() }
                check(after.id == before.id && after.sha256 != before.sha256)
            }
            record("cloud-real-ui-restore-new-project-with-identical-script-and-image") {
                ui.clickButton("恢复为新项目"); ui.clickButton("恢复")
                ui.wait("已恢复为新项目，原项目保持不变。")
                check(restored == 1)
                val copies = store.listProjects(); check(copies.size == 2)
                val copy = store.openProject(copies.single { it.projectId != project.manifest.projectId }.projectId)
                check(copy.flowSources.values.toList() == project.flowSources.values.toList())
                val path = copy.manifest.resources.single()["path"].asString
                check(File(copy.directory, path).readBytes().contentEquals(image))
                check(store.openProject(project.manifest.projectId).flowSources == project.flowSources)
            }
            record("cloud-second-account-cannot-list-download-or-delete-first-backup") {
                other = StudioCloudClient(origin, true).use { api ->
                    api.register(name + "_b", password, password); api.login(name + "_b", password, true)
                }
                val item = client(requireNotNull(latest)).use { it.list().single() }
                client(requireNotNull(other)).use { api ->
                    check(api.list().isEmpty())
                    val file = File(directory, "forbidden.zgb")
                    check((runCatching { api.download(item, file) }.exceptionOrNull() as? StudioCloudFailure)?.code == 404)
                    check((runCatching { api.delete(item) }.exceptionOrNull() as? StudioCloudFailure)?.code == 404)
                }
            }
            record("cloud-real-ui-logout-revokes-token-and-preserves-local-projects") {
                ui.clickButton("退出账号"); ui.clickButton("退出")
                ui.wait("已退出账号，本机项目保留。")
                check(sessionStore.read() == null && store.listProjects().size == 2)
                check((runCatching { client(requireNotNull(first)).use { it.list() } }.exceptionOrNull() as? StudioCloudFailure)?.code == 401)
                login(); check(latest!!.token != first!!.token && latest!!.backupKey == first!!.backupKey)
            }
            record("cloud-server-session-expiry-returns-real-ui-to-login") {
                client(requireNotNull(latest)).use { it.logout() }
                ui.clickButton("刷新列表"); ui.wait("登录已过期，请重新登录。")
                check(sessionStore.read() == null)
                login()
            }
            record("cloud-real-ui-delete-backup-keeps-local-projects") {
                ui.clickButton("删除备份"); ui.clickButton("删除")
                ui.wait("服务器快照已删除，本机项目不变。")
                check(client(requireNotNull(latest)).use { it.list().isEmpty() })
                backupIds.clear(); check(store.listProjects().size == 2)
                ui.screenshot("cloud-backup-empty")
            }
        } finally {
            test.runOnMainSync { activity.setContent {} }
            val cleanupSession = latest ?: first
            if (cleanupSession != null) {
                client(cleanupSession).use { api ->
                    val items = runCatching { api.list() }.getOrDefault(emptyList())
                    items.filter { it.id in backupIds }.forEach { api.delete(it) }
                    runCatching { api.logout() }
                }
                test.targetContext.getSharedPreferences("personal-project-backup", Context.MODE_PRIVATE).edit()
                    .remove(serverBackupAssociationKey(cleanupSession.origin, cleanupSession.backupKey, project.manifest.projectId)).commit()
            }
            other?.let { session -> runCatching { client(session).use { it.logout() } } }
            check(test.targetContext.getSharedPreferences(namespace, Context.MODE_PRIVATE).edit().clear().commit())
            KeyStore.getInstance("AndroidKeyStore").apply { load(null); deleteEntry("$namespace-session-v1") }
            check(directory.canonicalFile.parentFile == test.targetContext.cacheDir.canonicalFile && directory.name == "cloud-backup-qa-$suffix")
            check(directory.deleteRecursively())
        }
    }
}
