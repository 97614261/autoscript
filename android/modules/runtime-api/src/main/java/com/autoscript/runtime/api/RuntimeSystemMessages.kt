package com.autoscript.runtime.api

/** Stable translations for the existing code-only diagnostic channel. */
object RuntimeSystemMessages {
    private val messages = mapOf(
        "ROOT_START" to "正在按需连接 Root", "ROOT_INSTALLED_DAEMON" to "Root 组件已准备",
        "ROOT_EXTRACTED_DAEMON" to "Root 组件已解压", "ROOT_DIRECTORY_FAILED" to "Root 工作目录不可用",
        "ROOT_DAEMON_MISSING" to "Root 组件缺失", "ROOT_KEY_FAILED" to "Root 会话认证信息准备失败",
        "ROOT_SU_START_FAILED" to "无法启动 Root 进程", "ROOT_START_TIMEOUT" to "Root 启动超时",
        "ROOT_ATTACH_FAILED" to "Root 会话连接失败", "ROOT_READY" to "Root 已连接并验证",
        "ROOT_DISCONNECTED" to "Root 连接已断开", "ROOT_RETRIES_EXHAUSTED" to "Root 连接重试结束",
        "ROOT_PROCESS_EXITED" to "Root 进程已退出", "ROOT_PROCESS_PERMISSION_DENIED" to "Root 进程权限被拒绝",
        "ROOT_PROCESS_LINK_FAILED" to "Root 组件与当前系统不兼容", "ROOT_PROCESS_COMMAND_MISSING" to "Root 启动命令不可用",
        "ROOT_IDLE_RELEASED" to "Root 因空闲释放，下次使用时按需连接",
        "CAPTURE_REQUESTED" to "已请求截图", "CAPTURE_SESSION_CHANGED" to "截图会话已变化，请重新截图",
        "CAPTURE_ROOT_NOT_READY" to "截图失败：Root 尚未就绪", "CAPTURE_RUNTIME_BUSY" to "截图通道正在使用",
        "CAPTURE_NATIVE_UNAVAILABLE" to "截图组件不可用", "CAPTURE_BACKEND_FAILED" to "系统截图采集失败",
        "CAPTURE_INVALID_FRAME" to "截图画面格式无效或超出预算", "CAPTURE_READY" to "截图已生成",
        "CAPTURE_DEADLINE" to "截图采集超时", "CAPTURE_PROCESS_START" to "截图进程无法启动",
        "CAPTURE_PIPE_READ" to "截图数据读取失败", "CAPTURE_PROCESS_EXIT" to "截图进程异常退出",
        "CAPTURE_ID_EXHAUSTED" to "截图标识已用尽", "CAPTURE_CONTROL_UNAVAILABLE" to "截图控制通道不可用",
        "CAPTURE_TRANSPORT" to "截图传输失败", "CAPTURE_PROTOCOL" to "截图协议不兼容",
        "CAPTURE_CORRELATION" to "截图响应与请求不匹配", "CAPTURE_CANCELLED" to "截图已取消",
        "CAPTURE_BACKEND_UNAVAILABLE" to "截图后端不可用", "CAPTURE_BUDGET" to "截图超出资源预算",
        "CAPTURE_CONNECTION_CLOSED" to "截图连接已断开", "CAPTURE_QUEUE_BUSY" to "截图队列繁忙",
        "CAPTURE_REQUEST_FAILED" to "截图请求失败",
    )
    private val normal = setOf("ROOT_START", "ROOT_INSTALLED_DAEMON", "ROOT_EXTRACTED_DAEMON", "ROOT_READY",
        "ROOT_DISCONNECTED", "ROOT_IDLE_RELEASED", "CAPTURE_REQUESTED", "CAPTURE_READY", "CAPTURE_CANCELLED", "CAPTURE_RUNTIME_BUSY")
    private val diagnostic = Regex("^(?:[0-9]{1,20}(?:ms)? · )?App诊断 · ([A-Z_]+)(?: (\\[-?[0-9]+]))?$")
    fun description(code: String): String? = messages[code]
    fun isFailure(code: String): Boolean = code in messages && code !in normal
    fun diagnosticIsFailure(line: String): Boolean? = diagnostic.matchEntire(line)?.let {
        it.groupValues[1].takeIf(messages::containsKey)?.let(::isFailure)
    }
    fun readableDiagnostic(line: String): String? = diagnostic.matchEntire(line)?.let { match ->
        messages[match.groupValues[1]]?.let { "$it [${match.groupValues[1]}]${match.groupValues[2].let { suffix -> if (suffix.isBlank()) "" else " $suffix" }}" }
    }
}
