package com.autoscript.studio

import android.app.ActivityManager
import android.app.Application
import android.app.ApplicationExitInfo
import android.os.Build
import android.os.Process
import com.autoscript.runtime.api.RuntimeSystemLog

class StudioApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        val process = if (Build.VERSION.SDK_INT >= 28) getProcessName() else "进程 ${Process.myPid()}"
        RuntimeSystemLog.install(filesDir, process)
        if (Build.VERSION.SDK_INT >= 30 && !process.endsWith(":runner")) {
            Thread({ runCatching { collectSystemExits() } }, "system-exit-log").apply { isDaemon = true; start() }
        }
    }

    @androidx.annotation.RequiresApi(30)
    private fun collectSystemExits() {
        val preferences = getSharedPreferences("system-exit-log", MODE_PRIVATE)
        val previous = preferences.getLong("lastTimestamp", 0)
        val records = getSystemService(ActivityManager::class.java)
            .getHistoricalProcessExitReasons(packageName, 0, 8).filter { it.timestamp > previous }.sortedBy { it.timestamp }
        records.forEach { record ->
            val reason = when (record.reason) {
                ApplicationExitInfo.REASON_CRASH -> "应用异常退出"
                ApplicationExitInfo.REASON_CRASH_NATIVE -> "原生进程闪退"
                ApplicationExitInfo.REASON_ANR -> "应用无响应（ANR）"
                else -> null
            }
            val saved = reason == null || RuntimeSystemLog.record(filesDir, "ERROR",
                "$reason · ${record.processName} · 系统退出码 ${record.status}\n${record.description.orEmpty().take(2048)}", record.timestamp)
            if (!saved) return
            preferences.edit().putLong("lastTimestamp", record.timestamp).apply()
        }
    }
}
