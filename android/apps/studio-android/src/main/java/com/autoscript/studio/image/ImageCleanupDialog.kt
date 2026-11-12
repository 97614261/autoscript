package com.autoscript.studio

import android.graphics.Bitmap
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.autoscript.project.store.ProjectImageCleanup

internal val imageCleanupLabels=linkedMapOf("despeckle" to "小连通块去噪","erode" to "腐蚀 · 变细","dilate" to "膨胀 · 变粗","open" to "开处理 · 去细噪","close" to "闭处理 · 补小孔")

@Composable
internal fun ImageCleanupDialog(bitmap:Bitmap?,initialMode:String="despeckle",initial:ProjectImageCleanup=ProjectImageCleanup(),
    onDismiss:()->Unit,onApply:(String,ProjectImageCleanup)->Unit,onApplyAndSave:((String,ProjectImageCleanup)->Unit)?=null) {
    var mode by remember {mutableStateOf(initialMode)};var foreground by remember {mutableStateOf(initial.foreground)}
    var threshold by remember {mutableStateOf(initial.threshold.toString())};var radius by remember {mutableStateOf(initial.radius.toString())}
    var area by remember {mutableStateOf(initial.minArea.toString())};var connectivity by remember {mutableStateOf(initial.connectivity.toString())}
    var full by remember {mutableStateOf(false)};var original by remember {mutableStateOf(false)};var discard by remember {mutableStateOf(false)}
    val candidate=runCatching {ProjectImageCleanup(foreground,threshold.toInt(),radius.toInt(),area.toInt(),connectivity.toInt()).also {
        it.validate(mode,bitmap?.width ?: 1,bitmap?.height ?: 1)
    }}.getOrNull()
    val operation=candidate?.let {ImagePixelOperation(mode,cleanup=it)}
    val preview=bitmap?.let {rememberImageProcessingPreview(it,operation)}
    val dirty=mode!=initialMode || candidate!=initial || threshold!=initial.threshold.toString() || area!=initial.minArea.toString()
    val workspace = LocalImageWorkspace.current
    fun close() {if(dirty) discard=true else onDismiss()}
    ProvideTextStyle(LocalTextStyle.current.copy(lineHeight=14.sp)) {
    ImageWorkspacePanel("数字清理",{ close() },fullScreen=full,onToggleFullScreen={full=!full},compactHeader=true,showFooter=false) {
        BoxWithConstraints(Modifier.fillMaxWidth().heightIn(max=if(full)1000.dp else 570.dp)) {
        val previewHeight=(maxHeight*.32f).coerceIn(48.dp,if(full)220.dp else 144.dp).value.toInt()
        val short = studioShortViewport(maxHeight.value)
        val previewContent: @Composable () -> Unit = {
            if(bitmap!=null) {
                ImageProcessingPreviewBar(original, {original=!original},
                    if(candidate==null) "参数无效，请修正输入" else preview?.message.orEmpty(),
                    error=candidate==null || preview?.message?.startsWith("处理无效")==true || preview?.message?.startsWith("预览内存不足")==true)
                ImageProcessingPreviewCanvas(if(original) bitmap else preview?.bitmap ?: bitmap,previewHeight,label="数字清理预览")
            }
        }
        Column(Modifier.fillMaxSize()) {
            if (!short) previewContent()
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(8.dp),verticalArrangement=Arrangement.spacedBy(6.dp)) {
                if (short) previewContent()
                Row(horizontalArrangement=Arrangement.spacedBy(4.dp)) {
                    Box(Modifier.weight(1f)) { ImageToolChoice("方式",mode,listOf("despeckle" to "去噪","erode" to "变细","dilate" to "变粗","open" to "去细噪","close" to "补小孔"),true,dense=true) {mode=it} }
                    Box(Modifier.weight(1f)) { ImageToolChoice("前景",foreground,listOf("light" to "浅色字","dark" to "深色字"),true,dense=true) {foreground=it} }
                }
                Row(horizontalArrangement=Arrangement.spacedBy(4.dp)) {
                    ImageToolCompactInput("阈值",threshold,true,Modifier.weight(1f),numeric=true,dense=true) {threshold=it.take(3)}
                    if(mode=="despeckle" || area.toIntOrNull() !in 1..4096) ImageToolCompactInput("面积 ≥",area,true,Modifier.weight(1f),numeric=true,dense=true) {area=it.take(4)}
                    else Box(Modifier.weight(1f)) { ImageToolChoice("半径",radius,listOf("1" to "1 px","2" to "2 px"),true,dense=true) {radius=it} }
                }
                    if(mode=="despeckle") {
                        Row(horizontalArrangement=Arrangement.spacedBy(4.dp)) {
                            Box(Modifier.weight(1f)) { ImageToolChoice("连通",connectivity,listOf("4" to "4邻接","8" to "8邻接"),true,dense=true) {connectivity=it} }
                            ImageToolCompactAction("保留小数点",true) {area="1"}
                        }
                    } else if(area.toIntOrNull() !in 1..4096) ImageToolChoice("半径",radius,listOf("1" to "1 px","2" to "2 px"),true,dense=true) {radius=it}
                if(candidate==null) ImageWorkspaceHint("阈值0–255、面积1–4096、半径1–2；不得超过图像与邻域计算预算。",error=true)
                ImageToolHelp("输出黑白图，不自动猜字体或阈值。去噪可能删掉小数点，膨胀可能粘连数字；请对照预览并重新测试字库。")
            }
            ImageProcessingApplyFooter("应用清理", candidate!=null && (bitmap==null || preview?.ready==true), ::close,
                onApply = { onApply(mode,requireNotNull(candidate)) },
                onApplyAndSave = onApplyAndSave?.takeIf { bitmap != null }?.let { save -> { save(mode,requireNotNull(candidate)) } })
        }
        }
    }
    }
    if(discard) EditorPromptDialog("放弃清理配置？","未应用参数不会改变图片或积木。",{discard=false;workspace?.cancelNavigation?.invoke()},{discard=false;onDismiss()},"继续编辑","放弃")
}
