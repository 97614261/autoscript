package com.autoscript.studio

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
internal fun ImageCoordinateDialog(pipeline:ImageProcessingPipeline,capturedScreen:Boolean,selection:ImageToolCodeGen.Roi?,onDismiss:()->Unit) {
    var x by remember(pipeline) {mutableStateOf((selection?.left ?: 0).toString())}
    var y by remember(pipeline) {mutableStateOf((selection?.top ?: 0).toString())}
    val mapped=runCatching {pipeline.mapToOriginal(x.toInt(),y.toInt())}.getOrNull()
    StudioConfirmDialog(onDismissRequest=onDismiss,prompt=false,title={Text("处理坐标 → ${if(capturedScreen) "截图原屏" else "原图"}")},text={
        Column(verticalArrangement=Arrangement.spacedBy(6.dp)) {
            Text("处理图 ${pipeline.dimensions.first}×${pipeline.dimensions.second}px · ${pipeline.operations.size}步",fontSize=11.sp)
            ImageToolNumberFields(listOf(Triple("x", "处理图X", x), Triple("y", "处理图Y", y)), true) { key, value ->
                if (key == "x") x = value.take(5) else y = value.take(5)
            }
            Text(mapped?.let {"${if(capturedScreen) "截图时原屏" else "原图"}：X ${it.first} · Y ${it.second}"} ?: "坐标不在处理图内，未转换",fontSize=12.sp)
            Text(if(capturedScreen) "只预览整数采样关系，不自动点击，不保证现在的画面/方向仍相同。运行时请使用同任务处理帧和“还原原屏坐标”，再读取结果。"
                else "文件图片只有原图坐标，没有可信设备屏幕来源；运行时会拒绝将此文件的处理帧还原为原屏。",fontSize=11.sp)
        }
    },confirmButton={TextButton(onClick=onDismiss) {Text("知道了")}})
}
