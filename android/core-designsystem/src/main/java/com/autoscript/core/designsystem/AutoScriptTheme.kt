package com.autoscript.core.designsystem

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val AutoScriptColors = lightColorScheme(
    primary = Color(0xFF3D6EF7),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE8EEFF),
    onPrimaryContainer = Color(0xFF244FCE),
    secondary = Color(0xFF52627A),
    secondaryContainer = Color(0xFFEDF1F8),
    onSecondaryContainer = Color(0xFF36445A),
    background = Color(0xFFF5F7FB),
    onBackground = Color(0xFF172033),
    surface = Color.White,
    onSurface = Color(0xFF172033),
    surfaceVariant = Color(0xFFF0F3F8),
    onSurfaceVariant = Color(0xFF74819A),
    surfaceDim = Color(0xFFEFF3F8),
    surfaceBright = Color.White,
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color.White,
    surfaceContainer = Color(0xFFF8FAFD),
    surfaceContainerHigh = Color(0xFFF3F6FA),
    surfaceContainerHighest = Color(0xFFEEF2F7),
    outline = Color(0xFFDCE2EC),
    outlineVariant = Color(0xFFE7EBF2),
    error = Color(0xFFE84855),
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
