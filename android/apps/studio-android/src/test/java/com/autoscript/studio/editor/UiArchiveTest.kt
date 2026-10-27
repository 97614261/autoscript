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
            "ImageToolsScreen",
            "FileManagerPage",
            "ToolSettingsPage",
            "ImageRecognitionPage",
            "insertLegacyTreeNode",
            "removeLegacyTreeSubtree",
            "encodeLegacyTreeNodes",
            "decodeLegacyTreeNodes",
            "legacyTreeNode",
            "legacyDockProgramCommand",
            "editorEditingEntries",
            "projectFileCatalog",
            "importRuntimeAudio",
            "forSingleStep",
            "selectionSubtreeSize",
            "elseIfBranchCount",
            "flowCallTarget",
            "flowCallArguments",
            "updateFlowCall",
            "opensInterface",
            "displayAlgorithm",
            "VisualDebugInspector",
            "backfillFields",
            "backfillArguments",
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
        for (name in listOf("LuaEditorScreen.kt", "LuaEditorState.kt", "RuntimeAudioImporter.kt", "ProjectFileCatalog.kt", "VisualDebugInspector.kt")) {
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
            "drawable/ic_person_48.xml",
            "drawable/editor_annotation_24.xml",
            "drawable/editor_capture_camera_24.xml",
            "drawable/editor_file_move_24.xml",
            "drawable/editor_file_paste_24.xml",
            "drawable/editor_image_processing_24.xml",
            "drawable/editor_menu_24.xml",
            "drawable/editor_recognition_preview_24.xml",
            "drawable/ic_arrow_right_24.xml",
            "drawable/ic_content_cut_24.xml",
            "drawable/package_folder_open_20.xml",
            "drawable/package_java_plugin_24.xml",
            "drawable/visual_redo_24.xml",
            "drawable/visual_save_24.xml",
            "drawable/visual_undo_24.xml"
        )) {
            assertFalse(path, File(resources, path).exists())
        }
    }

    @Test fun editorHasOnlyTheAuthoritativeVisualTreeAndNoFileCatalogPlumbing() {
        val dock = File(source, "editor/EditorDock.kt").readText()
        assertTrue(dock.contains("programNodes: List<EditorProgramNode>,"))
        for (retired in listOf("EditorTreeNodesStateSaver", "externallyManaged", "treeNodes", "editingNodeId", "nextNodeId")) {
            assertFalse(retired, Regex("\\b${Regex.escape(retired)}\\b").containsMatchIn(dock))
        }
        val screen = File(source, "editor/VisualProjectScreen.kt").readText()
        assertFalse(screen.contains("projectFileCatalog("))
        assertFalse(screen.contains("onDeleteProjectFiles"))
        assertFalse(screen.contains("onOpenProjectFile"))
        assertFalse(screen.contains("onBackfill"))
        assertFalse(screen.contains("EditorToolPanel.DATA_BACKFILL"))
        assertTrue(screen.contains("VariableCheckDialog(snapshot, selectedFlowId, editor, runtimeClient,"))
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
