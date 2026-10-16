package com.autoscript.studio

import org.junit.Assert.*
import org.junit.Test

class UiDesignerRecoveryPromptTest {
    private val saved = runnerUiDesignerDraft(null)
    private val record = UiDesignerCheckpoint(1, "project", "base", saved)

    @Test fun invalidAndChangedRecordsHaveNoDisabledRestoreAction() {
        assertEquals(UiDesignerRecoveryAvailability.INVALID,
            uiDesignerRecoveryAvailability(true, record, "base", saved, saved))
        assertEquals(UiDesignerRecoveryAvailability.INVALID,
            uiDesignerRecoveryAvailability(false, null, "base", saved, saved))
        assertEquals(UiDesignerRecoveryAvailability.CHANGED,
            uiDesignerRecoveryAvailability(false, record, "new-base", saved, saved))
        for (state in UiDesignerRecoveryAvailability.entries.filter { it != UiDesignerRecoveryAvailability.READY }) {
            assertEquals(listOf(UiDesignerRecoveryAction.KEEP, UiDesignerRecoveryAction.DISCARD), uiDesignerRecoveryActions(state))
        }
    }

    @Test fun recoverableDraftHasThreeIndependentActionsAndCurrentChangesAreProtected() {
        assertEquals(UiDesignerRecoveryAvailability.READY,
            uiDesignerRecoveryAvailability(false, record, "base", saved, saved))
        assertEquals(listOf(UiDesignerRecoveryAction.KEEP, UiDesignerRecoveryAction.DISCARD, UiDesignerRecoveryAction.RESTORE),
            uiDesignerRecoveryActions(UiDesignerRecoveryAvailability.READY))
        val changed = saved.copy(description = "unsaved")
        assertEquals(UiDesignerRecoveryAvailability.EDITING,
            uiDesignerRecoveryAvailability(false, record, "base", changed, saved))
        assertEquals(saved, record.draft)
    }

    @Test fun compactActionsUseOneRowOnlyWhenAllCellsFit() {
        assertFalse(studioConfirmActionsStacked(400f, 1f, 3))
        assertFalse(studioConfirmActionsStacked(280f, 1f, 2))
        assertTrue(studioConfirmActionsStacked(280f, 1f, 3))
        assertTrue(studioConfirmActionsStacked(400f, 2f, 3))
        assertFalse(studioConfirmActionsStacked(400f, Float.NaN, 3))
        assertTrue(studioConfirmActionsStacked(Float.NaN, 1f, 2))
    }
}
