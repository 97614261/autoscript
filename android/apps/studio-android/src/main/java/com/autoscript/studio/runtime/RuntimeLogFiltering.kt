package com.autoscript.studio

internal enum class RuntimeConsoleChannel(val label: String) {
    SYSTEM("系统日志"), ACTIVITY("脚本运行日志"),
    ESSENTIAL("运行记录"), ALL("全部记录"), SCRIPT("脚本输出"), EXECUTION("执行记录"), DIAGNOSTIC("内部诊断"), OTHER("其他"),
}

internal val runtimeConsoleTimestamp = Regex("^(?:\\[([^\\r\\n\\]]{1,32})\\]\\s*|([0-9]{1,20})(?:ms)?\\s*·\\s*)")

internal fun runtimeLogDiagnosticRecords(text: String): List<String> = buildList {
    text.lineSequence().filter(String::isNotBlank).forEach { line ->
        if (isEmpty() || runtimeConsoleTimestamp.containsMatchIn(line) || line.startsWith("Runner错误码 · ")) add(line)
        else this[lastIndex] = last() + "\n" + line
    }
}

/** Classify only existing channel prefixes, never words inside the script's own message. */
internal fun runtimeConsoleChannel(line: String): RuntimeConsoleChannel {
    val body = line.replaceFirst(runtimeConsoleTimestamp, "")
    return when {
        body.startsWith("脚本调试日志 · ") || body.startsWith("脚本/") || body.startsWith("脚本动作/") -> RuntimeConsoleChannel.SCRIPT
        body.startsWith("脚本执行/") -> RuntimeConsoleChannel.EXECUTION
        body.startsWith("App诊断 · ") || body.startsWith("Runner状态 · ") || body.startsWith("Runner诊断 · ") ||
            body.startsWith("Runner 已") || body.startsWith("Runner 连接") || body.startsWith("正在连接 Runner") ||
            body.startsWith("Root ") || body.startsWith("引擎 ") || body.startsWith("诊断：") -> RuntimeConsoleChannel.DIAGNOSTIC
        else -> RuntimeConsoleChannel.OTHER
    }
}

internal fun runtimeConsoleScriptIsError(line: String): Boolean = line.replaceFirst(runtimeConsoleTimestamp, "")
    .removePrefix("脚本调试日志 · ").let { it.startsWith("脚本/ERROR:") || it.startsWith("脚本动作/ERROR:") }

internal fun runtimeConsoleExecutionIsError(line: String): Boolean = line.replaceFirst(runtimeConsoleTimestamp, "")
    .startsWith("脚本执行/ERROR:")

/** The live console mirrors persisted state; do not render a second copy of that mirror. */
internal fun runtimeLogCurrentSystemLines(system: List<String>, current: List<String>): List<String> {
    val saved = system.map { runtimeLogLinePresentation(it).message.removePrefix("运行连接 · ") }.toSet()
    return system + current.filterNot { line ->
        val body = line.replaceFirst(runtimeConsoleTimestamp, "")
        body.startsWith("Runner状态 · ") || body.startsWith("Runner诊断 · ") ||
            (body.startsWith("脚本执行/") && !body.startsWith("脚本执行/ERROR:")) ||
            (runtimeConsoleChannel(line) != RuntimeConsoleChannel.SCRIPT &&
                runtimeLogLinePresentation(line).message in saved)
    }
}

private val diagnosticFailureCode = Regex("\\b[A-Z][A-Z0-9_]*(?:FAILED|FAILURE|ERROR|DENIED|TIMEOUT|NOT_READY|DISCONNECTED|UNAVAILABLE|EXCEEDED|INVALID)\\b")

internal fun runtimeConsoleDiagnosticIsFailure(line: String): Boolean {
    if (runtimeConsoleChannel(line) != RuntimeConsoleChannel.DIAGNOSTIC) return false
    com.autoscript.runtime.api.RuntimeSystemMessages.diagnosticIsFailure(line.replaceFirst(runtimeConsoleTimestamp, ""))?.let { return it }
    return diagnosticFailureCode.containsMatchIn(line) || line.contains("失败") || line.contains("超时") || line.contains("拒绝")
}

internal fun filterRuntimeConsole(lines: List<String>, query: String, failuresOnly: Boolean,
    channel: RuntimeConsoleChannel = RuntimeConsoleChannel.ALL, checkpoint: () -> Unit = {}): List<String> = buildList {
    lines.forEachIndexed { index, line ->
        if (index % 128 == 0) checkpoint()
        val source = runtimeConsoleChannel(line)
        val failure = editorConsoleLineIsFailure(line)
        val matchesChannel = when (channel) {
            RuntimeConsoleChannel.ESSENTIAL -> source != RuntimeConsoleChannel.DIAGNOSTIC || failure
            RuntimeConsoleChannel.ALL -> true
            RuntimeConsoleChannel.SYSTEM -> source !in setOf(RuntimeConsoleChannel.SCRIPT, RuntimeConsoleChannel.EXECUTION) ||
                failure || (source == RuntimeConsoleChannel.EXECUTION && !line.contains("脚本执行/TRACE:"))
            RuntimeConsoleChannel.ACTIVITY -> source == RuntimeConsoleChannel.SCRIPT ||
                source == RuntimeConsoleChannel.EXECUTION && (line.contains("脚本执行/TRACE:") || line.contains("脚本执行/WARN:"))
            else -> source == channel
        }
        if (matchesChannel && line.contains(query, ignoreCase = true) && (!failuresOnly || failure)) add(line)
    }
    checkpoint()
}
