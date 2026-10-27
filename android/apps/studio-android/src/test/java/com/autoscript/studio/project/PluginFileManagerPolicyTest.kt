package com.autoscript.studio

import com.autoscript.project.store.SourceGroup
import org.junit.Assert.*
import org.junit.Test

class PluginFileManagerPolicyTest {
    private val main = SourceFileEntry("main", "main", "visual/flows/main.jsonl", 1, 100, null, true)
    private val helper = SourceFileEntry("2", "测试", "visual/flows/测试.jsonl", 2, 50, "分组", false)
    private val other = SourceFileEntry("3", "other", "visual/flows/other.jsonl", 3, 10, null, false)
    private val tree = SourceFileTree(listOf(SourceGroup("分组", listOf("2"))), listOf(main, helper, other))

    @Test fun selectionUsesIdentitiesAndRejectsMissingOrDuplicateTargets() {
        val selected = pluginFileSelection(tree, listOf("f:2", "f:2", "f:missing", "g:missing"))
        assertEquals(helper, selected.singleFile)
        assertEquals(listOf(helper), selected.files)
        assertTrue(selected.groups.isEmpty())
    }

    @Test fun mainPluginBlocksDeletionWithoutBlockingItsOtherOperations() {
        val selected = pluginFileSelection(tree, listOf("f:main"))
        assertFalse(selected.canDelete)
        assertEquals(main, selected.singleFile)
        assertTrue(selected.canGroup)
        assertFalse(pluginFileSelection(tree, listOf("f:main", "f:3")).canDelete)
    }

    @Test fun groupDissolutionDoesNotMeanDeletingItsMembers() {
        val selected = pluginFileSelection(tree, listOf("g:分组"))
        assertTrue(selected.canDelete)
        assertEquals("分组", selected.singleGroup)
        assertTrue(selected.files.isEmpty())
        assertFalse(selected.canGroup)
    }

    @Test fun mixedSelectionAndUngroupingOnlyUseEligibleIdentities() {
        val selected = pluginFileSelection(tree, listOf("f:2", "f:3"))
        assertNull(selected.singleFile)
        assertTrue(selected.canDelete && selected.canGroup)
        assertEquals(listOf("2"), selected.groupedFlowIds)
        assertFalse(pluginFileSelection(tree, listOf("f:2", "g:分组")).canGroup)
    }

    @Test fun selectingAndClearingVisibleRowsRetainsHiddenSelection() {
        assertEquals(listOf("f:hidden", "f:2", "f:3"), pluginFileSelectedKeys(listOf("f:2", "f:3"), listOf("f:hidden", "f:2"), true))
        assertEquals(listOf("f:hidden"), pluginFileSelectedKeys(listOf("f:2", "f:3"), listOf("f:hidden", "f:2", "f:3"), false))
    }

    @Test fun unchangedRenameAndJoiningExistingGroupAreNotDuplicateErrors() {
        assertNull(pluginFileNameError("main", listOf("main"), original = "main"))
        assertNotNull(pluginFileNameError("main", listOf("main")))
        assertNull(pluginFileNameError("分组", listOf("分组"), allowExisting = true))
    }

    @Test fun invalidNamesAndOverBudgetNamesAreReportedBeforeDispatch() {
        for (name in listOf("", "../main", ".main", "main.", "a/b", "a\\b", "x:y", "🙂", "x".repeat(65))) {
            assertNotNull(name, pluginFileNameError(name, emptyList()))
        }
        for (name in listOf("中文插件", "a-b_1", "a.1", "a b", "x".repeat(64))) {
            assertNull(name, pluginFileNameError(name, emptyList()))
        }
    }

    @Test fun suggestedNamesAreUniqueAndBounded() {
        assertEquals("main1", nextPluginFileName(tree, "main"))
        assertTrue(nextPluginFileName(tree, "a".repeat(64) + "_副本").length <= 64)
    }
}
