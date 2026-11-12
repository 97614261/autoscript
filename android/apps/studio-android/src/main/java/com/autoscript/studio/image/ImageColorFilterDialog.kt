package com.autoscript.studio

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
    bitmap: Bitmap? = null, snapshot: ProjectSnapshot? = null, store: ProjectStore? = null,
    onApplyAndSave: ((ProjectImageFilter) -> Unit)? = null) {
    key(bitmap, snapshot?.manifest?.projectId, initial) { ColorFilterContent(initial,onDismiss,onApply,bitmap,snapshot,store,onApplyAndSave) }
}

@Composable
@OptIn(ExperimentalLayoutApi::class)
private fun ColorFilterContent(initial: ProjectImageFilter, onDismiss: () -> Unit, onApply: (ProjectImageFilter) -> Unit,
    source: Bitmap?, snapshot: ProjectSnapshot?, store: ProjectStore?, onApplyAndSave: ((ProjectImageFilter) -> Unit)?) {
    var filter by remember { mutableStateOf(initial) }
    var selected by remember { mutableIntStateOf(0) }
    var draft by remember { mutableStateOf(ColorRuleDraft.from(initial.rules.first())) }
    var full by remember { mutableStateOf(false) }
    var showOriginal by remember { mutableStateOf(false) }
    var discard by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("") }
    var showPresets by remember { mutableStateOf(false) }
    StudioSystemLogEffect("滤色工具", message, skipInitial = true)
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
    val workspace = LocalImageWorkspace.current
    fun close() { if (saving) { workspace?.cancelNavigation?.invoke(); message="正在保存方案，请稍候" } else if (dirty) discard=true else onDismiss() }
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
                previewMessage="前景 $processed px"
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: OutOfMemoryError) { if(ticket==previewTicket) previewMessage="预览内存不足，原图保留；请缩小图片范围" }
        catch (_: Exception) { if(ticket==previewTicket) previewMessage="预览失败或超过预算，原图保留；请减少规则/图片范围" }
        finally { result?.recycle();frozen?.recycle();if(ticket==previewTicket) previewBusy=false }
    }
    ProvideTextStyle(LocalTextStyle.current.copy(lineHeight=14.sp)) {
    ImageWorkspacePanel("滤色",{ close() },fullScreen=full,onToggleFullScreen={full=!full},showFooter=false,compactHeader=true) {
        BoxWithConstraints(Modifier.fillMaxWidth()) {
        val short = studioShortViewport(maxHeight.value)
        val previewHeight = if (short) studioPopupPreviewHeight(maxHeight.value).toInt().coerceAtLeast(1) else if (full) 180 else 112
        val previewContent: @Composable () -> Unit = {
            if (source != null) {
                ImageProcessingPreviewBar(showOriginal, { showOriginal=!showOriginal },
                    if(candidate==null) "参数无效，请修正颜色或容差" else if(previewBusy) "更新预览…" else previewMessage,
                    enabled=!saving, error=candidate==null || !previewBusy &&
                        (previewMessage.startsWith("预览失败") || previewMessage.startsWith("预览内存不足")))
                val shown=if(showOriginal) source else preview?.takeIf { it.first==signature }?.second ?: source
                ColorFilterCanvas(shown,source,previewHeight,!saving) { rgb ->
                    draft=draft.copy(rgb="%06X".format(rgb));message="已取色 #${draft.rgb}"
                }
            }
        }
        Column(Modifier.fillMaxWidth().heightIn(max=if(full)1000.dp else 620.dp)) {
            if (!short) previewContent()
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(8.dp),verticalArrangement=Arrangement.spacedBy(5.dp)) {
                if (short) previewContent()
                Column(verticalArrangement=Arrangement.spacedBy(4.dp)) {
                    if(filter.rules.size>1) LazyRow(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(4.dp)) {
                        itemsIndexed(filter.rules) { index,stored ->
                            val rule=if(index==selected) candidate?.rules?.get(index) ?: stored else stored
                            FilterChip(selected=index==selected,onClick={select(index)},enabled=!saving,
                                modifier=Modifier.heightIn(min=30.dp).semantics { contentDescription="滤色规则${index+1}" },
                                label={ Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(4.dp)) {
                                    Box(Modifier.size(10.dp).background(Color(0xFF000000 or rule.rgb.toLong()),RoundedCornerShape(2.dp)))
                                    Text("${index+1} ${if(rule.exclude) "排除" else "保留"}",fontSize=10.sp)
                                } })
                        }
                    }
                    Row(horizontalArrangement=Arrangement.spacedBy(4.dp),verticalAlignment=Alignment.CenterVertically) {
                        key(selected) {
                            StudioColorInput("RGB",draft.rgb,!saving,Modifier.weight(1f),StudioColorFormat.RGB_BARE,dense=true) { draft=draft.copy(rgb=it.take(7)) }
                        }
                        ImageToolToggle("排除",draft.exclude,!saving) {draft=draft.copy(exclude=it)}
                    }
                    Row(horizontalArrangement=Arrangement.spacedBy(4.dp)) {
                        ImageToolCompactInput("R差",draft.r,!saving,Modifier.weight(1f),numeric=true,dense=true) { draft=draft.copy(r=it.take(3)) }
                        ImageToolCompactInput("G差",draft.g,!saving,Modifier.weight(1f),numeric=true,dense=true) { draft=draft.copy(g=it.take(3)) }
                        ImageToolCompactInput("B差",draft.b,!saving,Modifier.weight(1f),numeric=true,dense=true) { draft=draft.copy(b=it.take(3)) }
                    }
                    if(filter.space=="custom") ImageToolChoice("本条方式",draft.space,listOf("absolute" to "绝对","relative" to "相对"),!saving,dense=true) { draft=draft.copy(space=it) }
                    FlowRow(horizontalArrangement=Arrangement.spacedBy(2.dp)) {
                        ImageToolCompactAction("加色",!saving && candidate!=null && filter.rules.size<32) {
                            filter=requireNotNull(candidate).copy(rules=requireNotNull(candidate).rules+ProjectColorRule(0xFFFFFF));selected=filter.rules.lastIndex;draft=ColorRuleDraft.from(filter.rules[selected])
                        }
                        ImageToolCompactAction("删色",!saving && filter.rules.size>1) {
                            filter=filter.copy(rules=filter.rules.filterIndexed { index,_ -> index!=selected });selected=selected.coerceAtMost(filter.rules.lastIndex);draft=ColorRuleDraft.from(filter.rules[selected])
                        }
                        ImageToolCompactAction("白字",!saving) { draft=ColorRuleDraft.from(ProjectColorRule(0xFFFFFF,32,32,32)) }
                        ImageToolCompactAction("黑字",!saving) { draft=ColorRuleDraft.from(ProjectColorRule(0,32,32,32)) }
                        ImageToolCompactAction("还原",!saving) { draft=ColorRuleDraft.from(filter.rules[selected]) }
                    }
                    if(candidate==null) ImageWorkspaceHint("颜色需6位十六进制，RGB容差0–255；或图片/规则超出处理预算。不会用旧规则替代非法输入。",error=true)
                }
                Row(horizontalArrangement=Arrangement.spacedBy(4.dp)) {
                    Box(Modifier.weight(1f)) { ImageToolChoice("色差",filter.space,listOf("absolute" to "绝对","relative" to "相对","custom" to "逐条"),!saving,dense=true) { filter=filter.copy(space=it) } }
                    Box(Modifier.weight(1.2f)) { ImageToolChoice("输出",filter.output,listOf("binary" to "黑白","keep" to "透明保留"),!saving,dense=true) {filter=filter.copy(output=it)} }
                }
                ImageToolToggle("反选颜色",filter.invert,!saving) {filter=filter.copy(invert=it)}
                if(store!=null && snapshot!=null) {
                    ImageToolCompactAction(if(showPresets) "收起方案" else "滤色方案 (${presets.size})",!saving) {showPresets=!showPresets}
                    if(showPresets) {
                    Row {
                        ImageToolCompactInput("方案名",presetName,!saving,Modifier.weight(1f),dense=true) { presetName=it.take(64) }
                        ImageToolCompactAction("存方案",!saving && loaded && candidate!=null, onClick=::saveCurrentPreset)
                        ImageToolCompactAction("刷新",!saving) { reload++ }
                    }
                    if(presets.isNotEmpty()) LazyColumn(Modifier.fillMaxWidth().heightIn(max=88.dp)) {
                        itemsIndexed(presets) { _,preset -> Row {
                            ImageToolCompactAction(preset.name,!saving && loaded,Modifier.weight(1f)) { if(dirty) replace=preset else load(preset) }
                            ImageToolCompactAction("删除",!saving && loaded) { delete=preset }
                        } }
                    }
                    }
                }
                ImageToolHelp("点主图可取原图颜色。相对模式比较通道差，不区分灰色的整体亮暗；排除规则优先。黑白找图需模板与画面使用对应滤色，透明模板则忽略未保留区域。制字后运行也需对画面应用同样处理。")
                if (short) ImageWorkspaceHint(message)
            }
            if (!short) ImageWorkspaceHint(message)
            ImageProcessingApplyFooter("应用滤色", !saving && candidate!=null, ::close,
                onApply = { onApply(requireNotNull(candidate)) },
                onApplyAndSave = onApplyAndSave?.takeIf { source != null }?.let { save -> { save(requireNotNull(candidate)) } },
                closeEnabled = !saving)
        }
        }
    }
    }
    if(discard) EditorPromptDialog("放弃未应用滤色？","当前编辑不会应用到图片或积木；已保存方案保留。",{discard=false;workspace?.cancelNavigation?.invoke()},{discard=false;onDismiss()},"继续编辑","放弃")
    replace?.let { preset -> EditorPromptDialog("载入滤色方案？","当前未应用规则会替换，图片和已保存方案不变。",{replace=null},{replace=null;load(preset)},"保留当前","载入方案") }
    delete?.let { preset -> EditorPromptDialog("删除滤色方案？","只删除方案 ${preset.name}，已加入积木的规则不变。",{delete=null},{delete=null;savePresets(presets.filter { it.name!=preset.name },"方案已删除；已有积木不受影响")},"取消","删除") }
}

@Composable
private fun ColorFilterCanvas(shown: Bitmap, source: Bitmap, height: Int, enabled: Boolean, onPick: (Int)->Unit) {
    ImageWorkspaceCanvasSlot(Modifier.fillMaxWidth().height(height.dp)) { bounds ->
        ImageWorkspaceImageCanvas(shown,source,bounds,"滤色预览取色",onPick=if(enabled) {x,y ->
            val pixel=source.getPixel(x,y)
            if(pixel ushr 24!=0) onPick(pixel and 0xFFFFFF)
        } else null)
    }
}
