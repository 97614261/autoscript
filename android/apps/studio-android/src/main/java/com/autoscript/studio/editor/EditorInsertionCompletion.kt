package com.autoscript.studio

/** One form's completion ticket. Failure/cancellation never completes it; disposal revokes it. */
internal class EditorInsertionCompletion(private val closeForm: () -> Unit) {
    private var active = true
    fun committed() {
        if (!active) return
        active = false
        closeForm()
    }
    fun cancel() { active = false }
}

internal data class EditorPendingSnippet(val snippet: String, val onCommitted: () -> Unit,
    val flowId: String?, val source: String?, val primary: String?, val selection: Set<String>) {
    fun matches(flow: String?, source: String?, primary: String?, selection: Set<String>): Boolean =
        flowId == flow && this.source == source && this.primary == primary && this.selection == selection
}
