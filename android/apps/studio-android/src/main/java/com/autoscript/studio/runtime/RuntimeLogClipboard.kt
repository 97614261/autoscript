package com.autoscript.studio

/** Reject oversized selections explicitly, before joining or passing a large Binder payload. */
internal fun runtimeLogCopyText(lines: List<String>): String {
    var length = 0L
    lines.forEachIndexed { index, line ->
        length += line.length.toLong() + if (index == 0) 0 else 1
        require(length <= 128 * 1024) { "日志超过复制容量，请筛选或导出" }
    }
    return lines.joinToString("\n").also { require(it.toByteArray(Charsets.UTF_8).size <= 128 * 1024) }
}

/** No log text is included in status messages, including a mismatched clipboard readback. */
internal fun runtimeLogClipboardStatus(text: String, write: (String) -> Unit, read: () -> String?): String {
    return try {
        write(text)
        val actual = read()
        when {
            actual == null -> "已发送复制请求，系统未允许回读校验；可导出原日志。"
            actual != text -> "剪贴板内容校验不一致，请重试或导出原日志。"
            else -> "已复制并校验完整报告"
        }
    } catch (_: Exception) { "复制或回读失败，请导出原日志查看。" }
}
