package com.autoscript.studio

import androidx.annotation.DrawableRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.autoscript.core.model.RuntimeConnectionState
import com.autoscript.brand.ui.R as BrandR

private val ProfileAccent = StudioViolet

@Composable
internal fun StudioHomePage(
    modifier: Modifier = Modifier,
    spaceState: HomeSpaceState = rememberHomeSpaceState(),
) {
    StudioLaunchCanvas(modifier, spaceState)
}

@Composable
internal fun StudioProfilePage(runtime: RuntimeConnectionState, onEntry: (StudioEntry) -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val version = remember(context) { runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull() ?: "版本未知" }
    val labels = studioRuntimeLabels(runtime)
    StudioPageBackdrop(modifier.fillMaxSize()) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
    val compact = maxHeight < 560.dp
    val columns = if (studioWideShortLayout(maxWidth.value, maxHeight.value, LocalDensity.current.fontScale, 520)) 2 else 1
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(horizontal = 13.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(if (compact) 7.dp else 10.dp)) {
        item {
            Surface(shape = RoundedCornerShape(13.dp), color = Color.White,
                border = BorderStroke(1.dp, Color.White), shadowElevation = 1.dp) {
              Box(Modifier.clip(RoundedCornerShape(13.dp)).background(Brush.horizontalGradient(
                  listOf(Color(0xFFFBF9FF), Color(0xFFF0E9FF))))) {
                Canvas(Modifier.matchParentSize()) {
                    drawOval(Color(0xFFBFA0FF).copy(alpha = .1f), Offset(size.width * .45f, size.height * .55f),
                        androidx.compose.ui.geometry.Size(size.width * .68f, size.height * 1.3f))
                    drawOval(Color.White.copy(alpha = .35f), Offset(size.width * .55f, -size.height * .6f),
                        androidx.compose.ui.geometry.Size(size.width * .68f, size.height * 1.6f))
                }
                Row(Modifier.fillMaxWidth().padding(if (compact) 10.dp else 11.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(44.dp).background(Color.White, RoundedCornerShape(12.dp)), contentAlignment = Alignment.Center) {
                        Image(painterResource(BrandR.drawable.zhigou_brand_mark), "智构 Logo", Modifier.size(40.dp))
                    }
                    Column(Modifier.weight(1f).padding(horizontal = 9.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text("智构", color = StudioInk, fontSize = 18.sp, lineHeight = 23.sp, fontWeight = FontWeight.Bold)
                        Text("本地自动化工作空间", color = StudioMuted, fontSize = 11.sp, lineHeight = 15.sp)
                    }
                    Column(Modifier.widthIn(max = 80.dp).background(Color.White.copy(alpha = .6f), RoundedCornerShape(10.dp))
                        .padding(horizontal = 9.dp, vertical = 6.dp), horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(version, color = ProfileAccent, fontSize = 12.sp, lineHeight = 16.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text("本地版", color = StudioMuted, fontSize = 10.sp, lineHeight = 13.sp)
                    }
                }
              }
            }
        }
        studioProfileSections.chunked(columns).forEach { sections ->
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    sections.forEach { section ->
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            LandingSectionTitle(section.title)
                            LandingEntryGroup(section.entries, labels, onEntry, compact)
                        }
                    }
                    if (sections.size < columns) Spacer(Modifier.weight(1f))
                }
            }
        }
        item {
            Text("数据仅保存在本机 · 卸载前请导出备份", color = StudioMuted, fontSize = 10.sp,
                lineHeight = 14.sp, textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp, bottom = if (compact) 8.dp else 12.dp))
        }
    }
    }
    }
}


@Composable
private fun LandingEntryGroup(entries: List<StudioEntry>, labels: StudioRuntimeLabels, onEntry: (StudioEntry) -> Unit,
    compact: Boolean = false) {
    val secondaryVisible = LocalDensity.current.fontScale <= 1.3f
    Surface(shape = RoundedCornerShape(12.dp), color = Color.White.copy(alpha = .95f)) {
        Column {
            entries.forEachIndexed { index, entry ->
                val subtitle = when (entry) {
                    StudioEntry.PERMISSIONS -> "悬浮窗 · 通知 · 电池与系统授权"
                    StudioEntry.ENVIRONMENT -> "${labels.runner} · Root ${labels.root} · 脚本 ${labels.execution}"
                    else -> null
                }
                val note = when (entry) {
                    StudioEntry.BACKUPS -> "本地备份"
                    StudioEntry.DOCS -> "接口与积木"
                    StudioEntry.ABOUT -> "版本与更新"
                    else -> null
                }
                Row(Modifier.fillMaxWidth().heightIn(min = if (subtitle == null) {
                    if (compact) 40.dp else 42.dp
                } else if (compact) 44.dp else 49.dp)
                    .semantics { contentDescription = entry.title }
                    .clickable { onEntry(entry) }.padding(horizontal = 12.dp, vertical = if (compact) 5.dp else 7.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    LandingIcon(entry.icon())
                    Column(Modifier.weight(1f).padding(horizontal = 9.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(entry.title, color = StudioInk, fontSize = 13.sp, lineHeight = 18.sp, fontWeight = FontWeight.Medium)
                        if (subtitle != null) Text(subtitle, fontSize = 10.sp, lineHeight = 14.sp, color = StudioMuted)
                    }
                    if (note != null && secondaryVisible) Text(note, fontSize = 10.sp, color = StudioMuted,
                        modifier = Modifier.padding(end = 6.dp))
                    Icon(painterResource(R.drawable.ic_chevron_right_20), null, tint = StudioMuted, modifier = Modifier.size(15.dp))
                }
                if (index != entries.lastIndex) HorizontalDivider(Modifier.padding(start = 57.dp), color = Color(0xFFF0EEF7))
            }
        }
    }
}

@Composable
private fun LandingSectionTitle(title: String) {
    Row(Modifier.fillMaxWidth().padding(start = 2.dp, top = 1.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(width = 3.dp, height = 14.dp).background(StudioBrandGradient, RoundedCornerShape(2.dp)))
        Text(title, fontSize = 12.sp, lineHeight = 16.sp, fontWeight = FontWeight.SemiBold, color = StudioMuted,
            modifier = Modifier.padding(start = 7.dp))
    }
}

@Composable
private fun LandingIcon(@DrawableRes icon: Int) {
    Box(Modifier.size(32.dp).background(StudioLilac.copy(alpha = .7f), RoundedCornerShape(9.dp)), contentAlignment = Alignment.Center) {
        Icon(painterResource(icon), null, tint = ProfileAccent, modifier = Modifier.size(19.dp))
    }
}


@DrawableRes private fun StudioEntry.icon(): Int = when (this) {
    StudioEntry.PROJECTS, StudioEntry.CREATE -> R.drawable.nav_workspace_24
    StudioEntry.IMPORT -> R.drawable.ic_download_24
    StudioEntry.BACKUPS -> R.drawable.ic_backup_outline_24
    StudioEntry.PERMISSIONS -> R.drawable.editor_settings_24
    StudioEntry.ENVIRONMENT -> R.drawable.ic_memory_24
    StudioEntry.DOCS -> R.drawable.ic_code_24
    StudioEntry.ABOUT -> R.drawable.ic_help_outline_24
}
