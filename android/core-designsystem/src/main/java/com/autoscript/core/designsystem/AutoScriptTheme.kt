package com.autoscript.core.designsystem

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 参考产品 `res/values/colors.xml` 里的命名色，页面直接引用这里而不是散落的十六进制。
 * 名称沿用参考的语义（`app_*` / `profile_*` / `visual_editor_*`），值一字不改。
 */
object AutoScriptPalette {
    val Accent = Color(0xFF3A6EFF)
    val AccentDark = Color(0xFF2446C7)
    val AccentSoft = Color(0xFFEAF0FF)
    val Border = Color(0xFFEBEFF5)
    val PageBackground = Color(0xFFF5F7FB)
    val Card = Color.White
    val Mint = Color(0xFF22A06B)
    val Purple = Color(0xFF735BFF)
    val TextPrimary = Color(0xFF182033)
    val TextSecondary = Color(0xFF7A8499)
    val Danger = Color(0xFFE5484D)
    val Warning = Color(0xFFF59E0B)

    /** 悬浮面板弹窗的标题蓝与文字按钮蓝（`#3d5afe` / `#3f51b5`）。 */
    val DialogTitle = Color(0xFF3D5AFE)
    val DialogAction = Color(0xFF3F51B5)

    /** 弹窗分割线 `hs`（`#e1d5d5d5`，带 88% alpha）、三列底 `hs2` 与弱文字 `hui2`。 */
    val Divider = Color(0xE1D5D5D5)
    val DividerSoft = Color(0xFFF5F5F5)
    val Muted = Color(0xFF777777)

    /** “我的”页头部底色（`ProfileRainHeaderView`）。 */
    val ProfileHeader = Color(0xFF03060A)

    object VisualEditor {
        val Accent = Color(0xFF3A6EFF)
        val AccentSoft = Color(0xFFDCE7FF)
        val Border = Color(0xFFD6DEE9)
        val ControlBackground = Color(0xFFF7F9FC)
        val PageBackground = Color(0xFFEEF2F7)
        val PanelBackground = Color(0xFFF9FBFD)
        val SideBackground = Color(0xFFE5EBF3)
        val TextPrimary = Color(0xFF172033)
        val TextSecondary = Color(0xFF738096)
        val ToolbarBackground = Color(0xFFF3F6FB)
        val ToolbarIcon = Color(0xFF46618A)
        val ToolbarPressed = Color(0xFFE2EAF5)
        val Unsaved = Color(0xFFFF4D5E)
    }
}

/**
 * 参考布局里写成 `1.0px` 的发线。
 *
 * 参考自己是混用的：源文件管理、编辑窗口、函数库、我的、程序树行、创建项目弹窗用 `1px`，
 * 而文件弹窗、项目卡、Lua 编辑窗、运行环境卡描边用 `1dp`。前者必须按密度换算，
 * 否则在 MuMu（density 2）粗一倍、真机（density 3）粗三倍，整体就不如参考紧凑。
 */
@Composable
fun hairline(): Dp = with(LocalDensity.current) { 1.toDp() }

private val AutoScriptColors = lightColorScheme(
    primary = AutoScriptPalette.Accent,
    onPrimary = Color.White,
    primaryContainer = AutoScriptPalette.AccentSoft,
    onPrimaryContainer = AutoScriptPalette.AccentDark,
    secondary = Color(0xFF52627A),
    secondaryContainer = Color(0xFFEDF1F8),
    onSecondaryContainer = Color(0xFF36445A),
    background = AutoScriptPalette.PageBackground,
    onBackground = AutoScriptPalette.TextPrimary,
    surface = Color.White,
    onSurface = AutoScriptPalette.TextPrimary,
    surfaceVariant = Color(0xFFF0F3F8),
    onSurfaceVariant = AutoScriptPalette.TextSecondary,
    surfaceDim = Color(0xFFEFF3F8),
    surfaceBright = Color.White,
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color.White,
    surfaceContainer = Color(0xFFF8FAFD),
    surfaceContainerHigh = Color(0xFFF3F6FA),
    surfaceContainerHighest = Color(0xFFEEF2F7),
    outline = AutoScriptPalette.Border,
    outlineVariant = Color(0xFFE7EBF2),
    error = AutoScriptPalette.Danger,
)

private val CompactTypography = Typography(
    headlineMedium = TextStyle(fontSize = 24.sp, lineHeight = 30.sp, fontWeight = FontWeight.Bold),
    headlineSmall = TextStyle(fontSize = 20.sp, lineHeight = 26.sp, fontWeight = FontWeight.Bold),
    titleLarge = TextStyle(fontSize = 19.sp, lineHeight = 24.sp, fontWeight = FontWeight.SemiBold),
    titleMedium = TextStyle(fontSize = 15.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold),
    titleSmall = TextStyle(fontSize = 14.sp, lineHeight = 19.sp, fontWeight = FontWeight.SemiBold),
    bodyMedium = TextStyle(fontSize = 13.sp, lineHeight = 18.sp),
    bodySmall = TextStyle(fontSize = 12.sp, lineHeight = 17.sp),
    labelLarge = TextStyle(fontSize = 13.sp, lineHeight = 18.sp, fontWeight = FontWeight.Medium),
    labelMedium = TextStyle(fontSize = 12.sp, lineHeight = 16.sp, fontWeight = FontWeight.Medium),
    labelSmall = TextStyle(fontSize = 11.sp, lineHeight = 15.sp, fontWeight = FontWeight.Medium),
)

object AutoScriptDimens {
    val PageHorizontal = 12.dp
    val PageVertical = 10.dp
    val SectionGap = 10.dp
    val CardPadding = 12.dp
    val CardRadius = 16.dp
    val ControlRadius = 12.dp
    val CompactControlHeight = 42.dp
    val PrimaryControlHeight = 46.dp
}

@Composable
fun AutoScriptTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = AutoScriptColors,
        typography = CompactTypography,
        content = content,
    )
}
