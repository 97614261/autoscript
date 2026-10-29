package com.autoscript.studio

import android.graphics.Bitmap
import androidx.core.graphics.get
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.autoscript.core.designsystem.AutoScriptPalette
import com.autoscript.project.store.*
import kotlinx.coroutines.*

private data class ColorRuleDraft(val rgb: String, val r: String, val g: String, val b: String, val exclude: Boolean, val space: String) {
    fun parse() = ProjectColorRule(requireNotNull(rgb.removePrefix("#").takeIf { it.matches(Regex("[0-9a-fA-F]{6}")) }?.toIntOrNull(16)),
        r.toInt(),g.toInt(),b.toInt(),exclude,space)
    companion object { fun from(rule: ProjectColorRule) = ColorRuleDraft("%06X".format(rule.rgb),rule.redTolerance.toString(),rule.greenTolerance.toString(),rule.blueTolerance.toString(),rule.exclude,rule.space) }
}

/** Shared authoring: exact same settings feed pixel preview, preset, transform block and runtime. */
@Composable
internal fun ImageColorFilterDialog(initial: ProjectImageFilter, onDismiss: () -> Unit, onApply: (ProjectImageFilter) -> Unit,
    bitmap: Bitmap? = null, snapshot: ProjectSnapshot? = null, store: ProjectStore? = null) {
    key(bitmap, snapshot?.manifest?.projectId, initial) { ColorFilterContent(initial,onDismiss,onApply,bitmap,snapshot,store) }
}

@Composable
@OptIn(ExperimentalLayoutApi::class)
private fun ColorFilterContent(initial: ProjectImageFilter, onDismiss: () -> Unit, onApply: (ProjectImageFilter) -> Unit,
    source: Bitmap?, snapshot: ProjectSnapshot?, store: ProjectStore?) {
    var filter by remember { mutableStateOf(initial) }
    var selected by remember { mutableIntStateOf(0) }
    var draft by remember { mutableStateOf(ColorRuleDraft.from(initial.rules.first())) }
    var full by remember { mutableStateOf(false) }
    var showOriginal by remember { mutableStateOf(false) }
    var discard by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("先选颜色和容差；排除优先，黑白图用于形状识别，透明模板可忽略背景。") }
    var presets by remember { mutableStateOf(emptyList<ProjectImageFilterPreset>()) }
    var loaded by remember { mutableStateOf(false) }
    var presetFailure by remember { mutableStateOf(false) }
    var reload by remember { mutableIntStateOf(0) }
    var presetName by remember { mutableStateOf("滤色方案") }
    var saving by remember { mutableStateOf(false) }
    var replace by remember { mutableStateOf<ProjectImageFilterPreset?>(null) }
    var delete by remember { mutableStateOf<ProjectImageFilterPreset?>(null) }
    var preview by remember { mutableStateOf<Pair<String,Bitmap>?>(null) }
    var previewMessage by remember { mutableStateOf("") }
    var previewBusy by remember { mutableStateOf(false) }
    var previewTicket by remember { mutableLongStateOf(0) }
    val scope = rememberCoroutineScope()
    val savePrompt = rememberResourceSavePrompt()
    val candidate = runCatching {
        filter.copy(rules = filter.rules.toMutableList().apply { set(selected,draft.parse()) }.toList()).also {
            if (source != null) it.validatePixels(source.width.toLong()*source.height)
        }
    }.getOrNull()
    val signature = candidate?.encode()
    val dirty = candidate != initial || draft != ColorRuleDraft.from(filter.rules[selected])
    fun close() { if (saving) message="正在保存方案，请稍候" else if (dirty) discard=true else onDismiss() }
    fun load(preset: ProjectImageFilterPreset) { filter=preset.filter;selected=0;draft=ColorRuleDraft.from(filter.rules.first());message="已载入方案 ${preset.name}；规则复制到当前配置" }
    fun select(index: Int) {
        val current=candidate ?: run { message="请先修正当前颜色或容差，未丢弃输入"; return }
        filter=current;selected=index;draft=ColorRuleDraft.from(current.rules[index])
    }
    LaunchedEffect(store,snapshot?.manifest?.projectId,reload) {
        loaded=false;presetFailure=false;presets=emptyList()
        if (store != null && snapshot != null) try {
            presets=withContext(Dispatchers.IO) { store.readImageFilterPresets(snapshot.manifest.projectId) };loaded=true
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { presetFailure=true;message="项目方案不可读，原文件保留；可继续制作，但不能覆盖方案文件" }
    }
    fun savePresets(next: List<ProjectImageFilterPreset>, success: String) {
        if (saving || !loaded || presetFailure || store == null || snapshot == null) return
        val expected=presets;saving=true
        scope.launch {
            try {
                withContext(Dispatchers.IO) { store.updateImageFilterPresets(snapshot.manifest.projectId,next,expected) }
                ensureActive();presets=next;message=success
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { loaded=false;message="保存未完成或方案已被修改，请刷新后重试；未覆盖原方案" }
            finally { saving=false }
        }
    }
    fun saveCurrentPreset() {
        if(saving || !loaded || store==null || snapshot==null) return
        val saved=try {ProjectImageFilterPreset(presetName.trim(),requireNotNull(candidate))}
        catch(_:Exception) {message="方案名需1–32字，颜色与容差须有效";return}
        val expected=presets;saving=true
        scope.launch {
            try {
                val collision=expected.any {it.name==saved.name}
                val choice=if(collision) savePrompt.choose(saved.name,"覆盖只替换这个滤色方案；已有积木的规则不变。") else SaveConflictChoice.NUMBERED
                val final=if(choice==SaveConflictChoice.REPLACE) saved else saved.copy(name=numberedSaveName(saved.name,expected.map {it.name}))
                val next=if(choice==SaveConflictChoice.REPLACE) expected.map {if(it.name==final.name) final else it} else expected+final
                withContext(Dispatchers.IO) {store.updateImageFilterPresets(snapshot.manifest.projectId,next,expected)}
                ensureActive();presets=next;message="已保存方案 ${final.name}；已有积木不受影响"
            } catch(cancelled:CancellationException) {throw cancelled}
            catch(_:Exception) {loaded=false;message="保存失败、方案变化或超过预算，请刷新重试；当前规则保留"}
            finally {saving=false}
        }
    }
    DisposableEffect(preview) { val owned=preview?.second;onDispose { owned?.recycle() } }
    LaunchedEffect(source,signature) {
        val ticket=++previewTicket
        if (source == null || candidate == null || signature == null) { previewBusy=false;return@LaunchedEffect }
        previewBusy=true;previewMessage="更新预览…"
        var frozen: Bitmap?=null;var result: Bitmap?=null
        try {
            delay(120)
            frozen=requireNotNull(source.copy(Bitmap.Config.ARGB_8888,false))
            val image=requireNotNull(frozen);val settings=candidate
            val processed=withContext(Dispatchers.Default) {
                val work=currentCoroutineContext();val deadline=System.nanoTime()+5_000_000_000L
                val check={work.ensureActive();require(System.nanoTime()<deadline) { "滤色超时" }}
                val pixels=IntArray(image.width*image.height);image.getPixels(pixels,0,image.width,0,0,image.width,image.height)
                val output=processImagePixels(pixels,image.width,image.height,ImagePixelOperation("filter",filter=settings),check)
                check()
                result=Bitmap.createBitmap(output.argb,output.width,output.height,Bitmap.Config.ARGB_8888)
                val count=output.argb.count { (it ushr 24)!=0 && (settings.output=="keep" || (it and 0xFFFFFF)==0xFFFFFF) }
                count
            }
            ensureActive()
            if(ticket==previewTicket) {
                preview=signature to requireNotNull(result);result=null
                previewMessage="前景 $processed/${image.width*image.height}px · 轻点预览取原图颜色"
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: OutOfMemoryError) { if(ticket==previewTicket) previewMessage="预览内存不足，原图保留；请缩小图片范围" }
        catch (_: Exception) { if(ticket==previewTicket) previewMessage="预览失败或超过预算，原图保留；请减少规则/图片范围" }
        finally { result?.recycle();frozen?.recycle();if(ticket==previewTicket) previewBusy=false }
    }
    ProvideTextStyle(LocalTextStyle.current.copy(lineHeight=14.sp)) {
    StudioPanelDialog("滤色 · 规则与方案",::close,fullScreen=full,onToggleFullScreen={full=!full},showFooter=false,compactHeader=true) {
        BoxWithConstraints(Modifier.fillMaxWidth()) {
        val short = studioShortViewport(maxHeight.value)
        val previewHeight = if (short) studioPopupPreviewHeight(maxHeight.value).toInt().coerceAtLeast(1) else if (full) 180 else 112
        val previewContent: @Composable () -> Unit = {
            if (source != null) {
                FlowRow(Modifier.padding(horizontal=8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    ImageToolCompactAction(if(showOriginal) "● 原图" else "原图",true) { showOriginal=true }
                    ImageToolCompactAction(if(!showOriginal) "● 滤色图" else "滤色图",true) { showOriginal=false }
                    Text("${source.width}×${source.height}px",fontSize=10.sp,modifier=Modifier.padding(6.dp))
                }
                val shown=if(showOriginal) source else preview?.takeIf { it.first==signature }?.second ?: source
                ColorFilterCanvas(shown,source,previewHeight,!saving) { rgb ->
                    draft=draft.copy(rgb="%06X".format(rgb));message="已取原图颜色 #${draft.rgb}，不读取滤色后的黑白值"
                }
                Text(if(candidate==null) "参数无效，当前显示原图；请修正颜色或容差" else if(previewBusy) "正在生成当前规则预览…" else previewMessage,fontSize=10.sp,color=AutoScriptPalette.TextSecondary,modifier=Modifier.padding(horizontal=8.dp,vertical=3.dp))
            }
        }
        Column(Modifier.fillMaxWidth().heightIn(max=if(full)1000.dp else 620.dp)) {
            if (!short) previewContent()
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(8.dp),verticalArrangement=Arrangement.spacedBy(5.dp)) {
                if (short) previewContent()
                ImageToolChoice("滤色方式",filter.space,listOf("absolute" to "绝对 · RGB色差","relative" to "相对 · 允许共同明暗变化","custom" to "自定义 · 逐条方式"),!saving) { filter=filter.copy(space=it) }
                FlowRow(horizontalArrangement=Arrangement.spacedBy(4.dp), verticalArrangement=Arrangement.spacedBy(2.dp)) {
                    ImageToolCompactAction(if(filter.output=="binary") "● 黑白输出" else "黑白输出",!saving) { filter=filter.copy(output="binary") }
                    ImageToolCompactAction(if(filter.output=="keep") "● 透明保留" else "透明保留",!saving) { filter=filter.copy(output="keep") }
                    ImageToolCompactAction(if(filter.invert) "✓ 反选" else "反选",!saving) { filter=filter.copy(invert=!filter.invert) }
                }
                LazyColumn(Modifier.fillMaxWidth().heightIn(max=100.dp),verticalArrangement=Arrangement.spacedBy(2.dp)) {
                    itemsIndexed(filter.rules) { index,stored ->
                        val rule=if(index==selected) candidate?.rules?.get(index) ?: stored else stored
                        ImageToolCompactAction("${if(selected==index) "●" else "○"} ${index+1} · ${if(rule.exclude) "排除" else "保留"} #${"%06X".format(rule.rgb)} ±${rule.redTolerance}/${rule.greenTolerance}/${rule.blueTolerance}",!saving,
                            Modifier.semantics { contentDescription="滤色规则${index+1}" }) { select(index) }
                    }
                }
                Row(horizontalArrangement=Arrangement.spacedBy(4.dp)) {
                    ImageToolCompactInput("RGB",draft.rgb,!saving,Modifier.weight(1f)) { draft=draft.copy(rgb=it.take(7)) }
                    ImageToolCompactAction(if(draft.exclude) "排除该颜色" else "保留该颜色",!saving) { draft=draft.copy(exclude=!draft.exclude) }
                }
                Row(horizontalArrangement=Arrangement.spacedBy(4.dp)) {
                    ImageToolCompactInput("R差",draft.r,!saving,Modifier.weight(1f),numeric=true) { draft=draft.copy(r=it.take(3)) }
                    ImageToolCompactInput("G差",draft.g,!saving,Modifier.weight(1f),numeric=true) { draft=draft.copy(g=it.take(3)) }
                    ImageToolCompactInput("B差",draft.b,!saving,Modifier.weight(1f),numeric=true) { draft=draft.copy(b=it.take(3)) }
                }
                if(filter.space=="custom") ImageToolChoice("当前规则",draft.space,listOf("absolute" to "绝对","relative" to "相对"),!saving) { draft=draft.copy(space=it) }
                FlowRow(horizontalArrangement=Arrangement.spacedBy(3.dp)) {
                    ImageToolCompactAction("加颜色",!saving && candidate!=null && filter.rules.size<32) {
                        filter=requireNotNull(candidate).copy(rules=requireNotNull(candidate).rules+ProjectColorRule(0xFFFFFF));selected=filter.rules.lastIndex;draft=ColorRuleDraft.from(filter.rules[selected])
                    }
                    ImageToolCompactAction("删本条",!saving && filter.rules.size>1) {
                        filter=filter.copy(rules=filter.rules.filterIndexed { index,_ -> index!=selected });selected=selected.coerceAtMost(filter.rules.lastIndex);draft=ColorRuleDraft.from(filter.rules[selected])
                    }
                    ImageToolCompactAction("白字",!saving) { draft=ColorRuleDraft.from(ProjectColorRule(0xFFFFFF,32,32,32)) }
                    ImageToolCompactAction("黑字",!saving) { draft=ColorRuleDraft.from(ProjectColorRule(0,32,32,32)) }
                    ImageToolCompactAction("恢复本条",!saving) { draft=ColorRuleDraft.from(filter.rules[selected]) }
                }
                if(candidate==null) Text("颜色需6位十六进制，RGB容差0–255；或图片/规则超出处理预算。不会用旧规则替代非法输入。",fontSize=10.sp,color=AutoScriptPalette.Danger)
                if(store!=null && snapshot!=null) {
                    HorizontalDivider()
                    Row {
                        ImageToolCompactInput("方案名",presetName,!saving,Modifier.weight(1f)) { presetName=it.take(64) }
                        ImageToolCompactAction("保存方案",!saving && loaded && candidate!=null, onClick=::saveCurrentPreset)
                        ImageToolCompactAction("刷新方案",!saving) { reload++ }
                    }
                    if(presets.isNotEmpty()) LazyColumn(Modifier.fillMaxWidth().heightIn(max=88.dp)) {
                        itemsIndexed(presets) { _,preset -> Row {
                            ImageToolCompactAction(preset.name,!saving && loaded,Modifier.weight(1f)) { if(dirty) replace=preset else load(preset) }
                            ImageToolCompactAction("删除方案",!saving && loaded) { delete=preset }
                        } }
                    }
                }
                Text("相对模式比较通道差，不区分灰色的整体亮暗；排除规则优先。黑白找图需模板与画面使用对应滤色，透明模板则忽略未保留区域。制字后运行也需对画面应用同样处理。",fontSize=10.sp,color=AutoScriptPalette.TextSecondary)
                if (short) Text(message,fontSize=10.sp,color=AutoScriptPalette.TextSecondary)
            }
            HorizontalDivider()
            if (!short) Text(message,fontSize=10.sp,color=AutoScriptPalette.TextSecondary,modifier=Modifier.padding(horizontal=8.dp,vertical=4.dp))
            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceEvenly) {
                ImageToolCompactAction("取消滤色",!saving, onClick=::close)
                GlyphMakerPrimaryAction("应用滤色",!saving && candidate!=null) { onApply(requireNotNull(candidate)) }
            }
        }
        }
    }
    }
    if(discard) EditorPromptDialog("放弃未应用滤色？","当前编辑不会应用到图片或积木；已保存方案保留。",{discard=false},{discard=false;onDismiss()},"继续编辑","放弃")
    replace?.let { preset -> EditorPromptDialog("载入滤色方案？","当前未应用规则会替换，图片和已保存方案不变。",{replace=null},{replace=null;load(preset)},"保留当前","载入方案") }
    delete?.let { preset -> EditorPromptDialog("删除滤色方案？","只删除方案 ${preset.name}，已加入积木的规则不变。",{delete=null},{delete=null;savePresets(presets.filter { it.name!=preset.name },"方案已删除；已有积木不受影响")},"取消","删除") }
}

@Composable
private fun ColorFilterCanvas(shown: Bitmap, source: Bitmap, height: Int, enabled: Boolean, onPick: (Int)->Unit) {
    val pick by rememberUpdatedState(onPick)
    val image=remember(shown) { shown.asImageBitmap() }
    Canvas(Modifier.fillMaxWidth().height(height.dp).clipToBounds().background(Color(0xff272b37)).semantics { contentDescription="滤色预览取色" }
        .pointerInput(source,enabled) { detectTapGestures { point -> if(enabled && !source.isRecycled) {
            val viewport=glyphMakerFocus(ImageToolViewport.fit(source.width,source.height,size.width.toFloat(),size.height.toFloat()))
            viewport.toImage(point.x,point.y)?.let { (x,y) -> val pixel=source[x,y];if(pixel ushr 24 != 0) pick(pixel and 0xFFFFFF) }
        } } }) {
        val viewport=glyphMakerFocus(ImageToolViewport.fit(source.width,source.height,size.width,size.height))
        drawImage(image,dstOffset=IntOffset(viewport.translateX.toInt(),viewport.translateY.toInt()),
            dstSize=IntSize((source.width*viewport.scale).toInt().coerceAtLeast(1),(source.height*viewport.scale).toInt().coerceAtLeast(1)),filterQuality=FilterQuality.None)
    }
}
