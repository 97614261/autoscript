package com.autoscript.studio

internal enum class RuntimeConsoleChannel(val label: String) {
    ALL("全部"), SCRIPT("脚本输出"), DIAGNOSTIC("运行诊断"), OTHER("其他"),
}

private val consoleTimestamp = Regex("^(?:\\[[^\\r\\n\\]]{1,32}\\]\\s*|[0-9]{1,20}ms\\s*·\\s*)")

/** Classify only existing channel prefixes, never words inside the script's own message. */
internal fun runtimeConsoleChannel(line: String): RuntimeConsoleChannel {
    val body = line.replaceFirst(consoleTimestamp, "")
    return when {
        body.startsWith("脚本调试日志 · ") || body.startsWith("脚本/") -> RuntimeConsoleChannel.SCRIPT
        body.startsWith("Runner状态 · ") || body.startsWith("Runner诊断 · ") ||
            body.startsWith("Runner 已") || body.startsWith("Runner 连接") || body.startsWith("正在连接 Runner") ||
            body.startsWith("Root ") || body.startsWith("引擎 ") || body.startsWith("诊断：") -> RuntimeConsoleChannel.DIAGNOSTIC
        else -> RuntimeConsoleChannel.OTHER
    }
}

internal fun runtimeConsoleScriptIsError(line: String): Boolean = line.replaceFirst(consoleTimestamp, "")
    .removePrefix("脚本调试日志 · ").startsWith("脚本/ERROR:")

internal fun filterRuntimeConsole(lines: List<String>, query: String, failuresOnly: Boolean,
    channel: RuntimeConsoleChannel = RuntimeConsoleChannel.ALL): List<String> = lines.filter { line ->
    (channel == RuntimeConsoleChannel.ALL || runtimeConsoleChannel(line) == channel) &&
        line.contains(query, ignoreCase = true) && (!failuresOnly || editorConsoleLineIsFailure(line))
}
