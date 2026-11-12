package com.autoscript.studio

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.autoscript.project.store.*
import kotlinx.coroutines.*

/** Project-owned authoring metadata only. Loading never starts a test or script. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ImageRecipeDialog(snapshot:ProjectSnapshot,store:ProjectStore,current:ProjectImageRecipe?,
    onDismiss:()->Unit,onLoad:(ProjectImageRecipe)->Unit) {
    var full by remember {mutableStateOf(false)};var recipes by remember {mutableStateOf(emptyList<ProjectImageRecipe>())}
    var loaded by remember {mutableStateOf(false)};var busy by remember {mutableStateOf(false)};var reload by remember {mutableIntStateOf(0)}
    var name by remember {mutableStateOf("图像方案")};var selected by remember {mutableStateOf<String?>(null)}
    var query by remember {mutableStateOf("")};var message by remember {mutableStateOf("保存处理步骤、范围和识别参数；不保存变量、截图或测试证明。")}
    StudioSystemLogEffect("图像方案", message, skipInitial = true)
    var delete by remember {mutableStateOf<ProjectImageRecipe?>(null)};var load by remember {mutableStateOf<ProjectImageRecipe?>(null)}
    val scope=rememberCoroutineScope()
    val savePrompt=rememberResourceSavePrompt()
    val chosen=recipes.firstOrNull {it.name==selected}
    val workspace = LocalImageWorkspace.current
    fun close() {if(!busy) onDismiss() else {workspace?.cancelNavigation?.invoke();message="正在提交方案，请稍候"}}
    LaunchedEffect(snapshot.manifest.projectId,reload) {
        loaded=false;recipes=emptyList()
        try {recipes=withContext(Dispatchers.IO) {store.readImageRecipes(snapshot.manifest.projectId)};loaded=true}
        catch(cancelled:CancellationException) {throw cancelled}
        catch(_:Exception) {message="方案文件损坏或不可读，原文件保留；刷新前不允许覆盖"}
    }
    fun commit(next:List<ProjectImageRecipe>,success:String) {
        if(!loaded || busy) return
        val expected=recipes;busy=true
        scope.launch {
            try {
                withContext(Dispatchers.IO) {store.updateImageRecipes(snapshot.manifest.projectId,next,expected)}
                ensureActive();recipes=next;message=success
            } catch(cancelled:CancellationException) {throw cancelled}
            catch(_:Exception) {loaded=false;message="保存失败、资源变化或存在并发修改；未覆盖，请刷新后重试"}
            finally {busy=false}
        }
    }
    fun saveCopy(recipe:ProjectImageRecipe) {
        if(!loaded || busy) return
        val candidate=try {recipe.copy(name=name.trim()).also {it.validateResources(snapshot.manifest.resources)}}
        catch(_:Exception) {message="名称需1–32字，引用资源须有效";return}
        val expected=recipes;busy=true
        scope.launch {
            try {
                val collision=expected.any {it.name==candidate.name}
                val choice=if(collision) savePrompt.choose(candidate.name,"覆盖只替换这个图像方案；已有积木不会跟着改变。") else SaveConflictChoice.NUMBERED
                val saved=if(choice==SaveConflictChoice.REPLACE) candidate else candidate.copy(name=numberedSaveName(candidate.name,expected.map {it.name}))
                val next=if(choice==SaveConflictChoice.REPLACE) expected.map {if(it.name==saved.name) saved else it} else expected+saved
                withContext(Dispatchers.IO) {store.updateImageRecipes(snapshot.manifest.projectId,next,expected)}
                ensureActive();recipes=next;selected=saved.name;message="已保存 ${saved.name}；已有积木不随方案改变"
            } catch(cancelled:CancellationException) {throw cancelled}
            catch(_:Exception) {loaded=false;message="保存失败、资源变化或超过预算；请刷新后重试，当前配置保留"}
            finally {busy=false}
        }
    }
    ProvideTextStyle(LocalTextStyle.current.copy(lineHeight=14.sp)) {
    ImageWorkspacePanel("图像方案",{ close() },fullScreen=full,onToggleFullScreen={full=!full},compactHeader=true,confirmEnabled=!busy,closeEnabled=!busy) {
        Column(Modifier.fillMaxWidth().heightIn(max=if(full)1000.dp else 540.dp).padding(8.dp),verticalArrangement=Arrangement.spacedBy(6.dp)) {
            ImageWorkspaceSection("保存当前配置") {
                Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(4.dp)) {
                    ImageToolCompactInput("名称",name,!busy,Modifier.weight(1f)) {name=it.take(64)}
                    ImageToolCompactAction("保存",loaded && !busy && current!=null,primary=true) {saveCopy(requireNotNull(current))}
                }
                if(current!=null) Text("${current.sourceWidth}×${current.sourceHeight}px · ${current.steps.size}步 · ${current.tail?.kind ?: "仅处理/范围"}",
                    fontSize=10.sp,color=MaterialTheme.colorScheme.onSurfaceVariant,maxLines=1,overflow=TextOverflow.Ellipsis)
            }
            Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(4.dp)) {
                ImageToolCompactInput("查找方案",query,!busy,Modifier.weight(1f)) {query=it.take(64)}
                ImageToolCompactAction("刷新",!busy) {reload++}
            }
            LazyColumn(Modifier.weight(1f).fillMaxWidth(),verticalArrangement=Arrangement.spacedBy(4.dp)) {
                items(recipes.filter {it.name.contains(query,true)},key={it.name}) {recipe ->
                    val active=selected==recipe.name
                    Column(Modifier.fillMaxWidth().background(if(active) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,RoundedCornerShape(6.dp))
                        .border(1.dp,if(active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,RoundedCornerShape(6.dp))
                        .semantics {this.selected=active}.clickable(enabled=!busy) {selected=recipe.name;name=recipe.name}.padding(horizontal=8.dp,vertical=6.dp)) {
                        Text(recipe.name,fontSize=12.sp,fontWeight=FontWeight.Medium,maxLines=1,overflow=TextOverflow.Ellipsis)
                        Text("${recipe.sourceWidth}×${recipe.sourceHeight}px · ${recipe.steps.size}步 · ${recipe.tail?.kind ?: "仅处理"}",
                            fontSize=10.sp,color=MaterialTheme.colorScheme.onSurfaceVariant,maxLines=1,overflow=TextOverflow.Ellipsis)
                    }
                }
                if(loaded && recipes.isEmpty()) item {Text("尚无图像方案。先处理图片，再保存当前配置。",fontSize=11.sp)}
                else if(loaded && recipes.none {it.name.contains(query,true)}) item {Text("没有匹配的方案",fontSize=11.sp)}
            }
            HorizontalDivider(color=MaterialTheme.colorScheme.outlineVariant)
            FlowRow(horizontalArrangement=Arrangement.spacedBy(3.dp),verticalArrangement=Arrangement.spacedBy(2.dp)) {
                ImageToolCompactAction("载入所选",loaded && !busy && chosen!=null,primary=true) {
                    try {
                        val recipe=requireNotNull(chosen)
                        recipe.validateResources(snapshot.manifest.resources)
                        require(current==null || current.sourceWidth==recipe.sourceWidth && current.sourceHeight==recipe.sourceHeight)
                        load=recipe
                    } catch(_:Exception) {message="来源尺寸不一致或引用资源缺失；不会自动缩放或替换资源"}
                }
                ImageToolCompactAction("复制",loaded && !busy && chosen!=null) {saveCopy(requireNotNull(chosen))}
                ImageToolCompactAction("重命名",loaded && !busy && chosen!=null) {
                    try {
                        val renamed=requireNotNull(chosen).copy(name=name.trim());require(recipes.none {it.name==renamed.name && it.name!=chosen.name})
                        commit(recipes.map {if(it.name==chosen.name) renamed else it},"已重命名为 ${renamed.name}");selected=renamed.name
                    } catch(_:Exception) {message="名称无效或已存在，原方案未改变"}
                }
                ImageToolCompactAction("删除",loaded && !busy && chosen!=null) {delete=chosen}
            }
            ImageWorkspaceHint(message)
        }
    }
    }
    load?.let {recipe -> EditorPromptDialog("载入图像方案？","将从本工作台原图重算${recipe.steps.size}步，作为一次可撤销预览；不会自动测试、保存图片或加入积木。",
        {load=null},{load=null;onLoad(recipe)},"保留当前","载入方案")}
    delete?.let {recipe -> EditorPromptDialog("删除图像方案？","只删除 ${recipe.name} 的作者配置，不删图片/字库，不改变已有积木。",
        {delete=null},{delete=null;commit(recipes.filter {it.name!=recipe.name},"图像方案已删除；资源与已有积木保留")},"取消","删除")}
}
