package com.autoscript.studio

import com.autoscript.core.model.RuntimeConnectionPhase
import com.autoscript.core.model.RuntimeConnectionState
import com.autoscript.core.model.RuntimeRootState
import java.io.File
import org.junit.Assert.*
import org.junit.Test

class EditorPermissionsImageTest {
    @Test fun imageLibraryBelongsToTheServiceOverlayInsteadOfAnActivityDialog() {
        val screen = File("src/main/java/com/autoscript/studio/editor/VisualProjectScreen.kt").readText()
        assertFalse(screen.lineSequence().any { it.trim() == "import androidx.compose.ui.window.Dialog" })
        val library = screen.substringAfter("if (showImageLibrary) {").substringBefore("if (showRecognitionReader)")
        assertTrue(library.contains("ImageLibraryDialog("))
        assertFalse(screen.contains("ImageToolsScreen("))
        assertTrue(library.contains("onDismiss = { showImageLibrary = false }"))
        val host = File("src/main/java/com/autoscript/studio/image/ImageLibraryDialog.kt").readText()
        assertTrue(host.contains("StudioDialog("))
        assertFalse(host.lineSequence().any { it.trim() == "import androidx.compose.ui.window.Dialog" })
    }

    @Test fun connectionDoesNotMeanRootOrInputPermission() {
        val rows = automationPermissionStatuses(RuntimeConnectionState(phase = RuntimeConnectionPhase.CONNECTED), 3)
        assertTrue(rows.first().healthy)
        assertFalse(rows.drop(1).any { it.healthy })
    }

    @Test fun inputReadinessRequiresRootAndActualFeatureFlags() {
        val state = RuntimeConnectionState(phase = RuntimeConnectionPhase.CONNECTED, rootState = RuntimeRootState.READY)
        val loading = automationPermissionStatuses(state, null)
        assertEquals("读取中", loading[2].status)
        assertFalse(loading[2].healthy)
        assertTrue(automationPermissionStatuses(state, 1)[2].healthy)
        assertFalse(automationPermissionStatuses(state, 1)[3].healthy)
        assertTrue(automationPermissionStatuses(state, 3)[3].healthy)
        assertFalse(automationPermissionStatuses(state, 2)[3].healthy)
        assertTrue(automationPermissionStatuses(state, 3).single {it.title=="Root 截图"}.status.contains("未执行截图测试"))
        assertFalse(automationPermissionStatuses(state,3).last().healthy)
        assertTrue(automationPermissionStatuses(state,11).last().healthy)
    }

    @Test fun disconnectedOrFailedRootDoesNotReuseOldFeatures() {
        assertFalse(automationPermissionStatuses(RuntimeConnectionState(rootState = RuntimeRootState.READY), 3).any { it.healthy })
        val failed = automationPermissionStatuses(RuntimeConnectionState(phase = RuntimeConnectionPhase.CONNECTED, rootState = RuntimeRootState.FAILED), 3)
        assertEquals("启动失败", failed[1].status)
        assertFalse(failed.drop(1).any { it.healthy })
    }

    @Test fun imageHomeOnlyOffersRealConfigurableContracts() {
        val actions = imageEntryGroups.flatMap { it.second }
        assertEquals(actions.size, actions.map { it.kind }.distinct().size)
        actions.forEach { action ->
            if (action.kind == GLYPH_MAKER_ENTRY) {
                assertNull("制字是Studio工具，不是执行积木", BlockCatalog.find(action.kind))
                return@forEach
            }
            val kind = when(action.kind) { RECOGNITION_RESULT_READER -> "vision.readresult"; COLOR_FILTER_ENTRY -> "screen.transform";else -> action.kind }
            assertNotNull(action.title, BlockCatalog.find(kind))
            assertTrue(kind, kind in visualRecognitionKinds || kind in setOf(
                "vision.result", "vision.readresult", "vision.resultaction", "vision.findallmulticolor", "vision.components",
                "ocr.glyphdetails", "ocr.findtext", "screen.loadimage", "screen.crop", "screen.transform", "screen.savepng",
                "screen.series", "screen.seriesframe", "screen.seriesrelease", "screen.compareframes", "screen.mappoint", "vision.findscaled",
                "screen.frameinfo", "screen.capture", "screen.release", "screen.captureregion", "vision.findfeatures", "ocr.alphanumericv5",
            ))
        }
    }

    @Test fun thresholdsOutputsAndAdvancedActionsAreSeparatedWithoutDroppingProperties() {
        assertEquals(RecognitionSection.MATCH, recognitionPropertySection("tolerance"))
        assertEquals(RecognitionSection.MATCH, recognitionPropertySection("imagePaths"))
        assertEquals(RecognitionSection.OUTPUT, recognitionPropertySection("resultVariable"))
        assertEquals(RecognitionSection.OUTPUT, recognitionPropertySection("scoreVariable"))
        assertEquals(RecognitionSection.ADVANCED, recognitionPropertySection("frameVariable"))
        assertEquals(RecognitionSection.ADVANCED, recognitionPropertySection("offsetX"))
        assertEquals(RecognitionSection.ADVANCED, recognitionPropertySection("autoCapture"))
        imageEntryGroups.flatMap { it.second }.forEach { action ->
            if (action.kind == GLYPH_MAKER_ENTRY) return@forEach
            val kind = when(action.kind) { RECOGNITION_RESULT_READER -> "vision.readresult"; COLOR_FILTER_ENTRY -> "screen.transform";else -> action.kind }
            requireNotNull(BlockCatalog.find(kind)).properties.forEach { assertNotNull(recognitionPropertySection(it.path)) }
        }
    }

    @Test fun bulkSelectionIsBoundedAndClearOnlyTouchesExistingSelectedNodes() {
        val ids = (0..299).map { "node$it" }
        assertEquals(256, editorSelectionToggleIds(ids, emptySet(), true).size)
        val selected = ids.takeLast(100).toSet()
        assertEquals(156, editorSelectionToggleIds(ids, selected, true).size)
        assertTrue(editorSelectionToggleIds(ids, selected, true).none { it in selected })
        assertEquals(ids.takeLast(100), editorSelectionToggleIds(ids, selected + "stale", false))
        assertTrue(editorSelectionToggleIds(ids, ids.toSet(), true).isEmpty())
    }
}
