package com.autoscript.studio

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Retirement guards remain active after the user-approved archive deletion. */
class UiArchiveTest {
    private val source = File("src/main/java/com/autoscript/studio")
    private val resources = File("src/main/res")

    @Test fun retiredDeclarationsStayOutOfProduction() {
        val production = source.walkTopDown().filter { it.isFile && it.extension == "kt" }
            .joinToString("\n") { it.readText() }
        val retiredFunctions = listOf(
            "ProjectSettingsButton",
            "RecognitionPreviewDialog",
            "LegacyDialogTitle",
            "LegacyDialogRows",
            "LegacyValueBox",
            "LegacyDialogControl",
            "LuaWorkbenchHeader",
            "LuaWorkbenchAction",
            "LegacyInputDialog",
            "DenseVariableSelector",
            "DenseRuntimeMode",
            "SectionCard",
            "SettingSwitch",
            "EntryEditField",
            "CompactChoice",
            "RadioLine"
        )
        val retiredConstants = listOf(
            "LuaChromeBackground",
            "LuaChromeDivider",
            "LuaChromeText",
            "LuaChromeMuted",
            "LuaChromeUnsaved"
        )
        for (symbol in retiredFunctions) {
            assertFalse(symbol, Regex("\\bfun\\s+${Regex.escape(symbol)}\\s*\\(").containsMatchIn(production))
        }
        for (symbol in retiredConstants) {
            assertFalse(symbol, Regex("\\bval\\s+${Regex.escape(symbol)}\\b").containsMatchIn(production))
        }
    }

    @Test fun retiredLuaPagesAndUnusedResourcesStayOutOfProduction() {
        for (name in listOf("LuaEditorScreen.kt", "LuaEditorState.kt")) {
            assertFalse(name, source.walkTopDown().any { it.isFile && it.name == name })
        }
        for (path in listOf(
            "drawable/editor_function_search_24.xml",
            "drawable/editor_moon_24.xml",
            "drawable/editor_popup_minimize_20.xml",
            "drawable/editor_popup_plugin_20.xml",
            "drawable/editor_popup_power_20.xml",
            "drawable/editor_popup_settings_20.xml",
            "drawable/ic_add_20.xml",
            "drawable/visual_adjust_24.xml",
            "drawable/visual_align_24.xml",
            "drawable/visual_code_24.xml",
            "drawable/visual_control_anchor.xml",
            "drawable/visual_control_button.xml",
            "drawable/visual_control_checkbox.xml",
            "drawable/visual_control_container.xml",
            "drawable/visual_control_input.xml",
            "drawable/visual_control_label.xml",
            "drawable/visual_control_list.xml",
            "drawable/visual_control_progress.xml",
            "drawable/visual_control_radio.xml",
            "drawable/visual_control_select.xml",
            "drawable/visual_control_slider.xml",
            "drawable/visual_control_textarea.xml",
            "drawable/visual_copy_24.xml",
            "drawable/visual_multi_24.xml",
            "drawable/visual_page_24.xml",
            "drawable/visual_preview_24.xml",
            "drawable/editor_float_ball_logo.xml",
            "values/editor-preview.xml",
            "drawable/editor_backfill_24.xml",
            "drawable/editor_swap_24.xml",
            "drawable/ic_description_24.xml",
            "drawable/ic_group_24.xml",
            "drawable/ic_person_48.xml"
        )) {
            assertFalse(path, File(resources, path).exists())
        }
    }

    @Test fun activeUiFilesKeepTheirCurrentNames() {
        for ((old, current) in mapOf(
            "LegacyScriptDock.kt" to "EditorDock.kt",
            "LegacySourceManagerDialog.kt" to "PluginFileManagerDialog.kt",
            "LegacyPromptDialogs.kt" to "EditorPromptDialogs.kt",
            "LegacyFunctionCatalog.kt" to "FunctionCatalog.kt"
        )) {
            assertFalse(old, source.walkTopDown().any { it.isFile && it.name == old })
            assertEquals(current, 1, source.walkTopDown().count { it.isFile && it.name == current })
        }
        val catalog = source.walkTopDown().single { it.isFile && it.name == "FunctionLibraryDialog.kt" }.readText()
        assertTrue(catalog.contains("fun FunctionLibraryDialog("))
    }

    @Test fun retiredLuaFunctionCatalogueCannotReturnThroughAnEditorFallback() {
        val catalog = source.walkTopDown().single { it.isFile && it.name == "FunctionCatalog.kt" }.readText()
        val dock = source.walkTopDown().single { it.isFile && it.name == "EditorDock.kt" }.readText()
        val utilities = source.walkTopDown().single { it.isFile && it.name == "VisualUtilityArguments.kt" }.readText()
        for (symbol in listOf("luaGroups", "LUA_GROUPS", "LUA_TEMPLATES", "luaDefault")) {
            assertFalse(symbol, catalog.contains(symbol) || dock.contains(symbol))
        }
        assertFalse(utilities.contains("utilityLuaDefault"))
        assertTrue(dock.contains("remember { FunctionCatalog.visualGroups() }"))
        assertTrue(source.walkTopDown().any { it.isFile && it.name == "LuaSnippetGate.kt" })
        assertTrue(source.walkTopDown().any { it.isFile && it.name == "PluginSourceEditorDialog.kt" })
    }
}
