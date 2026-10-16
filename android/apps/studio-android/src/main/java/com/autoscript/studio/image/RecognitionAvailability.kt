package com.autoscript.studio

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.sp
import com.autoscript.core.model.RuntimeConnectionPhase
import com.autoscript.core.model.RuntimeEngineState
import com.autoscript.runtime.api.RecognitionPreviewReply as Reply
import com.autoscript.runtime.client.RuntimeClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal fun recognitionComponent(kind: String): String? = when (kind) {
    "vision.findgray" -> "opencv"
    "ocr.alphanumeric" -> "onnx"
    else -> null
}

internal fun recognitionAvailabilityProblem(raw: String?): String? {
    val status = VisionProviderStatus.parse(raw) ?: return "组件状态尚未取得，请复查运行环境"
    return when (status.state) {
        "AVAILABLE", "INSTALLED" -> null // validated code may initialize only on the explicit test.
        else -> status.label
    }
}

internal class RecognitionUnavailable(message: String) : IllegalStateException(message)

/** Read bounded cached status off Main; this neither imports nor loads a provider. */
internal suspend fun prepareRecognitionPreview(client: RuntimeClient, operation: Int) {
    val state = client.runtimeStates.value
    if (state.phase != RuntimeConnectionPhase.CONNECTED || state.sessionGeneration == null)
        throw RecognitionUnavailable("运行服务尚未连接，请检查运行环境")
    if (state.engineState in setOf(RuntimeEngineState.RUNNING, RuntimeEngineState.PAUSED, RuntimeEngineState.STOPPING))
        throw RecognitionUnavailable("脚本运行或停止清理中，请稍后测试")
    val kind = when (operation) { Reply.GRAY_TEMPLATE -> "vision.findgray"; Reply.ALPHANUMERIC -> "ocr.alphanumeric"; else -> "" }
    val component = recognitionComponent(kind) ?: return
    val inventory = withContext(Dispatchers.IO) { client.visionAvailability() }
    if (client.runtimeStates.value.sessionGeneration != state.sessionGeneration)
        throw RecognitionUnavailable("运行服务会话已变化，请重试")
    recognitionAvailabilityProblem(inventory[component])?.let { throw RecognitionUnavailable("$component · $it；请在我的/运行环境复查") }
}

@Composable
internal fun RecognitionAvailabilityNotice(kind: String, client: RuntimeClient) {
    val runtime by client.runtimeStates.collectAsState()
    var revision by remember(client) { mutableIntStateOf(0) }
    var label by remember(client, kind) { mutableStateOf("正在读取运行状态…") }
    val component = recognitionComponent(kind)
    LaunchedEffect(client, kind, runtime.phase, runtime.sessionGeneration, revision) {
        label = if (runtime.phase != RuntimeConnectionPhase.CONNECTED) "服务未连接；可继续配置，测试和加入校验需连接"
        else if (component == null) "基础图像/字库不需要额外视觉SDK；不代表已实测"
        else {
            val values = withContext(Dispatchers.IO) { client.visionAvailability() }
            "$component · ${VisionProviderStatus.parse(values[component])?.label ?: "状态待复查"}"
        }
    }
    Row(Modifier.fillMaxWidth()) {
        Text(label, Modifier.weight(1f), fontSize = 10.sp)
        TextButton(onClick = { revision++ }) { Text("复查", fontSize = 10.sp) }
    }
}
