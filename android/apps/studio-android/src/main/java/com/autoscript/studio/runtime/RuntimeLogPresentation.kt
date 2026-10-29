package com.autoscript.studio

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

internal data class RuntimeLogLinePresentation(val time: String, val message: String, val failure: Boolean, val warning: Boolean,
    val label: String)

private val scriptLevel = Regex("^脚本/(INFO|WARN|ERROR|DEBUG):\\s*")
private val executionLevel = Regex("^脚本执行/(START|RESUME|PAUSE|END|TRACE|WARN|ERROR):\\s*")
private const val logIdentity = "(?:[A-Za-z0-9_.-]{1,128}|anonymous:[a-f0-9]{16})"
private val executionIdentity = Regex(" \\[定位:$logIdentity/$logIdentity](?= · 执行失败$|$)")
private val legacyExecutionLocation = Regex("^插件 $logIdentity · 积木 $logIdentity( · 执行失败)?$")

/** Strip only recognized transport prefixes; the full original remains available in details and copy. */
internal fun runtimeLogLinePresentation(line: String, clockOffsetMs: Long? = null): RuntimeLogLinePresentation {
    val stamp = runtimeConsoleTimestamp.find(line)
    val time = stamp?.groupValues?.get(1)?.takeIf(String::isNotEmpty) ?: stamp?.groupValues?.get(2)?.toLongOrNull()?.let { value ->
        val wall = if (value >= 1_000_000_000_000L) value else clockOffsetMs?.takeIf {
            it >= 0 && value <= Long.MAX_VALUE - it
        }?.plus(value)
        if (wall != null) SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(wall))
        else "${value / 60_000}:${((value / 1000) % 60).toString().padStart(2, '0')}"
    }.orEmpty()
    var message = if (stamp == null) line else line.substring(stamp.range.last + 1)
    val source = runtimeConsoleChannel(line)
    var warning = false
    var label = "记录"
    if (source == RuntimeConsoleChannel.SCRIPT) {
        message = message.removePrefix("脚本调试日志 · ")
        val level = scriptLevel.find(message)
        warning = level?.groupValues?.get(1) == "WARN"
        label = "输出"
        if (level != null) message = message.substring(level.range.last + 1)
    } else if (source == RuntimeConsoleChannel.EXECUTION) {
        val level = executionLevel.find(message)
        warning = level?.groupValues?.get(1) == "WARN"
        label = if (level?.groupValues?.get(1) == "TRACE") "积木" else "执行"
        if (level != null) message = message.substring(level.range.last + 1)
        if (level?.groupValues?.get(1) == "TRACE" || level?.groupValues?.get(1) == "ERROR") {
            message = if (legacyExecutionLocation.matches(message)) {
                if (message.endsWith(" · 执行失败")) "积木执行失败（此记录未保存名称和行号）"
                else "执行到一条积木（此记录未保存名称和行号）"
            } else message.replace(executionIdentity, "")
        }
    } else if (source == RuntimeConsoleChannel.DIAGNOSTIC) {
        label = "诊断"
        listOf("App诊断 · ", "Runner状态 · ", "Runner诊断 · ", "诊断：").firstOrNull(message::startsWith)?.let {
            message = message.removePrefix(it)
        }
    }
    val failure = editorConsoleLineIsFailure(line)
    return RuntimeLogLinePresentation(time, message.trim().ifEmpty { line }, failure, warning,
        if (failure) "错误" else if (warning) "提醒" else label)
}
