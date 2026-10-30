package com.autoscript.studio

import android.app.Instrumentation
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.*
import com.autoscript.core.designsystem.AutoScriptTheme
import com.autoscript.runtime.client.RuntimeClient

internal object TouchUsageAcceptanceFixture {
    fun menu(test: Instrumentation, activity: ComponentActivity, client: RuntimeClient, record: (String, () -> Unit) -> Unit) {
        val features = client.inputFeatures()
        val ui = UsageTestUi(test)
        var visible by mutableStateOf(false)
        var selected by mutableStateOf<ImageToolMode?>(null)
        test.runOnMainSync { activity.setContent { AutoScriptTheme {
            if (visible) VisualInputActionDialog(true, features, { visible = false }, { selected = it; visible = false })
        } } }
        for (action in VisualInputAction.entries) record("touch-real-menu-${action.name.lowercase()}") {
            check(inputActionUnavailableReason(action, true, features) == null) { "QA_TOUCH_DEVICE_UNSUPPORTED:${action.name}" }
            test.runOnMainSync { selected = null; visible = true }
            ui.click(action.title)
            ui.await("touch choice") { selected == action.mode && !visible }
        }
    }
}
