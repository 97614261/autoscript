package com.autoscript.runtime.api

import com.google.gson.JsonObject
import com.google.gson.JsonParser

/** Script-facing records contain lifecycle or validated observation identities, never inferred UI samples. */
object RuntimeScriptLog {
    private val identity = Regex("(?:[A-Za-z0-9_.-]{1,128}|anonymous:[a-f0-9]{16})")
    private val timestamp = Regex("^([0-9]{1,20})ms · ")
    private val visualLine = Regex("[1-9][0-9]{0,9}")

    fun entry(level: String, message: String, elapsedMs: Long): String =
        "${elapsedMs}ms · 脚本执行/$level: $message"

    fun observation(payload: String): String? = runCatching {
        require(payload.toByteArray(Charsets.UTF_8).size <= 8_192)
        val event = JsonParser.parseString(payload).asJsonObject
        val time = event.get("receivedAtElapsedMs")?.asLong?.takeIf { it >= 0 } ?: return null
        when (event.get("kind")?.asString) {
            "trace", "task" -> {
                require(event.get("version")?.asInt == 1)
                val flow = event.get("flowId")?.asString ?: return null
                val node = event.get("nodeId")?.asString ?: return null
                require(identity.matches(flow) && identity.matches(node))
                val location = visualLocation(event)?.let { "$it [定位:$flow/$node]" }
                if (event.get("kind").asString == "trace" && event.get("phase")?.asString == "enter")
                    entry("TRACE", location ?: "插件 $flow · 积木 $node", time)
                else if (event.get("kind").asString == "task" && event.get("status")?.asString == "failed")
                    entry("ERROR", "${location ?: "插件 $flow · 积木 $node"} · 执行失败", time)
                else null
            }
            "dropped" -> {
                require(event.get("version")?.asInt == 1)
                val total = event.get("count")?.asLong?.coerceAtLeast(0) ?: return null
                if (total == 0L) return null
                val traces = event.get("eventCount")?.asLong
                val outputs = event.get("scriptLogCount")?.asLong
                val message = if (traces != null && outputs != null && traces >= 0 && outputs >= 0)
                    "记录不完整：执行观测丢失 $traces 条，脚本输出丢失 $outputs 条"
                else "记录不完整：丢失 $total 条"
                entry("WARN", message, time)
            }
            else -> null
        }
    }.getOrNull()

    /** Names and rows come from the executed checkpoint, never from a later editor draft. */
    private fun visualLocation(event: JsonObject): String? = runCatching {
        val location = event.getAsJsonObject("visualLocation") ?: return null
        val number = location.get("line").asJsonPrimitive
        require(number.isNumber && visualLine.matches(number.asString))
        val line = number.asLong.also { require(it <= 4_294_967_295L) }
        fun text(key: String): String {
            val value = location.get(key).asJsonPrimitive
            require(value.isString)
            val text = value.asString.also {
                require(it.isNotBlank() && it.toByteArray(Charsets.UTF_8).size <= 512 &&
                    it.none { char -> char.isISOControl() || char == '\u2028' || char == '\u2029' })
            }
            return if (text.length <= 80) text else text.take(79).dropLastWhile(Char::isHighSurrogate) + "…"
        }
        "插件 ${text("flowName")} · 第 $line 行 · ${text("blockTitle")}"
    }.getOrNull()

    /** Trace events remain in their original file; merge only script records for the reader. */
    fun history(script: String, events: String, checkpoint: () -> Unit = {}): List<String> {
        val lines = script.lineSequence().filter(String::isNotBlank).toMutableList()
        events.lineSequence().filter(String::isNotBlank).forEachIndexed { index, payload ->
            if (index % 128 == 0) checkpoint()
            observation(payload)?.let(lines::add)
        }
        checkpoint()
        return lines.sortedBy { timestamp.find(it)?.groupValues?.get(1)?.toLongOrNull() ?: 0L }
    }
}

/** Only accepted runs generate lifecycle records; Root-only updates do not repeat them. */
class RuntimeScriptLogState {
    private var active = false
    private var previous = RuntimeProtocol.STATE_IDLE

    @Synchronized
    fun start(elapsedMs: Long, restarted: Boolean = false): String {
        active = true
        previous = RuntimeProtocol.STATE_RUNNING
        return RuntimeScriptLog.entry("START", if (restarted) "脚本重新开始" else "脚本开始运行", elapsedMs)
    }

    @Synchronized
    fun transition(state: Int, elapsedMs: Long, stopped: Boolean, stepPause: Boolean = false,
        failureCode: String = "RUNTIME_FAILED"): String? {
        if (!active || (state == previous && !stepPause)) return null
        val last = previous
        previous = state
        val record = when (state) {
            RuntimeProtocol.STATE_RUNNING -> if (last == RuntimeProtocol.STATE_PAUSED) "RESUME" to "脚本继续运行" else return null
            RuntimeProtocol.STATE_PAUSED -> "PAUSE" to if (stepPause) "单步完成，已暂停" else "脚本已暂停"
            RuntimeProtocol.STATE_STOPPED -> "END" to if (stopped) "脚本已停止" else "脚本运行完成"
            RuntimeProtocol.STATE_FAILED -> "ERROR" to "脚本运行失败 [$failureCode]"
            else -> return null
        }
        if (state == RuntimeProtocol.STATE_STOPPED || state == RuntimeProtocol.STATE_FAILED) active = false
        return RuntimeScriptLog.entry(record.first, record.second, elapsedMs)
    }
}
