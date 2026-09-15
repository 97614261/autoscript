package com.autoscript.runner

import android.content.Context
import android.text.InputType
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.Switch
import android.widget.TextView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.widget.doAfterTextChanged

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
    modifier: Modifier = Modifier,
) {
    key(releaseId) {
        AndroidView(
            modifier = modifier,
            factory = { context ->
                LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    definition.description?.let { description ->
                        addView(label(context, description).apply { textSize = 13f })
                    }
                    definition.fields.forEach { field ->
                        addView(label(context, field.label + if (field.required) " *" else ""))
                        when (field.kind) {
                            RunnerUiFieldKind.TEXT, RunnerUiFieldKind.INTEGER -> {
                                val current = (values[field.id] as? RunnerConfigDraftValue.Text)?.value.orEmpty()
                                addView(EditText(context).apply {
                                    setText(current)
                                    setSingleLine(true)
                                    textSize = 14f
                                    inputType = if (field.kind == RunnerUiFieldKind.INTEGER) {
                                        InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_SIGNED
                                    } else {
                                        InputType.TYPE_CLASS_TEXT
                                    }
                                    doAfterTextChanged { editable ->
                                        onValueChanged(
                                            field.id,
                                            RunnerConfigDraftValue.Text(editable?.toString().orEmpty()),
                                        )
                                    }
                                }, matchWidth())
                            }
                            RunnerUiFieldKind.BOOLEAN -> {
                                val current = (values[field.id] as? RunnerConfigDraftValue.BooleanValue)
                                    ?.value ?: false
                                @Suppress("DEPRECATION")
                                addView(Switch(context).apply {
                                    isChecked = current
                                    text = if (current) "已开启" else "已关闭"
                                    setOnCheckedChangeListener { button, checked ->
                                        button.text = if (checked) "已开启" else "已关闭"
                                        onValueChanged(
                                            field.id,
                                            RunnerConfigDraftValue.BooleanValue(checked),
                                        )
                                    }
                                })
                            }
                            RunnerUiFieldKind.CHOICE -> {
                                val current = (values[field.id] as? RunnerConfigDraftValue.Text)?.value
                                addView(Spinner(context).apply {
                                    adapter = ArrayAdapter(
                                        context,
                                        android.R.layout.simple_spinner_dropdown_item,
                                        field.options,
                                    )
                                    setSelection(field.options.indexOf(current).coerceAtLeast(0), false)
                                    onItemSelectedListener = SimpleItemSelectedListener { position ->
                                        onValueChanged(
                                            field.id,
                                            RunnerConfigDraftValue.Text(field.options[position]),
                                        )
                                    }
                                }, matchWidth())
                            }
                        }
                    }
                }
            },
        )
    }
}

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
                    stored.takeIf { it.codePointLength() <= 256 && '\u0000' !in it } ?: initial,
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

private fun label(context: Context, value: String) = TextView(context).apply {
    text = value
    textSize = 14f
    setPadding(0, 8, 0, 2)
}

private fun matchWidth() = LinearLayout.LayoutParams(
    ViewGroup.LayoutParams.MATCH_PARENT,
    ViewGroup.LayoutParams.WRAP_CONTENT,
)

private class SimpleItemSelectedListener(
    private val selected: (Int) -> Unit,
) : android.widget.AdapterView.OnItemSelectedListener {
    override fun onItemSelected(
        parent: android.widget.AdapterView<*>?,
        view: android.view.View?,
        position: Int,
        id: Long,
    ) = selected(position)

    override fun onNothingSelected(parent: android.widget.AdapterView<*>?) = Unit
}

private const val PREFERENCES_NAME = "runner-script-ui"

private fun String.codePointLength(): Int = codePointCount(0, length)
