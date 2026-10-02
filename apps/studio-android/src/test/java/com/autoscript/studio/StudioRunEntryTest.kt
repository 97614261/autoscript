package com.autoscript.studio

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StudioRunEntryTest {
    @Test fun onlyProjectLaunchOpensTheDesignedInterface() {
        assertTrue(StudioRunEntry.LAUNCH.opensInterface(hasDefinition = true))
        assertFalse(StudioRunEntry.LAUNCH.opensInterface(hasDefinition = false))
        assertFalse(StudioRunEntry.LAUNCH.opensInterface(hasDefinition = true, hasSubmittedValues = true))
        assertFalse(StudioRunEntry.EDITOR.opensInterface(hasDefinition = true))
        assertFalse(StudioRunEntry.SINGLE_STEP.opensInterface(hasDefinition = true))
    }

    // Static wiring regression: native View behavior is still checked manually on the emulator.
    @Test fun submittedInterfaceHidesBeforeTheStartupCallback() {
        val source = File("src/main/java/com/autoscript/studio/ProjectInterfacePreview.kt").readText()
        val submit = source.substringAfter("confirming = true").substringBefore("\"close\" ->")
        val hide = submit.indexOf("dialog.hide()")
        val start = submit.indexOf("confirm(validated)")
        assertTrue("设计弹窗必须在提交启动前立即隐藏", hide >= 0 && start > hide)
        assertTrue("同步提交失败应恢复弹窗", submit.contains("dialog.show()"))
        val editor = File("src/main/java/com/autoscript/studio/EditorDock.kt").readText()
        assertFalse("编辑器不应套用设计界面的运行收起逻辑", editor.contains("runPresentation"))
    }
}
