package com.autoscript.studio

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Simple actions stay beside the canvas; only editors that need parameters open a panel. */
@Composable
internal fun ImageToolSecondaryBar(menu: ImageToolbarMenu, mode: ImageToolMode, polygon: Boolean,
    history: ImagePolygonHistory, selectedVertex: Int, polygonGesture: String,
    workspace: ImageWorkspaceActions?, recorderAvailable: Boolean, zoomMode: Boolean,
    onMode: (ImageToolMode) -> Unit, onShape: (Boolean) -> Unit, onPolygonGesture: (String) -> Unit,
    onPublishPolygon: () -> Unit, onDeleteVertex: () -> Unit,
    onView: (String) -> Unit, onClose: () -> Unit, touchTools: Boolean = false) {
    val scroll = rememberScrollState()
    LaunchedEffect(menu) { scroll.scrollTo(0) }
    Row(Modifier.fillMaxWidth().padding(start=4.dp,end=2.dp,bottom=4.dp), verticalAlignment=Alignment.CenterVertically) {
        Row(Modifier.weight(1f).horizontalScroll(scroll), horizontalArrangement=Arrangement.spacedBy(4.dp),
            verticalAlignment=Alignment.CenterVertically) {
            fun color(selected: Boolean) = if (selected) ButtonBlue else ButtonGrey
            when(menu) {
                ImageToolbarMenu.MODE -> (if (touchTools) touchToolModes else ImageToolMode.menuOrder).forEach { entry ->
                    ImageToolButton(entry.label, color(entry==mode), imageToolMenuAvailable(entry,recorderAvailable)) { onMode(entry) }
                }
                ImageToolbarMenu.SELECTION -> {
                    ImageToolButton("矩形",color(!polygon),true) {onShape(false)}
                    ImageToolButton("多边形",color(polygon),true) {onShape(true)}
                    if(polygon) {
                        listOf("edit" to "加点/修形","move" to "拖选区","pan" to "移图").forEach { (key,label) ->
                            ImageToolButton(label,color(polygonGesture==key),key=="edit" || key=="pan" || history.points.isNotEmpty()) {onPolygonGesture(key)}
                        }
                        ImageToolButton("撤点",ButtonGrey,history.canUndo) {history.undo();onPublishPolygon()}
                        ImageToolButton("重做",ButtonGrey,history.canRedo) {history.redo();onPublishPolygon()}
                        ImageToolButton("删点",ButtonGrey,selectedVertex in history.points.indices,onDeleteVertex)
                        Text("${history.points.size}/128点",fontSize=10.sp,color=Color(0xffc4cede))
                    }
                }
                ImageToolbarMenu.VIEW -> {
                    ImageToolButton(if(zoomMode) "结束拖图" else "拖图",color(zoomMode),true) {onView("pan")}
                    listOf("out" to "缩小","in" to "放大","fit" to "适应","actual" to "100%").forEach { (key,label) ->
                        ImageToolButton(label,ButtonGrey,true) {onView(key)}
                    }
                    workspace?.let { actions ->
                        ImageToolButton(if(actions.showingOriginal) "处理图" else "原图",ButtonGrey,!actions.busy,actions.onOriginal)
                        ImageToolButton("还原",ButtonGrey,!actions.busy,actions.onRestore)
                        ImageToolButton("标尺",ButtonGrey,!actions.busy) {actions.onQuickAction("rulers")}
                    }
                }
                ImageToolbarMenu.TEST, ImageToolbarMenu.PROCESS -> workspace?.let { actions ->
                    (if(menu==ImageToolbarMenu.TEST) imageQuickTests else imageQuickProcessing).forEach { (key,label) ->
                        ImageToolButton(label,ButtonGrey,actions.canQuickAction(key)) {actions.onQuickAction(key)}
                    }
                }
            }
        }
        ImageToolButton("收起",ButtonGrey,true,onClose)
    }
}
