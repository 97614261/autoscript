package com.autoscript.studio

import android.app.Activity
import android.os.Bundle
import android.graphics.Color as AndroidColor
import java.io.File
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.autoscript.core.designsystem.AutoScriptPalette
import com.autoscript.core.designsystem.AutoScriptTheme
import androidx.core.view.WindowInsetsControllerCompat
import com.autoscript.core.designsystem.hairline
import com.autoscript.core.model.RuntimeConnectionState
import com.autoscript.runtime.client.RuntimeClient
import com.autoscript.project.store.ProjectStore
import kotlin.random.Random
import kotlinx.coroutines.delay

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = AndroidColor.rgb(245, 247, 251)
        window.navigationBarColor = AndroidColor.WHITE
        // 用 WindowInsetsControllerCompat 而不是已废弃的 systemUiVisibility：
        // SystemBarScope 在“我的”页会临时切换图标明暗，两套机制混用会互相覆盖。
        WindowInsetsControllerCompat(window, window.decorView).apply {
            isAppearanceLightStatusBars = true
            isAppearanceLightNavigationBars = true
        }
        setContent { AutoScriptTheme { StudioApp() } }
    }
}

@Composable
private fun StudioApp() {
    var navigation by remember { mutableStateOf(StudioNavigationState()) }
    var runtimeEnvironmentVisible by remember { mutableStateOf(false) }
    var profileSubpage by remember { mutableStateOf<ProfileSubpage?>(null) }
    var workspaceFullScreen by remember { mutableStateOf(false) }
    var runtimeState by remember { mutableStateOf(RuntimeConnectionState()) }
    // 悬浮面板控制台的唯一数据源：每次状态快照都记一次真实变化，不是静态占位。
    val console = remember { RuntimeConsoleLog() }
    val runtimeClient = rememberRuntimeClient {
        runtimeState = it
        console.record(it)
    }
    val context = androidx.compose.ui.platform.LocalContext.current
    val projectStore = remember(context) {
        ProjectStore(File(context.applicationContext.filesDir, "projects"))
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        bottomBar = {
            if (!runtimeEnvironmentVisible && !workspaceFullScreen) {
                StudioBottomNavigation(
                    selected = navigation.destination,
                    onSelected = { destination ->
                        navigation = navigation.navigateTo(destination)
                        if (destination != StudioDestination.PROFILE) profileSubpage = null
                    },
                )
            }
        },
    ) { contentPadding ->
        Surface(
            modifier = Modifier.fillMaxSize().padding(contentPadding),
            color = MaterialTheme.colorScheme.background,
        ) {
            if (runtimeEnvironmentVisible) {
                RuntimeEnvironmentScreen(
                    state = runtimeState,
                    onBack = { runtimeEnvironmentVisible = false },
                    onRefresh = runtimeClient::refresh,
                    modifier = Modifier.fillMaxSize(),
                )
            } else when (navigation.destination) {
                StudioDestination.HOME -> HomePage(modifier = Modifier.fillMaxSize())
                StudioDestination.WORKSPACE -> ProjectPage(
                    store = projectStore,
                    runtimeClient = runtimeClient,
                    runtimeState = runtimeState,
                    consoleLines = console.lines,
                    active = true,
                    onFullScreenChanged = { workspaceFullScreen = it },
                    modifier = Modifier.fillMaxSize(),
                )
                StudioDestination.PROFILE -> profileSubpage?.let { page ->
                    ProfileSubpageScreen(
                        page = page,
                        onBack = { profileSubpage = null },
                        onOpenSubpage = { profileSubpage = it },
                        modifier = Modifier.fillMaxSize(),
                    )
                } ?: ProfilePage(
                    onOpenRuntimeEnvironment = { runtimeEnvironmentVisible = true },
                    onOpenSubpage = { profileSubpage = it },
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }
}

/** `activity_main.xml` 的 `BottomNavigationView`：22dp 图标、labeled、上下 3dp 内边距。 */
@Composable
private fun StudioBottomNavigation(
    selected: StudioDestination,
    onSelected: (StudioDestination) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(62.dp)
            .background(Color.White)
            .padding(vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        StudioDestination.entries.forEach { destination ->
            StudioBottomItem(destination, selected == destination, Modifier.weight(1f), onSelected)
        }
    }
}

@Composable
private fun StudioBottomItem(
    destination: StudioDestination,
    selected: Boolean,
    modifier: Modifier,
    onSelected: (StudioDestination) -> Unit,
) {
    val color = if (selected) AutoScriptPalette.Accent else AutoScriptPalette.TextSecondary
    val icon = when (destination) {
        StudioDestination.HOME -> R.drawable.nav_home_24
        StudioDestination.WORKSPACE -> R.drawable.nav_workspace_24
        StudioDestination.PROFILE -> R.drawable.nav_profile_24
    }
    Column(
        // activity_main 的 BottomNavigationView 设了 itemRippleColor=transparent，这里同样不要水波纹。
        modifier = modifier
            .fillMaxSize()
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
            ) { onSelected(destination) },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(painterResource(icon), contentDescription = destination.label, tint = color, modifier = Modifier.size(22.dp))
        Spacer(Modifier.height(4.dp))
        // labelVisibilityMode=labeled：Material 的选中项字号比未选中大一档。
        Text(destination.label, color = color, fontSize = if (selected) 14.sp else 12.sp, lineHeight = 16.sp)
    }
}

// ---------------------------------------------------------------------------------------------
// 主页：复刻 assets/web/home/index.html（四页轮播 + 价值面板）。参考页背景是插画，这里用渐变替代。
// ---------------------------------------------------------------------------------------------

private val HomeInk = Color(0xFF12213A)
private val HomeMuted = Color(0xFF6C7D98)

private data class HomeCapability(val title: String, val detail: String)

private data class HomeSlide(
    val eyebrow: String,
    val headline: String,
    val lead: List<String>,
    val accent: Color,
    val veil: Color,
    val mark: String,
    val capabilities: List<HomeCapability> = emptyList(),
    val code: List<String> = emptyList(),
)

private val HomeSlides = listOf(
    HomeSlide(
        eyebrow = "零基础 · 快速上手",
        headline = "可视化操作 轻松开发脚本",
        lead = listOf("常用功能清晰呈现，无需先掌握复杂代码", "从配置到运行，快速完成自动化脚本"),
        accent = Color(0xFF61A88A),
        veil = Color(0xFFD3F1E4),
        mark = "易",
        capabilities = listOf(HomeCapability("简单直观", "零基础易上手"), HomeCapability("灵活扩展", "需要时编写 Lua")),
    ),
    HomeSlide(
        eyebrow = "内置 Lua 引擎 · 灵活高效",
        headline = "从简单配置\n到复杂脚本",
        lead = listOf("可视化功能与 Lua 代码自由结合", "快速入门，也能满足进阶开发"),
        accent = Color(0xFF218FBD),
        veil = Color(0xFFC5ECFA),
        mark = "Lua",
        code = listOf("if target then", "    tap(x, y)", "    wait(300)", "end"),
    ),
    HomeSlide(
        eyebrow = "Root 运行 · 本机执行",
        headline = "在授权设备上运行脚本",
        lead = listOf("当前阶段使用 Root 后端执行截图与输入", "免 Root 与 Shizuku 等方式留待后续阶段"),
        accent = Color(0xFFBF7B34),
        veil = Color(0xFFFFDAA1),
        mark = "Root",
        capabilities = listOf(HomeCapability("Root", "本机执行"), HomeCapability("截图", "真实屏幕"), HomeCapability("输入", "点击滑动")),
    ),
    HomeSlide(
        eyebrow = "AI 编程助手 · 预留",
        headline = "描述开发需求\nAI 助手协同开发",
        lead = listOf("理解项目结构，按需读取与修改文件", "服务未配置前入口保持预留状态"),
        accent = Color(0xFF668ECF),
        veil = Color(0xFFD2E4FF),
        mark = "AI",
        capabilities = listOf(HomeCapability("理解项目", "按需读取"), HomeCapability("编写修改", "完成校验"), HomeCapability("设备协作", "观察操作")),
    ),
)

@Composable
private fun HomePage(modifier: Modifier = Modifier) {
    val background = remember {
        Brush.linearGradient(
            0f to Color(0xFFD3E1F8).copy(alpha = .62f),
            .10f to Color(0xFFD3E1F8).copy(alpha = .62f),
            .102f to Color(0xFFF6F8FC),
            .76f to Color(0xFFF6F8FC),
            .762f to Color(0xFFDEE9FA).copy(alpha = .5f),
            1f to Color(0xFFDEE9FA).copy(alpha = .5f),
            start = Offset.Zero,
            end = Offset.Infinite,
        )
    }
    Column(
        modifier = modifier.background(Color(0xFFF6F8FC)).background(background)
            .padding(start = 18.dp, top = 13.dp, end = 18.dp, bottom = 13.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        HomeCarousel(Modifier.weight(1f).fillMaxWidth())
        HomeValuePanel(Modifier.fillMaxWidth())
    }
}

@Composable
private fun HomeCarousel(modifier: Modifier) {
    val pagerState = rememberPagerState(pageCount = { HomeSlides.size })
    LaunchedEffect(pagerState.settledPage) {
        delay(4_600)
        if (!pagerState.isScrollInProgress) {
            pagerState.animateScrollToPage((pagerState.settledPage + 1) % HomeSlides.size)
        }
    }
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(24.dp),
        color = Color(0xFFEEF4FB),
        border = BorderStroke(1.dp, Color(0xFF4A75BC).copy(alpha = .16f)),
        shadowElevation = 6.dp,
    ) {
        Box(Modifier.fillMaxSize()) {
            HorizontalPager(state = pagerState, modifier = Modifier.fillMaxSize()) { page ->
                HomeSlideCard(HomeSlides[page], page)
            }
            Row(
                modifier = Modifier.align(Alignment.BottomStart).padding(start = 17.dp, bottom = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                HomeSlides.forEachIndexed { index, slide ->
                    val active = index == pagerState.currentPage
                    Box(Modifier.size(width = 27.dp, height = 24.dp), contentAlignment = Alignment.Center) {
                        Box(
                            Modifier
                                .size(width = if (active) 21.dp else 5.dp, height = 5.dp)
                                .background(if (active) slide.accent else Color(0xFF1B3452).copy(alpha = .29f), CircleShape),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun HomeSlideCard(slide: HomeSlide, index: Int) {
    val slideBackground = remember(slide) {
        Brush.linearGradient(
            0f to Color.White,
            .55f to slide.veil.copy(alpha = .55f),
            1f to slide.veil,
            start = Offset.Zero,
            end = Offset.Infinite,
        )
    }
    Box(
        Modifier.fillMaxSize().background(slideBackground)
            .drawSlideTopAccent(slide.accent)
            .padding(start = 22.dp, top = 23.dp, end = 22.dp, bottom = 20.dp),
    ) {
        Column(Modifier.fillMaxSize()) {
            HomeEyebrow(slide.eyebrow, slide.accent)
            // index.html 的 h1：font-size: clamp(24px, 7.1vw, 28px) → 360dp 视口下是 25.6px；
            // weight 850；letter-spacing: -.045em。负字距不能省——漏了它标题会宽约 4.5%，
            // 在 720×1280 上足以把参考的一行挤成两行。
            Text(
                slide.headline,
                color = HomeInk,
                fontSize = 25.6.sp,
                lineHeight = 32.sp,
                fontWeight = FontWeight.ExtraBold,
                letterSpacing = (-0.045).em,
                modifier = Modifier.padding(top = 16.dp),
            )
            Column(Modifier.padding(top = 11.dp)) {
                slide.lead.forEachIndexed { line, text ->
                    Text(
                        text,
                        color = HomeInk.copy(alpha = if (line == 0) .74f else .62f),
                        fontSize = 12.5.sp,
                        lineHeight = 21.sp,
                    )
                }
            }
        }
        if (slide.code.isNotEmpty()) {
            HomeCodeArt(slide, Modifier.align(Alignment.BottomCenter).padding(bottom = 29.dp))
        } else {
            HomeCapabilityArt(slide, Modifier.align(Alignment.BottomCenter).padding(bottom = 29.dp))
        }
        Text(
            "0${index + 1} / 0${HomeSlides.size}",
            color = HomeInk.copy(alpha = .58f),
            fontSize = 9.sp,
            fontFamily = FontFamily.Monospace,
            letterSpacing = 1.2.sp,
            modifier = Modifier.align(Alignment.BottomEnd),
        )
    }
}

/** `.slide { box-shadow: inset 0 2px 0 var(--accent) }`：顶部 2dp 强调线。 */
private fun Modifier.drawSlideTopAccent(accent: Color): Modifier = drawBehind {
    drawRect(accent, size = Size(size.width, 2.dp.toPx()))
}

@Composable
private fun HomeEyebrow(text: String, accent: Color) {
    Row(
        modifier = Modifier
            .background(Color.White.copy(alpha = .68f), CircleShape)
            .border(1.dp, accent.copy(alpha = .26f), CircleShape)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        Box(Modifier.size(5.dp).background(accent, CircleShape))
        Text(text, color = HomeInk, fontSize = 10.5.sp, fontWeight = FontWeight.Bold, letterSpacing = .3.sp)
    }
}

@Composable
private fun HomeGlassPanel(accent: Color, modifier: Modifier, content: @Composable () -> Unit) {
    Box(
        modifier
            .fillMaxWidth()
            .background(Color.White.copy(alpha = .43f), RoundedCornerShape(15.dp))
            .border(1.dp, accent.copy(alpha = .22f), RoundedCornerShape(15.dp)),
    ) { content() }
}

@Composable
private fun HomeCapabilityArt(slide: HomeSlide, modifier: Modifier) {
    HomeGlassPanel(slide.accent, modifier) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 68.dp).padding(horizontal = 12.dp, vertical = 11.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Box(
                Modifier.size(27.dp)
                    .background(slide.accent.copy(alpha = .16f), RoundedCornerShape(9.dp))
                    .border(1.dp, slide.accent.copy(alpha = .24f), RoundedCornerShape(9.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Text(slide.mark, color = slide.accent, fontSize = 8.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
            }
            slide.capabilities.forEach { capability ->
                Column(
                    Modifier.weight(1f)
                        .drawLeftRule(slide.accent.copy(alpha = .24f))
                        .padding(start = 9.dp),
                ) {
                    Text(capability.title, color = HomeInk, fontSize = 10.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(capability.detail, color = HomeMuted, fontSize = 8.4.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp))
                }
            }
        }
    }
}

/** `.capability-item { border-left: 1px solid var(--accent-line) }`。 */
private fun Modifier.drawLeftRule(color: Color): Modifier = drawBehind {
    drawLine(color, Offset.Zero, Offset(0f, size.height), 1.dp.toPx())
}

@Composable
private fun HomeCodeArt(slide: HomeSlide, modifier: Modifier) {
    HomeGlassPanel(slide.accent, modifier) {
        Column(Modifier.padding(horizontal = 15.dp, vertical = 12.dp)) {
            slide.code.forEach { line ->
                val keyword = line.trim().substringBefore(' ')
                Row {
                    if (keyword == "if" || keyword == "end") {
                        Text(line.substringBefore(keyword), color = HomeMuted, fontSize = 9.5.sp, fontFamily = FontFamily.Monospace, lineHeight = 17.sp)
                        Text(keyword, color = slide.accent, fontSize = 9.5.sp, fontFamily = FontFamily.Monospace, lineHeight = 17.sp)
                        Text(line.substringAfter(keyword), color = HomeInk, fontSize = 9.5.sp, fontFamily = FontFamily.Monospace, lineHeight = 17.sp, fontWeight = FontWeight.SemiBold)
                    } else {
                        Text(line, color = HomeMuted, fontSize = 9.5.sp, fontFamily = FontFamily.Monospace, lineHeight = 17.sp)
                    }
                }
            }
        }
    }
}

private data class HomeValue(val mark: String, val title: String, val detail: String)

private val HomeValues = listOf(
    HomeValue("易", "简单易用", "可视化操作更直观"),
    HomeValue("Lua", "Lua 引擎", "脚本能力灵活扩展"),
    HomeValue("界", "界面开发", "控件布局即改即看"),
    HomeValue("Root", "本机运行", "Root 后端真实执行"),
)

@Composable
private fun HomeValuePanel(modifier: Modifier) {
    val panelBackground = remember {
        Brush.linearGradient(
            0f to Color(0xFFF3F7FF).copy(alpha = .98f),
            1f to Color(0xFFE8F0FF).copy(alpha = .96f),
            start = Offset.Zero,
            end = Offset.Infinite,
        )
    }
    Column(
        modifier
            .background(panelBackground, RoundedCornerShape(22.dp))
            .border(1.dp, Color(0xFFD7E3F7), RoundedCornerShape(22.dp))
            .padding(start = 14.dp, top = 14.dp, end = 14.dp, bottom = 12.dp),
    ) {
        // 价值面板标题：14.5px / weight 830 / letter-spacing -.025em。
        Text(
            "零基础快速上手 开发更高效",
            color = HomeInk,
            fontSize = 14.5.sp,
            fontWeight = FontWeight.ExtraBold,
            letterSpacing = (-0.025).em,
            modifier = Modifier.padding(start = 2.dp, bottom = 9.dp),
        )
        HomeValues.chunked(2).forEachIndexed { rowIndex, row ->
            Row(
                Modifier.fillMaxWidth().padding(top = if (rowIndex == 0) 0.dp else 7.dp),
                horizontalArrangement = Arrangement.spacedBy(7.dp),
            ) {
                row.forEach { value -> HomeValueTile(value, Modifier.weight(1f)) }
            }
        }
    }
}

@Composable
private fun HomeValueTile(value: HomeValue, modifier: Modifier) {
    Row(
        modifier
            .heightIn(min = 49.dp)
            .background(Color.White.copy(alpha = .72f), RoundedCornerShape(12.dp))
            .border(1.dp, Color(0xFFCFDDF5), RoundedCornerShape(12.dp))
            .padding(horizontal = 8.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(27.dp)
                .background(AutoScriptPalette.AccentSoft, RoundedCornerShape(9.dp))
                .border(1.dp, Color(0xFFC7D8FF), RoundedCornerShape(9.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Text(value.mark, color = AutoScriptPalette.Accent, fontSize = 10.sp, fontWeight = FontWeight.ExtraBold)
        }
        Column(Modifier.padding(start = 6.dp)) {
            Text(value.title, color = HomeInk, fontSize = 11.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(value.detail, color = HomeMuted, fontSize = 9.2.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 3.dp))
        }
    }
}

/**
 * 在某个页面存在期间临时改变状态栏底色与图标明暗，离开时精确还原到进入前的值。
 *
 * 用它而不是 edge-to-edge：`setDecorFitsSystemWindows(false)` 会让所有页面自己处理 inset，
 * 对已经逐页对齐过尺寸的 20 个页面是高风险改动，而这里要的只是“状态栏别是一条突兀的浅色带”。
 */
@Composable
private fun SystemBarScope(color: Color, darkIcons: Boolean) {
    val view = LocalView.current
    if (view.isInEditMode) return
    val activity = LocalContext.current as? Activity ?: return
    DisposableEffect(view, color, darkIcons) {
        val window = activity.window
        val controller = WindowInsetsControllerCompat(window, view)
        val previousColor = window.statusBarColor
        val previousDarkIcons = controller.isAppearanceLightStatusBars
        window.statusBarColor = color.toArgb()
        controller.isAppearanceLightStatusBars = darkIcons
        onDispose {
            window.statusBarColor = previousColor
            WindowInsetsControllerCompat(window, view).isAppearanceLightStatusBars = previousDarkIcons
        }
    }
}

@Composable
private fun rememberRuntimeClient(onState: (RuntimeConnectionState) -> Unit): RuntimeClient {
    val context = androidx.compose.ui.platform.LocalContext.current
    val client = remember { RuntimeClient(context) }
    DisposableEffect(client) {
        client.onStateChanged = onState
        client.bind()
        onDispose {
            client.onStateChanged = null
            client.unbind()
        }
    }
    return client
}

// ---------------------------------------------------------------------------------------------
// 我的：fragment_profile.xml + ProfileRainHeaderView（近黑底 + 右上光晕 + 雨丝）。
// ---------------------------------------------------------------------------------------------

@Composable
private fun ProfilePage(
    onOpenRuntimeEnvironment: () -> Unit,
    onOpenSubpage: (ProfileSubpage) -> Unit,
    modifier: Modifier = Modifier,
) {
    // 参考的“我的”页把雨丝头部一直画到屏幕最上沿，状态栏是深色底 + 白色图标。
    // 这里不动全局的 decorFitsSystemWindows（那会改变每个页面的 inset，风险远大于收益），
    // 只把状态栏染成头部同色并切成白色图标，视觉上等价；离开本页时恢复浅色。
    SystemBarScope(AutoScriptPalette.ProfileHeader, darkIcons = false)
    LazyColumn(modifier = modifier.background(AutoScriptPalette.PageBackground), contentPadding = PaddingValues(bottom = 24.dp)) {
        item {
            Box(Modifier.fillMaxWidth().height(250.dp)) {
                ProfileRainHeader(Modifier.fillMaxSize())
                Row(
                    modifier = Modifier.fillMaxSize().padding(start = 24.dp, end = 20.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        Modifier.size(76.dp).border(3.dp, Color.White.copy(alpha = .35f), CircleShape).padding(3.dp)
                            .background(Color.White.copy(alpha = .96f), CircleShape)
                            .clickable { onOpenSubpage(ProfileSubpage.LOGIN) },
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(painterResource(R.drawable.ic_person_48), contentDescription = "账号", tint = Color.Unspecified, modifier = Modifier.size(48.dp))
                    }
                    Column(modifier = Modifier.weight(1f).padding(start = 16.dp).clickable { onOpenSubpage(ProfileSubpage.LOGIN) }) {
                        Text("登录 / 注册", color = Color.White, fontSize = 21.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text("登录后可创建项目并管理个人资料", color = Color.White.copy(alpha = .87f), fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 6.dp))
                    }
                }
            }
        }
        item {
            Text(
                "功能服务",
                color = AutoScriptPalette.TextPrimary,
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(start = 20.dp, top = 20.dp, end = 20.dp),
            )
        }
        item {
            Surface(
                modifier = Modifier.padding(start = 16.dp, top = 10.dp, end = 16.dp).fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                color = Color.White,
                // fragment_profile 的卡描边是 strokeWidth="1.0px"，不是 1dp。
                border = BorderStroke(hairline(), AutoScriptPalette.Border),
                shadowElevation = 1.dp,
            ) {
                Column {
                    ProfileFeatureRow(R.drawable.ic_memory_24, "运行环境", null, onOpenRuntimeEnvironment)
                    ProfileDivider()
                    ProfileFeatureRow(R.drawable.ic_group_24, "社区与反馈", "本地版暂未配置外部群组", {})
                    ProfileDivider()
                    ProfileFeatureRow(R.drawable.ic_description_24, "开发文档", "离线文档与接口说明", { onOpenSubpage(ProfileSubpage.DOCS) })
                    ProfileDivider()
                    ProfileFeatureRow(R.drawable.ic_download_24, "检查更新", "当前版本 0.1.0", { onOpenSubpage(ProfileSubpage.UPDATE) })
                }
            }
        }
        item {
            Row(
                modifier = Modifier.fillMaxWidth().height(52.dp).padding(top = 14.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ProfileLegalLink("用户协议") { onOpenSubpage(ProfileSubpage.LEGAL) }
                Text("·", color = AutoScriptPalette.TextSecondary, fontSize = 12.sp)
                ProfileLegalLink("隐私政策") { onOpenSubpage(ProfileSubpage.LEGAL) }
            }
        }
    }
}

@Composable
private fun ProfileLegalLink(label: String, onClick: () -> Unit) {
    Text(
        label,
        color = AutoScriptPalette.TextSecondary,
        fontSize = 12.sp,
        modifier = Modifier.height(40.dp).clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 11.dp),
    )
}

private class RainStreak(val x: Float, val y: Float, val length: Float, val width: Float, val speed: Float, val alpha: Float)

/**
 * `ProfileRainHeaderView`：底色 rgb(3,6,10)，右上角径向光晕，三档粗细的雨丝自上而下飘落。
 * 雨丝用固定种子生成，位置随一个 6 秒循环缓慢推进。
 */
@Composable
private fun ProfileRainHeader(modifier: Modifier) {
    val streaks = remember {
        val random = Random(731_927)
        List(46) {
            val roll = random.nextFloat()
            val (length, width, alpha) = when {
                roll < .54f -> Triple(.025f + random.nextFloat() * .027f, .26f + random.nextFloat() * .2f, .10f + random.nextFloat() * .10f)
                roll < .86f -> Triple(.055f + random.nextFloat() * .04f, .52f + random.nextFloat() * .3f, .22f + random.nextFloat() * .15f)
                else -> Triple(.10f + random.nextFloat() * .06f, .9f + random.nextFloat() * .45f, .37f + random.nextFloat() * .2f)
            }
            RainStreak(
                x = -.08f + random.nextFloat() * 1.1f,
                y = random.nextFloat(),
                length = length,
                width = width,
                speed = .48f + random.nextFloat() * .7f,
                alpha = alpha,
            )
        }
    }
    val transition = rememberInfiniteTransition(label = "profile-rain")
    val progress by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(6_000, easing = LinearEasing), RepeatMode.Restart),
        label = "profile-rain-progress",
    )
    Canvas(modifier) {
        drawRect(AutoScriptPalette.ProfileHeader)
        drawCircle(
            brush = Brush.radialGradient(
                0f to Color(0xFF1C2E4A).copy(alpha = .85f),
                1f to Color.Transparent,
                center = Offset(size.width * .94f, size.height * .42f),
                radius = size.width * .56f,
            ),
            radius = size.width * .56f,
            center = Offset(size.width * .94f, size.height * .42f),
        )
        streaks.forEach { streak ->
            val travel = (streak.y + progress * streak.speed) % 1.28f - .14f
            val top = travel * size.height
            val x = (streak.x + travel * .09f) * size.width
            drawLine(
                color = Color.White.copy(alpha = streak.alpha),
                start = Offset(x, top),
                end = Offset(x + streak.length * size.height * .09f, top + streak.length * size.height),
                strokeWidth = streak.width.dp.toPx(),
                cap = StrokeCap.Round,
            )
        }
    }
}

@Composable
private fun ProfileFeatureRow(icon: Int, title: String, subtitle: String?, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().height(72.dp).clickable(onClick = onClick).padding(start = 16.dp, end = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(42.dp).background(AutoScriptPalette.AccentSoft, RoundedCornerShape(14.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(painterResource(icon), contentDescription = null, tint = AutoScriptPalette.Accent, modifier = Modifier.size(24.dp))
        }
        Column(Modifier.weight(1f).padding(start = 14.dp)) {
            Text(title, color = AutoScriptPalette.TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.Bold)
            subtitle?.let {
                Text(it, color = AutoScriptPalette.TextSecondary, fontSize = 11.sp, modifier = Modifier.padding(top = 4.dp))
            }
        }
        Icon(painterResource(R.drawable.ic_chevron_right_20), contentDescription = "进入", tint = AutoScriptPalette.TextSecondary, modifier = Modifier.size(20.dp))
    }
}

@Composable
private fun ProfileDivider() =
    Box(Modifier.fillMaxWidth().padding(start = 72.dp).height(hairline()).background(AutoScriptPalette.Border))
