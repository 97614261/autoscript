package com.autoscript.studio

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

internal class ImageWorkspaceNavigation(initialPanel: String? = null) {
    var panel by mutableStateOf(initialPanel)
        private set
    private var pending: (() -> Unit)? = null

    fun open(next: String, requestClose: (() -> Unit)?) {
        if (panel == next) return
        afterClose(requestClose) { panel = next }
    }

    fun afterClose(requestClose: (() -> Unit)?, action: () -> Unit) {
        if (panel == null) action() else {
            pending = action
            if (requestClose != null) requestClose() else finish()
        }
    }

    fun finish() {
        panel = null
        val action = pending
        pending = null
        action?.invoke()
    }

    fun cancelPending() { pending = null }
}

/** A parameter editor borrows the workspace canvas; it never owns the source bitmap. */
internal class ImageWorkspaceSurface {
    val selection = mutableStateOf(ImageToolSelection())
    val viewport = mutableStateOf<ImageToolViewport?>(null)
    val mode = mutableStateOf(ImageToolMode.CROP)
    var toolbarMenu by mutableStateOf<ImageToolbarMenu?>(null)
    var polygonEditing by mutableStateOf(false)
    var canvas by mutableStateOf<(@Composable () -> Unit)?>(null)
    var canvasOwner: Any? = null
    var requestClose: (() -> Unit)? = null
    var cancelNavigation: () -> Unit = {}
    var clearDraft by mutableStateOf<(() -> Unit)?>(null)
    var backToTools by mutableStateOf<(() -> Unit)?>(null)
    val panelPlacement = ImagePanelPlacementState()
}

internal val LocalImageWorkspace = staticCompositionLocalOf<ImageWorkspaceSurface?> { null }

@Composable
internal fun ImageWorkspacePanel(
    title: String, onDismiss: () -> Unit, fullScreen: Boolean = false,
    onToggleFullScreen: (() -> Unit)? = null, compactHeader: Boolean = true,
    showFooter: Boolean = false, confirmEnabled: Boolean = true,
    headerTitle: (@Composable () -> Unit)? = null, confirmLabel: String = "关闭",
    onConfirm: (() -> Unit)? = null, landscapeWidthDp: Int? = null, closeEnabled: Boolean = true,
    content: @Composable () -> Unit,
) {
    val workspace = LocalImageWorkspace.current
    if (workspace != null) {
        val latestClose by rememberUpdatedState(onDismiss)
        val close = remember { { latestClose() } }
        DisposableEffect(workspace) {
            workspace.requestClose = close
            onDispose { if (workspace.requestClose === close) workspace.requestClose = null }
        }
    }
    val frame: @Composable (Modifier) -> Unit = { bounds ->
        val panelDrag by rememberUpdatedState(LocalImagePanelDrag.current)
        val draggable = panelDrag != null
        ImageWorkspaceTheme {
            Surface(bounds, color = MaterialTheme.colorScheme.surface, contentColor = MaterialTheme.colorScheme.onSurface,
                shape = RoundedCornerShape(if (fullScreen && workspace == null) 0.dp else 10.dp),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant), shadowElevation = 8.dp) {
                Column {
                    Row(Modifier.fillMaxWidth().heightIn(min = 36.dp).background(MaterialTheme.colorScheme.surfaceContainer)
                        .padding(horizontal = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        workspace?.backToTools?.let { back ->
                            IconButton(back, Modifier.size(28.dp), enabled = closeEnabled) {
                                Icon(Icons.Filled.ArrowBack, "返回图像处理", Modifier.size(16.dp))
                            }
                        }
                        Row(Modifier.weight(1f).heightIn(min = 32.dp).padding(horizontal = 4.dp)
                            .then(if (draggable) Modifier.semantics { contentDescription = "拖动图像面板" }
                                .pointerInput(Unit) {
                                    detectDragGestures { change, delta -> change.consume(); panelDrag?.invoke(delta) }
                                } else Modifier), verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            if (draggable) {
                                val gripColor = MaterialTheme.colorScheme.onSurfaceVariant
                                Canvas(Modifier.size(8.dp, 14.dp)) {
                                    for (column in 0..1) for (row in 0..2)
                                        drawCircle(gripColor, 1.dp.toPx(), Offset((2 + column * 4).dp.toPx(), (3 + row * 4).dp.toPx()))
                                }
                            }
                            Box(Modifier.weight(1f)) {
                            ProvideTextStyle(MaterialTheme.typography.labelMedium.copy(
                                color = MaterialTheme.colorScheme.onSurface, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)) {
                                if (headerTitle == null) Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) else headerTitle()
                            }
                            }
                        }
                        if (workspace == null && onToggleFullScreen != null) IconButton(onToggleFullScreen, Modifier.size(28.dp)) {
                            Icon(painterResource(if (fullScreen) R.drawable.ic_panel_restore_20 else R.drawable.ic_panel_fullscreen_20),
                                if (fullScreen) "还原窗口" else "展开窗口", Modifier.size(16.dp))
                        }
                        IconButton({ workspace?.cancelNavigation?.invoke(); onDismiss() }, Modifier.size(28.dp), enabled = closeEnabled) {
                            Icon(Icons.Filled.Close, if (workspace == null) "关闭$title" else "收起$title", Modifier.size(16.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    Box(Modifier.weight(1f, fill = workspace != null).fillMaxWidth()) { content() }
                    if (showFooter) ImageWorkspaceFooter {
                        ImageToolCompactAction(confirmLabel, confirmEnabled, Modifier.weight(1f), primary = true) { (onConfirm ?: onDismiss)() }
                    }
                }
            }
        }
    }
    if (workspace != null) frame(Modifier.fillMaxSize())
    else StudioDialog(onDismissRequest = { if (closeEnabled) onDismiss() }, properties = DialogProperties(usePlatformDefaultWidth = false,
        dismissOnBackPress = closeEnabled, dismissOnClickOutside = closeEnabled)) {
        val configuration = LocalConfiguration.current
        val placement = remember { ImagePanelPlacementState() }
        ImageDraggablePanelHost(configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE,
            placement, Modifier.fillMaxSize().imePadding(), draggable = !fullScreen) { position ->
            val width = studioToolPanelWidth(maxWidth.value, maxHeight.value, LocalDensity.current.fontScale,
                landscapeWidthDp = landscapeWidthDp ?: 440).dp
            frame(if (fullScreen) position.fillMaxSize() else position.width(width).heightIn(
                max = studioPanelMaxHeight(maxHeight.value, configuration.screenHeightDp, 16).coerceAtMost(620f).dp))
        }
    }
}

@Composable
internal fun ImageWorkspaceDraftClear(onClear: (() -> Unit)?) {
    val workspace = LocalImageWorkspace.current ?: return
    val latestClear by rememberUpdatedState(onClear)
    val clear = remember { { latestClear?.invoke(); Unit } }
    SideEffect { workspace.clearDraft = if (onClear == null) null else clear }
    DisposableEffect(workspace) { onDispose { if (workspace.clearDraft === clear) workspace.clearDraft = null } }
}

/** Register a canvas in the existing workspace instead of allocating a second preview area. */
@Composable
internal fun ImageWorkspaceCanvasSlot(modifier: Modifier, content: @Composable (Modifier) -> Unit) {
    val workspace = LocalImageWorkspace.current
    if (workspace == null) content(modifier) else {
        val owner = remember { Any() }
        val latestContent by rememberUpdatedState(content)
        val canvas = remember(owner) { @Composable { CompositionLocalProvider(LocalImageWorkspace provides workspace) { latestContent(Modifier.fillMaxSize()) } } }
        SideEffect { workspace.canvasOwner = owner; workspace.canvas = canvas }
        DisposableEffect(workspace, owner) {
            onDispose { if (workspace.canvasOwner === owner) { workspace.canvas = null; workspace.canvasOwner = null } }
        }
    }
}

@Composable
internal fun rememberImageWorkspaceViewport(bitmap: Bitmap?): MutableState<ImageToolViewport?> =
    LocalImageWorkspace.current?.viewport ?: remember(bitmap) { mutableStateOf(null) }

internal data class ImageWorkspaceCapture(
    val designWidth: Int, val designHeight: Int, val scaleMode: String,
    val initialMode: ImageToolMode, val initialSearchPolygon: String?,
    val pointerInputSupported: Boolean, val onCapture: () -> Unit,
    val onEmit: (ImageToolCodeGen.Snippet) -> Unit, val onOpenRecorder: (() -> Unit)?,
    val onSaveRegion: (ImageToolCodeGen.Roi) -> Unit, val message: String?,
    val onTemplateSaved: ((String) -> Unit)?,
)

internal data class ImageWorkspaceActions(
    val surface: ImageWorkspaceSurface,
    val onSelection: () -> Unit, val onProcessing: () -> Unit, val onOcr: () -> Unit,
    val onUndo: (() -> Unit)?, val onRedo: (() -> Unit)?, val onOriginal: () -> Unit,
    val showingOriginal: Boolean, val onRestore: () -> Unit,
    val onMapPoint: (Int, Int) -> Pair<Int, Int>, val sourceWidth: Int, val sourceHeight: Int,
    val busy: Boolean, val captureAvailable: Boolean,
    val panelOpen: Boolean,
    val onTest: () -> Unit,
    val sampleColor: (Int, Int) -> Int,
    val mapSelection: (ImageToolSelection) -> ImageToolSelection,
    val onQuickAction: (String) -> Unit,
    val canQuickAction: (String) -> Boolean,
)

@Composable
internal fun ImageWorkspaceImageCanvas(
    shown: Bitmap, source: Bitmap = shown, modifier: Modifier = Modifier, label: String,
    onPick: ((Int, Int) -> Unit)? = null,
    overlay: (DrawScope.(ImageToolViewport) -> Unit)? = null,
) {
    var viewport by rememberImageWorkspaceViewport(source)
    BoxWithConstraints(modifier.clipToBounds().background(ToolChrome).semantics {contentDescription=label}) {
        val width=constraints.maxWidth.toFloat();val height=constraints.maxHeight.toFloat()
        LaunchedEffect(source,width,height) {
            if(width>0 && height>0) viewport=viewport?.takeIf {it.imageWidth==source.width && it.imageHeight==source.height}
                ?.resized(width,height) ?: ImageToolViewport.fit(source.width,source.height,width,height)
        }
        if (!source.isRecycled && !shown.isRecycled) ImageToolCanvas(source,viewport,
            if(onPick!=null) ImageToolMode.COLOR else ImageToolMode.CROP,onPick==null,false,
            ImageToolSelection(),Offset.Zero,null,null,onViewport={viewport=it},onSelection={selected ->
                selected.points.lastOrNull()?.let {onPick?.invoke(it.x,it.y)}
            },onCrosshair={},onSlide={_,_->},displayBitmap=shown)
        if(overlay!=null) Canvas(Modifier.fillMaxSize()) {viewport?.let {overlay(it)}}
    }
}

@Composable
internal fun ImageWorkspaceRegionPreview(bitmap: Bitmap, region: ImageToolCodeGen.Roi?, enabled: Boolean,
    height: Int, onRegion: (ImageToolCodeGen.Roi) -> Unit) {
    ImageWorkspaceCanvasSlot(Modifier.fillMaxWidth().height(height.dp)) { bounds ->
        var viewport by rememberImageWorkspaceViewport(bitmap)
        BoxWithConstraints(bounds.clipToBounds().background(ToolChrome)) {
            val w = constraints.maxWidth.toFloat(); val h = constraints.maxHeight.toFloat()
            LaunchedEffect(bitmap, w, h) {
                if (w > 0 && h > 0) viewport = viewport?.takeIf { it.imageWidth == bitmap.width && it.imageHeight == bitmap.height }
                    ?.resized(w, h) ?: ImageToolViewport.fit(bitmap.width, bitmap.height, w, h)
            }
            ImageToolCanvas(bitmap, viewport, ImageToolMode.REGION, !enabled, false,
                ImageToolSelection(box = region), Offset.Zero, null, null,
                onViewport = { viewport = it }, onSelection = { it.box?.let(onRegion) },
                onCrosshair = {}, onSlide = { _, _ -> })
        }
    }
}

@Composable
internal fun ImageWorkspaceConfirmPanel(
    onDismissRequest: () -> Unit, prompt: Boolean = false, title: @Composable () -> Unit,
    text: @Composable () -> Unit, confirmButton: @Composable () -> Unit,
    dismissButton: @Composable (() -> Unit)? = null,
) {
    val inline = LocalImageWorkspace.current != null
    CompositionLocalProvider(LocalStudioPrompt provides (prompt || LocalStudioPrompt.current)) {
        ImageWorkspacePanel("图像工具", onDismissRequest, headerTitle = title, content = {
            Column(if (inline) Modifier.fillMaxSize() else Modifier.fillMaxWidth().heightIn(max = 520.dp)) {
                Box(Modifier.weight(1f, fill = inline).fillMaxWidth().verticalScroll(rememberScrollState()).padding(8.dp)) { text() }
                ImageWorkspaceFooter {
                    Row(Modifier.weight(1f).horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                        dismissButton?.invoke(); confirmButton()
                    }
                }
            }
        })
    }
}

