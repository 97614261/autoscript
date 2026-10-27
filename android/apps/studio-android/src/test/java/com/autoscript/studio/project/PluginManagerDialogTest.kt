package com.autoscript.studio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PluginManagerDialogTest {
    @Test fun retainsAllEightOriginalOptionsAndTheirOrder() {
        assertEquals(listOf("插件创建", "插件删除", "插件另存", "插件检错", "全部插件检错", "插件分组", "存储为模板", "未调用插件"),
            PluginManagerAction.entries.map { it.label })
        assertEquals(8, PluginManagerAction.entries.map { it.label }.toSet().size)
    }

    @Test fun postponedTemplateIsVisibleButNeverDispatchable() {
        assertEquals("后续开放", PluginManagerAction.TEMPLATE.description)
        assertTrue(!pluginManagerActionEnabled(PluginManagerAction.TEMPLATE, true))
        assertTrue(PluginManagerAction.entries.filter { it != PluginManagerAction.TEMPLATE }.all { pluginManagerActionEnabled(it, true) })
    }

    @Test fun busyStateDisablesEveryOptionAndDeletionStillRequiresConfirmation() {
        assertTrue(PluginManagerAction.entries.none { pluginManagerActionEnabled(it, false) })
        assertTrue(PluginManagerAction.DELETE.description.contains("确认"))
    }
}
