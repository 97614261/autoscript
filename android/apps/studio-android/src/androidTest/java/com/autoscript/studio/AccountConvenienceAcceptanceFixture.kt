package com.autoscript.studio

import android.app.Instrumentation
import android.content.Context
import android.graphics.Rect
import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import com.autoscript.core.designsystem.AutoScriptTheme
import com.autoscript.project.store.*
import com.autoscript.runtime.client.RuntimeClient
import java.io.File
import java.security.KeyStore
import java.util.UUID

internal object AccountConvenienceAcceptanceFixture {
    fun run(test: Instrumentation, activity: ComponentActivity, client: RuntimeClient, runtimeStore: ProjectStore,
        initial: ProjectSnapshot, origin: String, record: (String, () -> Unit) -> Unit): ProjectSnapshot {
        require(origin.matches(Regex("http://127\\.0\\.0\\.1:[0-9]{2,5}")))
        val suffix = UUID.randomUUID().toString()
        val namespace = "studio-account-qa-easy-$suffix"
        val sessions = StudioAccountStore(test.targetContext, namespace)
        val directory = File(test.targetContext.cacheDir, namespace)
        val store = ProjectStore(File(directory, "projects"))
        val name = "qa_" + suffix.replace("-", "").take(16)
        val password = "Public-QA-2026!"
        val ui = UsageTestUi(test)
        val rt = OwnedUsageRuntime(client, runtimeStore, initial)
        var session: StudioAccountSession? = null
        val backups = mutableListOf<ServerBackupRecord>()
        var revision by mutableIntStateOf(0)
        var skipped by mutableStateOf(false)
        fun api(active: StudioAccountSession? = session) = StudioCloudClient(origin, true, active)
        fun field(label: String): AccessibilityNodeInfo? {
            val anchor = ui.label(label) ?: return null
            val rect = try { Rect().also(anchor::getBoundsInScreen) } finally { anchor.recycle() }
            return ui.find { it.isEditable && Rect.intersects(rect, Rect().also(it::getBoundsInScreen)) }
        }
        fun fill(label: String, value: String) {
            ui.reveal(label)
            val node = requireNotNull(field(label))
            try { check(node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value)
            })) } finally { node.recycle() }
            ui.await("input:$label") { field(label)?.let { current -> try {
                if (current.isPassword) current.text?.length == value.length else current.text?.toString() == value
            } finally { current.recycle() } } == true }
        }
        fun masked(expected: Boolean) = ui.await("password mask:$expected") {
            field("密码")?.let { try { it.isPassword == expected } finally { it.recycle() } } == true
        }
        fun render() = test.runOnMainSync { activity.setContent { AutoScriptTheme { key(revision) {
            if (skipped) Text("公开本机项目") else CloudProjectBackupsScreen(store, store.listProjects(), { false }, {},
                { skipped = true }, sessionStore = sessions, accountOnly = true)
        } } } }
        try {
            api(null).use { it.register(name, password, password); session = it.login(name, password, true) }
            sessions.save(requireNotNull(session)); api().use { it.logout() }; sessions.clear()
            record("remember-successful-user-without-password-or-session") {
                check(sessions.lastUserName() == name && sessions.lastOrigin() == requireNotNull(session).origin && sessions.read() == null)
                val prefs = test.targetContext.getSharedPreferences(namespace, Context.MODE_PRIVATE).all
                check(prefs.keys == setOf("origin", "lastUserName"))
                render();ui.wait("账号与云备份")
                ui.await("remembered username") { field("账号")?.let { try { it.text?.toString() == name } finally { it.recycle() } } == true }
                check(requireNotNull(field("密码")).let { try { it.text.isNullOrEmpty() } finally { it.recycle() } })
            }
            record("password-toggle-and-real-login-logout-keeps-username") {
                fill("密码", password); masked(true)
                ui.rowAction("密码", "显示"); masked(false)
                ui.rowAction("密码", "隐藏"); masked(true)
                ui.reveal("允许 HTTP 调试（账号密码会明文传输）")
                val checkBox = requireNotNull(ui.find { it.isCheckable })
                try { ui.tap(Rect().also(checkBox::getBoundsInScreen)) } finally { checkBox.recycle() }
                ui.clickButton("登录");ui.wait("我的账号");session = requireNotNull(sessions.read())
                ui.clickButton("退出账号");ui.clickButton("退出");ui.wait("账号与云备份")
                check(sessions.read() == null && sessions.lastUserName() == name)
                masked(true)
            }
            record("guest-exit-keeps-local-edit-compile-and-runtime-available") {
                ui.clickButton("暂不登录");ui.wait("公开本机项目");check(sessions.read() == null)
                rt.variables("公开结果" to ProjectVariableType.INTEGER)
                rt.run(listOf(rt.set("公开结果", "40 + 2"))) { check(it["公开结果"] == "42") }
            }
            val first = store.createProject("本机甲_完整项目名称", ProjectSourceMode.VISUAL)
            store.createProject("本机乙_其他项目", ProjectSourceMode.VISUAL)
            session = api(null).use { it.login(name, password, true) }; sessions.save(requireNotNull(session))
            for (label in listOf("云端甲_公开备份", "云端乙_其他备份")) {
                val id = ServerBackupCrypto.id()
                val zip = File(directory, "$id.zip")
                zip.outputStream().use { store.exportProjectBackup(first.manifest.projectId, it) }
                val encrypted = File(directory, "$id.zgb")
                zip.inputStream().use { source -> encrypted.outputStream().use { target ->
                    ServerBackupCrypto.encrypt(source, target, requireNotNull(session).backupKey, id)
                } }
                backups += api().use { it.upload(encrypted, id,
                    ServerBackupCrypto.encryptName(label, requireNotNull(session).backupKey, id)) }
            }
            test.runOnMainSync { skipped = false; revision++ };render()
            ui.wait("我的账号");ui.clickButton("云备份与恢复");ui.wait("共 2 份服务器备份")
            record("backup-name-search-clear-and-empty-results") {
                fill("搜索备份名称", "云端甲");ui.wait("找到 1 / 2 份备份");ui.wait("云端甲_公开备份");ui.absent("云端乙_其他备份")
                fill("搜索备份名称", "不存在");ui.wait("找到 0 / 2 份备份")
                ui.clickButton("清空");ui.wait("云端甲_公开备份");ui.wait("云端乙_其他备份")
            }
            record("project-name-search-select-full-name-and-cancel-upload") {
                ui.clickButton("选择本机项目");fill("搜索本机项目", "本机甲")
                ui.wait("本机甲_完整项目名称");ui.absent("本机乙_其他项目")
                ui.clickButton("本机甲_完整项目名称");ui.clickButton("上传备份")
                ui.wait("本机项目：本机甲_完整项目名称");ui.clickButton("取消")
                check(api().use { it.list() }.size == 2)
                ui.screenshot("account-convenience-final")
            }
            return runtimeStore.openProject(rt.id)
        } finally {
            rt.stop();test.runOnMainSync { activity.setContent {} }
            session?.let { active -> api(active).use { connection -> backups.forEach(connection::delete);runCatching { connection.logout() } } }
            sessions.clear()
            check(test.targetContext.getSharedPreferences(namespace, Context.MODE_PRIVATE).edit().clear().commit())
            KeyStore.getInstance("AndroidKeyStore").apply { load(null);deleteEntry("$namespace-session-v1") }
            check(directory.canonicalFile.parentFile == test.targetContext.cacheDir.canonicalFile)
            if (directory.exists()) check(directory.deleteRecursively())
        }
    }
}
