package com.autoscript.studio

import com.google.gson.JsonObject

/** User-facing progress, transient toast and developer log have different runtime channels. */
internal object DebugOutputCode {
    const val RUN_PROMPT = 0
    const val TOAST = 1
    const val LOG = 2
    const val COMMENT = 3

    private val variableName = Regex("[A-Za-z_][A-Za-z0-9_]{0,63}")

    fun error(mode: Int, text: String, variable: String?): String? {
        if (mode !in RUN_PROMPT..COMMENT) return "未知的输出方式"
        if (variable != null && mode != COMMENT && !variableName.matches(variable)) return "变量名称无效"
        if ((mode == COMMENT || variable == null) && text.isBlank()) return "请先输入内容或选择变量"
        if (text.toByteArray(Charsets.UTF_8).size > 2_048) return "内容不能超过 2048 字节"
        return null
    }

    fun snippet(mode: Int, text: String, variable: String?, visualMode: Boolean): String? {
        if (error(mode, text, variable) != null) return null
        if (visualMode) {
            val kind = when (mode) {
                RUN_PROMPT -> "task.runprompt"
                TOAST -> "task.prompt"
                LOG -> "task.log"
                else -> "task.comment"
            }
            val args = JsonObject().apply {
                addProperty("message", text.ifBlank { variable ?: "" })
                if (variable != null && mode != COMMENT) addProperty("valueVariable", variable)
                if (mode == LOG) addProperty("level", "info")
            }
            return "${FunctionCatalog.BLOCK_HINT_PREFIX}$kind\n$args\n"
        }
        if (mode == COMMENT) return "-- ${text.replace('\r', ' ').replace('\n', ' ').trim()}\n"
        val expression = variable?.let { "tostring($it)" } ?: luaString(text)
        val function = when (mode) {
            RUN_PROMPT -> "Prompt.show"
            TOAST -> "Prompt.toast"
            else -> "Log.info"
        }
        return "$function($expression)\n"
    }

    private fun luaString(value: String): String = buildString {
        append('"')
        value.forEach { char ->
            when (char) {
                '\\' -> append("\\\\")
                '"' -> append("\\\"")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> if (char.code < 32 || char.code == 127) append("\\%03d".format(char.code)) else append(char)
            }
        }
        append('"')
    }
}
