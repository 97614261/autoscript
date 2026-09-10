package com.autoscript.studio

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue

internal data class LuaEditorBuffer(
    val projectId: String,
    val text: String,
    val savedText: String,
) {
    val isDirty: Boolean get() = text != savedText

    fun edit(value: String): LuaEditorBuffer = copy(text = value)

    fun markSaved(persistedText: String): LuaEditorBuffer = copy(savedText = persistedText)

    companion object {
        fun open(projectId: String, source: String): LuaEditorBuffer =
            LuaEditorBuffer(projectId = projectId, text = source, savedText = source)
    }
}

internal fun insertEditorIndent(
    value: TextFieldValue,
    indent: String = "    ",
): TextFieldValue {
    val start = minOf(value.selection.start, value.selection.end).coerceIn(0, value.text.length)
    val end = maxOf(value.selection.start, value.selection.end).coerceIn(start, value.text.length)
    val updated = value.text.replaceRange(start, end, indent)
    return value.copy(text = updated, selection = TextRange(start + indent.length))
}
