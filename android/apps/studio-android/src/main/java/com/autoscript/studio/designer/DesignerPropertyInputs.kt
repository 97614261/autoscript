package com.autoscript.studio

import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.staticCompositionLocalOf

internal data class DesignerInputOwner(val pageId: String, val controlId: String?, val tab: String)
internal val LocalDesignerPropertyInputs = staticCompositionLocalOf<DesignerPropertyInputs?> { null }
internal val LocalDesignerInputOwner = staticCompositionLocalOf<DesignerInputOwner?> { null }

/** Only unrepresentable numeric text is retained here; valid values stay in the undoable model. */
internal class DesignerPropertyInputs {
    private data class Key(val owner: DesignerInputOwner, val label: String)
    private data class Invalid(val text: String, val range: IntRange)
    private val invalid = mutableStateMapOf<Key, Invalid>()
    fun text(owner: DesignerInputOwner, label: String, value: String): String = invalid[Key(owner, label)]?.text ?: value
    fun edit(owner: DesignerInputOwner, label: String, text: String, range: IntRange): Int? {
        val number = text.toIntOrNull()?.takeIf { it in range }
        val key = Key(owner, label)
        if (number == null) invalid[key] = Invalid(text.take(32), range) else invalid.remove(key)
        return number
    }
    fun firstIssue(): DesignerValidationIssue? = invalid.entries.firstOrNull()?.let { (key, value) ->
        DesignerValidationIssue(key.owner.controlId, key.owner.pageId, key.owner.tab,
            "${key.label}须为${value.range.first}～${value.range.last}的整数，请完成输入后再保存")
    }
    fun prune(draft: RunnerUiDesignerDraft) {
        invalid.keys.filter { key -> draft.pages.none { it.id == key.owner.pageId } ||
            key.owner.controlId?.let { id -> draft.fields.none { it.id == id && it.ui.pageId == key.owner.pageId } } == true
        }.forEach(invalid::remove)
    }
    fun clear() { invalid.clear() }
    fun clearSizes(ids: Set<String>) {
        invalid.keys.filter { it.owner.controlId in ids && it.label in setOf("宽", "高", "字号 px") }.forEach(invalid::remove)
    }
}
