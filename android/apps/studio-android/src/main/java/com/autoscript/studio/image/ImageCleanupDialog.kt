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
    onDismiss:()->Unit,onApply:(String,ProjectImageCleanup)->Unit) {
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
    fun close() {if(dirty) discard=true else onDismiss()}
    ProvideTextStyle(LocalTextStyle.current.copy(lineHeight=14.sp)) {
    StudioPanelDialog("数字图像清理",::close,fullScreen=full,onToggleFullScreen={full=!full},compactHeader=true,showFooter=false) {
        BoxWithConstraints(Modifier.fillMaxWidth().heightIn(max=if(full)1000.dp else 570.dp)) {
        val previewHeight=(maxHeight*.32f).coerceIn(48.dp,if(full)220.dp else 144.dp).value.toInt()
        Column(Modifier.fillMaxSize()) {
            if(bitmap!=null) {
                Row {ImageToolCompactAction(if(original) "● 原图" else "原图",true) {original=true};ImageToolCompactAction(if(!original) "● 清理图" else "清理图",true) {original=false}}
                ImageProcessingPreviewCanvas(if(original) bitmap else preview?.bitmap ?: bitmap,previewHeight,label="数字清理预览")
                Text(if(candidate==null) "参数无效 · 显示原图" else preview?.message.orEmpty(),fontSize=10.sp,modifier=Modifier.padding(6.dp))
            }
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(8.dp),verticalArrangement=Arrangement.spacedBy(6.dp)) {
                DesignerChoice("处理",imageCleanupLabels[mode].orEmpty(),imageCleanupLabels.map {it.key to it.value},true) {mode=it}
                DesignerChoice("前景",if(foreground=="light") "浅色字" else "深色字",listOf("light" to "浅色字","dark" to "深色字"),true) {foreground=it}
                Row(horizontalArrangement=Arrangement.spacedBy(4.dp)) {
                    ImageToolCompactInput("灰度阈值",threshold,true,Modifier.weight(1f),true) {threshold=it.take(3)}
                    // Keep invalid text editable after switching modes; never silently replace it.
                    if(mode=="despeckle" || area.toIntOrNull() !in 1..4096)
                        ImageToolCompactInput("保留面积≥",area,true,Modifier.weight(1f),true) {area=it.take(4)}
                }
                if(mode=="despeckle") {
                    DesignerChoice("连通",connectivity,listOf("4" to "4邻接","8" to "8邻接"),true) {connectivity=it}
                    ImageToolCompactAction("保留小数点等小区域（面积1）",true) {area="1"}
                } else DesignerChoice("半径 px",radius,listOf("1" to "1 px","2" to "2 px"),true) {radius=it}
                if(candidate==null) Text("阈值0–255、面积1–4096、半径1–2；不得超过图像与邻域计算预算。",fontSize=10.sp)
                Text("输出黑白图，不自动猜字体或阈值。去噪可能删掉小数点，膨胀可能粘连数字；请对照预览并重新测试字库。",fontSize=10.sp)
                if(bitmap==null) Text("没有原图，本窗只配置参数；运行时按来源帧尺寸检查预算。",fontSize=10.sp)
            }
            HorizontalDivider()
            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceEvenly) {
                ImageToolCompactAction("取消清理",true,onClick=::close)
                GlyphMakerPrimaryAction("应用清理",candidate!=null && (bitmap==null || preview?.ready==true)) {onApply(mode,requireNotNull(candidate))}
            }
        }
        }
    }
    }
    if(discard) EditorPromptDialog("放弃清理配置？","未应用参数不会改变图片或积木。",{discard=false},{discard=false;onDismiss()},"继续编辑","放弃")
}
