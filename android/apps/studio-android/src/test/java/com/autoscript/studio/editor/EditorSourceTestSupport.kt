package com.autoscript.studio

/** Source wiring checks span both the state owner and its extracted rendering layer. */
internal fun editorDockTestSources(): String = listOf("EditorDock.kt", "EditorDockContent.kt").joinToString("\n") {
    java.io.File("src/main/java/com/autoscript/studio/editor/$it").readText()
}
