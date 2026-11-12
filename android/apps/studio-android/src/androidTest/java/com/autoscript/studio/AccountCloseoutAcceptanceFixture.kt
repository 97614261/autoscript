package com.autoscript.studio

import android.app.Instrumentation
import android.content.Context
import android.content.pm.ActivityInfo
import android.graphics.Rect
import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.*
import com.autoscript.core.designsystem.AutoScriptTheme
import com.autoscript.project.store.ProjectStore
import kotlinx.coroutines.runBlocking
import java.io.File
import java.security.KeyStore
import java.util.UUID

/** New account controls only; existing normal backup round trips are not repeated. */
internal object AccountCloseoutAcceptanceFixture {
    fun run(test: Instrumentation, activity: ComponentActivity, origin: String, record: (String, () -> Unit) -> Unit,
        registerOnly: Boolean = false, backupsOnly: Boolean = false) {
        require(origin.matches(Regex("http://127\\.0\\.0\\.1:[0-9]{2,5}")))
        val suffix = UUID.randomUUID().toString()
        val namespace = "studio-account-qa-closeout-$suffix"
        val sessions = StudioAccountStore(test.targetContext, namespace)
        val directory = File(test.targetContext.cacheDir, namespace)
        val store = ProjectStore(File(directory, "projects"))
        val name = "qa_" + suffix.replace("-", "").take(16)
        val password = "Public-QA-2026!"
        val updated = "Public-QA-2026-Changed!"
        val originalOrientation = activity.requestedOrientation
        var revision by mutableIntStateOf(0)
        val ui = UsageTestUi(test)
        var session: StudioAccountSession? = null
        var seeded: ServerBackupRecord? = null
        var ownedProjects = emptyList<com.autoscript.project.store.ProjectSummary>()
        fun api(active: StudioAccountSession? = session) = StudioCloudClient(origin, true, active)
        fun render() = test.runOnMainSync { activity.setContent { AutoScriptTheme { key(revision) {
            CloudProjectBackupsScreen(store, ownedProjects, { false }, {}, {}, sessionStore = sessions, accountOnly = true)
        } } } }
        fun field(label: String): AccessibilityNodeInfo? {
            val anchor = ui.label(label) ?: return null
            val bounds = try { Rect().also(anchor::getBoundsInScreen) } finally { anchor.recycle() }
            return ui.find { it.isEditable && Rect.intersects(bounds, Rect().also(it::getBoundsInScreen)) }
        }
        fun fill(label: String, text: String) {
            ui.reveal(label)
            val node = requireNotNull(field(label))
            try {
                node.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SHOW_ON_SCREEN.id)
                check(node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply {
                    putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
                }))
                ui.await("account input:$label") { node.refresh() &&
                    if (node.isPassword) node.text?.length == text.length else node.text?.toString() == text }
            } finally { node.recycle() }
        }
        fun keyboardBounds(): Rect? {
            fun find(node: AccessibilityNodeInfo): Rect? {
                try {
                    if (node.packageName?.toString() != "com.autoscript.vision.acceptance") return null
                    if (node.contentDescription?.toString() == "QA键盘面板") return Rect().also(node::getBoundsInScreen)
                    for (index in 0 until node.childCount) node.getChild(index)?.let { find(it)?.let { found -> return found } }
                    return null
                } finally { node.recycle() }
            }
            return test.uiAutomation.windows.asSequence().mapNotNull { it.root?.let(::find) }.firstOrNull()
        }
        fun keyboardOn(label: String, button: String) {
            ui.reveal(label)
            val node = requireNotNull(field(label))
            try { node.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SHOW_ON_SCREEN.id) } finally { node.recycle() }
            android.os.SystemClock.sleep(200)
            val target = requireNotNull(field(label))
            val bounds = try { Rect().also(target::getBoundsInScreen) } finally { target.recycle() }
            ui.tap(bounds)
            ui.await("real QA keyboard") { keyboardBounds()?.height()?.let { it > 0 } == true }
            ui.await("action above keyboard:$button") {
                val action = ui.label(button) ?: return@await false
                val actionBounds = try { Rect().also(action::getBoundsInScreen) } finally { action.recycle() }
                actionBounds.height() > 0 && actionBounds.bottom <= requireNotNull(keyboardBounds()).top
            }
        }
        fun hideKeyboard() {
            test.runOnMainSync {
                val view = activity.currentFocus ?: activity.window.decorView
                (activity.getSystemService(Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager)
                    .hideSoftInputFromWindow(view.windowToken, 0)
                view.clearFocus()
            }
            ui.await("keyboard hidden") { keyboardBounds() == null }
        }
        try {
            api(null).use { it.register(name, password, password); session = it.login(name, password, true) }
            runBlocking { sessions.save(requireNotNull(session)) }
            if (backupsOnly) {
                val project = store.createProject("公开横屏备份检查", com.autoscript.project.store.ProjectSourceMode.VISUAL)
                ownedProjects = store.listProjects()
                val zip = File(directory, "owned.zip")
                zip.outputStream().use { store.exportProjectBackup(project.manifest.projectId, it) }
                val encrypted = File(directory, "owned.zgb")
                val id = ServerBackupCrypto.id()
                zip.inputStream().use { input -> encrypted.outputStream().use { ServerBackupCrypto.encrypt(input, it, requireNotNull(session).backupKey, id) } }
                seeded = api().use { it.upload(encrypted, id, ServerBackupCrypto.encryptName(project.manifest.name, requireNotNull(session).backupKey, id)) }
                render()
                record("backup-landscape-list-and-primary-actions") {
                    test.runOnMainSync { activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE }
                    ui.await("landscape configuration") { activity.resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE }
                    ui.wait("我的账号");ui.clickButton("云备份与恢复");ui.wait("共 1 份服务器备份")
                    ui.wait(project.manifest.name);ui.wait("恢复为新项目");ui.wait("删除备份")
                    ui.screenshot("account-landscape-backup-list")
                }
                record("backup-landscape-choose-upload-restore-delete-dialogs-cancel") {
                    ui.clickButton("选择本机项目");ui.clickButton(project.manifest.name)
                    ui.clickButton("上传备份");ui.wait("新增备份");ui.screenshot("account-landscape-backup-upload");ui.clickButton("取消")
                    ui.clickButton("恢复为新项目");ui.wait("恢复");ui.screenshot("account-landscape-backup-restore");ui.clickButton("取消")
                    ui.clickButton("删除备份");ui.wait("删除");ui.clickButton("取消")
                    check(store.listProjects().single().projectId == project.manifest.projectId)
                    check(api().use { it.list() }.single().id == requireNotNull(seeded).id)
                }
                return
            }
            if (registerOnly) {
                sessions.clear();render()
                record("account-landscape-register-all-fields-scroll-and-fixed-submit") {
                    test.runOnMainSync { activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE }
                    ui.await("landscape configuration") { activity.resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE }
                    ui.wait("账号与云备份");ui.clickButton("注册账号")
                    keyboardOn("确认密码", "注册");ui.screenshot("account-landscape-register-keyboard");hideKeyboard()
                }
                return
            }
            render()
            record("account-portrait-entry-and-actions") {
                ui.wait("我的账号");ui.wait(name);ui.wait("云备份与恢复");ui.wait("修改密码");ui.wait("退出账号")
            }
            record("account-landscape-password-fields-and-real-keyboard-actions") {
                test.runOnMainSync { activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE }
                ui.await("landscape configuration") { activity.resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE }
                ui.clickButton("修改密码")
                for (label in listOf("原密码", "新密码", "确认新密码")) keyboardOn(label, "保存密码")
                ui.screenshot("account-landscape-password-keyboard")
                hideKeyboard()
            }
            record("account-password-validation-preserves-session") {
                ui.clickButton("保存密码")
                ui.wait("请填写原密码；新密码需为8到72字节、两次一致且与原密码不同。")
                check(sessions.read() == session)
            }
            record("account-real-ui-password-change-revokes-old-session-keeps-key") {
                fill("原密码", password);fill("新密码", updated);fill("确认新密码", updated)
                ui.clickButton("保存密码")
                ui.wait("密码已修改，所有旧登录已失效，请重新登录。")
                check(sessions.read() == null)
                check((runCatching { api().use { it.list() } }.exceptionOrNull() as? StudioCloudFailure)?.code == 401)
                val next = api(null).use { it.login(name, updated, true) }
                check(next.backupKey == requireNotNull(session).backupKey)
                api(next).use { it.logout() }
            }
            record("account-landscape-login-real-keyboard-and-fixed-submit") {
                fill("密码", updated);keyboardOn("密码", "登录")
                ui.screenshot("account-landscape-login-keyboard")
                hideKeyboard();ui.clickButton("登录");ui.wait("我的账号")
                session = requireNotNull(sessions.read())
            }
            record("account-landscape-register-all-fields-scroll-and-fixed-submit") {
                api().use { it.logout() };sessions.clear()
                test.runOnMainSync { revision++ };ui.wait("账号与云备份");ui.clickButton("注册账号")
                keyboardOn("确认密码", "注册");ui.screenshot("account-landscape-register-keyboard");hideKeyboard()
                ui.clickButton("返回登录")
            }
        } catch (failure: Throwable) {
            runCatching { ui.screenshot("account-closeout-failure") }
            throw failure
        } finally {
            runCatching { hideKeyboard() }
            test.runOnMainSync { activity.setContent {};activity.requestedOrientation = restoredScreenOrientation(originalOrientation) }
            session?.let { active ->
                seeded?.let { backup -> api(active).use { it.delete(backup) } }
                runCatching { api(active).use { client -> client.logout() } }
            }
            sessions.clear()
            check(test.targetContext.getSharedPreferences(namespace, Context.MODE_PRIVATE).edit().clear().commit())
            KeyStore.getInstance("AndroidKeyStore").apply { load(null);deleteEntry("$namespace-session-v1") }
            if (directory.exists()) {
                check(directory.canonicalFile.parentFile == test.targetContext.cacheDir.canonicalFile)
                check(directory.deleteRecursively())
            }
        }
    }
}
