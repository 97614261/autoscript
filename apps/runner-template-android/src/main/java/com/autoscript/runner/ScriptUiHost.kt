package com.autoscript.runner

import android.content.Context
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import com.autoscript.script.ui.ScriptUiDefinition
import com.autoscript.script.ui.ScriptUiDialog
import com.autoscript.script.ui.ScriptUiWindowView
import com.autoscript.script.ui.UiField
import com.autoscript.script.ui.luaConfiguration

internal sealed interface RunnerConfigDraftValue {
    data class Text(val value: String) : RunnerConfigDraftValue
    data class BooleanValue(val value: Boolean) : RunnerConfigDraftValue
}

@Composable
internal fun ScriptUiHost(
    releaseId: String,
    definition: RunnerUiDefinition,
    values: Map<String, RunnerConfigDraftValue>,
    onValueChanged: (String, RunnerConfigDraftValue) -> Unit,
    visible: Boolean = true,
    release: EmbeddedRelease? = null,
    onRun: () -> Unit = {},
    onClose: () -> Unit = {},
    onMinimize: () -> Unit = {},
) {
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    val change by rememberUpdatedState(onValueChanged)
    val run by rememberUpdatedState(onRun)
    val close by rememberUpdatedState(onClose)
    val minimize by rememberUpdatedState(onMinimize)
    val window = remember(releaseId, definition, context) {
        val model = definition.shared()
        lateinit var host: ScriptUiWindowView
        host = ScriptUiWindowView(context, model, values.strings(), { path -> release?.resources?.find { it.path == path }?.file }) { _, _, _, action ->
            host.values().forEach { (id, value) ->
                val field = definition.fields.first { it.id == id }
                change(id, if (field.kind == RunnerUiFieldKind.BOOLEAN) RunnerConfigDraftValue.BooleanValue(value == "true") else RunnerConfigDraftValue.Text(value))
            }
            when (action) {
                "run" -> runCatching { model.validateValues(host.values()); run() }.onFailure { host.error(it.message ?: "参数无效") }
                "close" -> close()
                "minimize" -> minimize()
            }
        }
        ScriptUiDialog(context, host) { close() }
    }
    DisposableEffect(window) { onDispose { window.close() } }
    LaunchedEffect(window, visible, configuration.screenWidthDp, configuration.screenHeightDp, configuration.orientation) {
        if (visible) window.show() else window.hide()
    }
}

internal fun RunnerUiDefinition.shared() = script ?: ScriptUiDefinition(description, fields.map { f ->
    UiField(f.id, f.label, f.kind.name.lowercase(), f.required, when (val v = f.initialValue) {
        is RunnerUiValue.Text -> v.value; is RunnerUiValue.Integer -> v.value.toString(); is RunnerUiValue.BooleanValue -> v.value.toString()
    }, f.minimum, f.maximum, f.options)
})
internal fun Map<String, RunnerConfigDraftValue>.strings() = mapValues { (_, value) -> when (value) {
    is RunnerConfigDraftValue.Text -> value.value; is RunnerConfigDraftValue.BooleanValue -> value.value.toString()
} }


internal fun initialRunnerConfig(
    context: Context,
    release: EmbeddedRelease,
): Map<String, RunnerConfigDraftValue> {
    val definition = release.runnerUi ?: return emptyMap()
    val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
    return definition.fields.associate { field ->
        val key = "${release.releaseId}:${field.id}"
        val value = when (field.kind) {
            RunnerUiFieldKind.TEXT -> {
                val initial = (field.initialValue as RunnerUiValue.Text).value
                val stored = preferences.getString(key, initial) ?: initial
                RunnerConfigDraftValue.Text(
                    stored.takeIf { it.codePointLength() <= (if (definition.script != null) 2048 else 256) && '\u0000' !in it } ?: initial,
                )
            }
            RunnerUiFieldKind.INTEGER -> {
                val initial = (field.initialValue as RunnerUiValue.Integer).value
                val minimum = field.minimum ?: -1_000_000_000L
                val maximum = field.maximum ?: 1_000_000_000L
                val stored = preferences.getString(key, null)?.toLongOrNull()
                RunnerConfigDraftValue.Text(
                    stored?.takeIf { it in minimum..maximum }?.toString() ?: initial.toString(),
                )
            }
            RunnerUiFieldKind.BOOLEAN -> {
                val initial = (field.initialValue as RunnerUiValue.BooleanValue).value
                RunnerConfigDraftValue.BooleanValue(preferences.getBoolean(key, initial))
            }
            RunnerUiFieldKind.CHOICE -> {
                val initial = (field.initialValue as RunnerUiValue.Text).value
                val stored = preferences.getString(key, initial) ?: initial
                RunnerConfigDraftValue.Text(stored.takeIf(field.options::contains) ?: initial)
            }
        }
        field.id to value
    }
}

internal fun persistRunnerConfig(
    context: Context,
    release: EmbeddedRelease,
    values: Map<String, RunnerConfigDraftValue>,
) {
    val definition = release.runnerUi ?: return
    val editor = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE).edit()
    definition.fields.forEach { field ->
        val key = "${release.releaseId}:${field.id}"
        when (val value = values[field.id]) {
            is RunnerConfigDraftValue.Text -> editor.putString(key, value.value)
            is RunnerConfigDraftValue.BooleanValue -> editor.putBoolean(key, value.value)
            null -> Unit
        }
    }
    editor.apply()
}

internal fun buildRuntimeLua(
    release: EmbeddedRelease,
    values: Map<String, RunnerConfigDraftValue>,
): ByteArray {
    release.runnerUi?.script?.let { model ->
        val prefix = model.luaConfiguration(values.strings()).toByteArray(Charsets.UTF_8)
        return prefix + release.luaSource
    }
    val fields = release.runnerUi?.fields.orEmpty()
    val prefix = buildString {
        append("RunnerConfig={")
        fields.forEachIndexed { index, field ->
            if (index > 0) append(',')
            append(field.id).append('=')
            when (field.kind) {
                RunnerUiFieldKind.TEXT -> {
                    val value = (values[field.id] as? RunnerConfigDraftValue.Text)?.value.orEmpty()
                    require(value.codePointLength() <= 256 && '\u0000' !in value &&
                        (!field.required || value.isNotBlank())) {
                        "${field.label}不能为空且不能超过256个字符"
                    }
                    appendLuaString(value)
                }
                RunnerUiFieldKind.INTEGER -> {
                    val raw = (values[field.id] as? RunnerConfigDraftValue.Text)?.value.orEmpty()
                    val value = raw.toLongOrNull() ?: error("${field.label}必须是整数")
                    val minimum = field.minimum ?: -1_000_000_000L
                    val maximum = field.maximum ?: 1_000_000_000L
                    require(value in minimum..maximum) { "${field.label}超出范围 $minimum..$maximum" }
                    append(value)
                }
                RunnerUiFieldKind.BOOLEAN -> append(
                    (values[field.id] as? RunnerConfigDraftValue.BooleanValue)?.value ?: false,
                )
                RunnerUiFieldKind.CHOICE -> {
                    val value = (values[field.id] as? RunnerConfigDraftValue.Text)?.value.orEmpty()
                    require(value in field.options) { "${field.label}选项无效" }
                    appendLuaString(value)
                }
            }
        }
        append("}\n")
    }.toByteArray(Charsets.UTF_8)
    return ByteArray(prefix.size + release.luaSource.size).also { merged ->
        prefix.copyInto(merged)
        release.luaSource.copyInto(merged, prefix.size)
    }
}

internal const val RUNTIME_CONFIG_PREFIX_LINES = 1

private fun StringBuilder.appendLuaString(value: String) {
    append('"')
    value.forEach { character ->
        when (character) {
            '\\' -> append("\\\\")
            '"' -> append("\\\"")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '\t' -> append("\\t")
            else -> if (character.code < 32 || character.code == 127) {
                append('\\').append(character.code.toString().padStart(3, '0'))
            } else {
                append(character)
            }
        }
    }
    append('"')
}

private const val PREFERENCES_NAME = "runner-script-ui"

private fun String.codePointLength(): Int = codePointCount(0, length)
