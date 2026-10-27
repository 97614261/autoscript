package com.autoscript.studio

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import com.autoscript.project.store.ProjectNames
import com.autoscript.project.store.ProjectVariableType
import com.google.gson.JsonPrimitive

/** Editing conveniences only; the compiler still owns the restricted expression grammar. */
internal object DebugOutputExpression {
    fun variable(field: TextFieldValue, variable: CalculationVariable, labeled: Boolean = false): TextFieldValue? {
        val name = variable.name
        if (!ProjectNames.pattern.matches(name)) return null
        val value = when (variable.type) {
            ProjectVariableType.STRING -> name
            ProjectVariableType.INTEGER, ProjectVariableType.NUMBER -> "text($name)"
            ProjectVariableType.IMAGE -> return null
        }
        val fragment = if (labeled) "${quote("$name=")} .. $value" else value
        return operand(field, fragment, separate = labeled)
    }

    fun literal(field: TextFieldValue, value: String, editInside: Boolean = false): TextFieldValue? {
        if (sameLiteral(field)) return replace(field, quote(value).drop(1).dropLast(1))
        val result = operand(field, quote(value)) ?: return null
        return if (editInside) result.copy(selection = TextRange(result.selection.start - 1)) else result
    }

    fun asText(field: TextFieldValue): TextFieldValue? {
        val range = if (field.selection.collapsed) TextRange(0, field.text.length) else field.selection
        if (!boundary(field.text, range.min) || !boundary(field.text, range.max)) return null
        val selected = field.text.substring(range.min, range.max)
        val result = replace(field.copy(selection = range), "text($selected)") ?: return null
        return if (selected.isBlank()) result.copy(selection = TextRange(result.selection.start - 1)) else result
    }

    private fun operand(field: TextFieldValue, value: String, separate: Boolean = false): TextFieldValue? {
        val start = field.selection.min
        val end = field.selection.max
        if (sameLiteral(field)) {
            val left = "\" .. "
            val result = replace(field, left + value + " .. \"") ?: return null
            return result.copy(selection = TextRange(start + left.length + value.length))
        }
        if (!boundary(field.text, start) || !boundary(field.text, end)) return null
        val before = field.text.substring(0, start).trimEnd()
        val after = field.text.substring(end).trimStart()
        val left = if (before.isEmpty() || before.endsWith("..") || before.last() in "(,+-*/%") ""
            else if (separate) " .. \"  \" .. " else " .. "
        val right = if (after.isEmpty() || after.startsWith("..") || after.first() in "),+-*/%") "" else " .. "
        val inserted = left + value + right
        val result = replace(field, inserted) ?: return null
        return result.copy(selection = TextRange(start + left.length + value.length))
    }

    fun token(field: TextFieldValue, token: String): TextFieldValue? {
        if (token !in setOf(" + ", " - ", " * ", " / ", "(", ")", " .. ")) return null
        if (!boundary(field.text, field.selection.min) || !boundary(field.text, field.selection.max)) return null
        return replace(field, token)
    }

    private fun replace(field: TextFieldValue, value: String): TextFieldValue? {
        val text = field.text.replaceRange(field.selection.min, field.selection.max, value)
        if (text.toByteArray(Charsets.UTF_8).size > 1024) return null
        return TextFieldValue(text, TextRange(field.selection.min + value.length))
    }

    private fun quote(value: String): String = JsonPrimitive(value).toString()

    private fun literalContext(text: String, index: Int): Pair<Int, Boolean> {
        var opening = -1
        var escaped = false
        var unicodeDigits = 0
        for ((offset, char) in text.take(index).withIndex()) {
            if (unicodeDigits > 0) unicodeDigits--
            else if (escaped) { escaped = false; if (char == 'u') unicodeDigits = 4 }
            else if (opening >= 0 && char == '\\') escaped = true
            else if (char == '"') opening = if (opening >= 0) -1 else offset
        }
        return opening to (escaped || unicodeDigits > 0)
    }

    private fun sameLiteral(field: TextFieldValue): Boolean {
        val start = literalContext(field.text, field.selection.min)
        val end = literalContext(field.text, field.selection.max)
        return start.first >= 0 && start.first == end.first && !start.second && !end.second &&
            safeCharacterBoundary(field.text, field.selection.min) && safeCharacterBoundary(field.text, field.selection.max)
    }

    private fun safeCharacterBoundary(text: String, index: Int): Boolean =
        index == 0 || index == text.length || !(text[index - 1].isHighSurrogate() && text[index].isLowSurrogate())

    // Outside a literal, never split an identifier, number or two-character operator.
    private fun boundary(text: String, index: Int): Boolean {
        if (literalContext(text, index).first >= 0 || !safeCharacterBoundary(text, index)) return false
        fun part(char: Char) = char == '_' || char.isLetterOrDigit() || char == '.'
        return index == 0 || index == text.length || !part(text[index - 1]) || !part(text[index])
    }
}
