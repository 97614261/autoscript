package com.autoscript.script.ui

import com.google.gson.JsonParser

/** Narrow wire data shared by Studio and standalone Runner; no arbitrary window or file names. */
data class ScriptDialogRequest(
    val pageId: String?, val title: String, val message: String,
    val confirmText: String, val cancelText: String, val blockOutside: Boolean, val timeoutMs: Long,
) {
    companion object {
        fun parse(id: String, operation: String, source: String): ScriptDialogRequest {
            require(source.toByteArray(Charsets.UTF_8).size <= 32768 && '\u0000' !in source)
            val json = JsonParser.parseString(source).asJsonObject
            val confirm = operation == "dialogConfirm"
            require(confirm || operation == "dialogShow")
            val keys = if (confirm) setOf("title", "message", "confirmText", "cancelText", "timeoutMs") else setOf("blockOutside", "timeoutMs")
            require(json.keySet() == keys)
            val time = json.get("timeoutMs").also { require(it.isJsonPrimitive && it.asJsonPrimitive.isNumber) }.asString.toLong()
            require(time in 1000..1800000)
            fun text(key: String, limit: Int): String {
                val value = json.get(key).also { require(it.isJsonPrimitive && it.asJsonPrimitive.isString) }.asString
                require(value.isNotEmpty() && '\u0000' !in value && value.toByteArray(Charsets.UTF_8).size <= limit)
                return value
            }
            if (confirm) {
                require(id == "window")
                return ScriptDialogRequest(null, text("title", 192), text("message", 4096), text("confirmText", 96), text("cancelText", 96), false, time)
            }
            require(Regex("[A-Za-z_][A-Za-z0-9_]{0,63}").matches(id))
            val block = json.get("blockOutside").also { require(it.isJsonPrimitive && it.asJsonPrimitive.isBoolean) }.asBoolean
            return ScriptDialogRequest(id, "", "", "", "", block, time)
        }
    }
}

/** One completion per session. Stale choices/cancellation never become a positive answer. */
class ScriptDialogCompletion(private val confirm: Boolean, private val deliver: (String) -> Unit) {
    var finished = false
        private set
    fun finish(result: String) {
        require(result in if (confirm) setOf("confirm", "cancel", "dismiss", "timeout") else setOf("close", "dismiss", "timeout"))
        if (finished) return
        finished = true
        deliver(result)
    }
    fun cancel() { finished = true }
}
