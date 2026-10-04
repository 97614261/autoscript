package com.autoscript.studio

import com.autoscript.core.model.RuntimeConnectionPhase
import com.autoscript.core.model.RuntimeConnectionState
import com.autoscript.core.model.RuntimeRootState
import org.junit.Assert.*
import org.junit.Test

class EditorPermissionsImageTest {
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
        assertTrue(automationPermissionStatuses(state, 3).last().status.contains("未执行截图测试"))
    }

    @Test fun disconnectedOrFailedRootDoesNotReuseOldFeatures() {
        assertFalse(automationPermissionStatuses(RuntimeConnectionState(rootState = RuntimeRootState.READY), 3).any { it.healthy })
        val failed = automationPermissionStatuses(RuntimeConnectionState(phase = RuntimeConnectionPhase.CONNECTED, rootState = RuntimeRootState.FAILED), 3)
        assertEquals("授权或启动失败", failed[1].status)
        assertFalse(failed.drop(1).any { it.healthy })
    }

    @Test fun imageHomeOnlyOffersRealConfigurableContracts() {
        val actions = imageEntryGroups.flatMap { it.second }
        assertEquals(actions.size, actions.map { it.kind }.distinct().size)
        actions.forEach { action ->
            assertNotNull(action.title, BlockCatalog.find(action.kind))
            assertTrue(action.kind in visualRecognitionKinds || action.kind == "vision.result")
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
            requireNotNull(BlockCatalog.find(action.kind)).properties.forEach { assertNotNull(recognitionPropertySection(it.path)) }
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
