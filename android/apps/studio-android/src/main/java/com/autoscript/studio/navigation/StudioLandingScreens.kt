package com.autoscript.studio

import androidx.annotation.DrawableRes
import androidx.compose.foundation.Image
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
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.autoscript.core.designsystem.AutoScriptPalette
import com.autoscript.core.model.RuntimeConnectionState
import com.autoscript.brand.ui.R as BrandR

private val LandingBackground = Color(0xFFF6F5FA)
private val ProfileAccent = Color(0xFF794BE5)

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
    LazyColumn(modifier.fillMaxSize().background(LandingBackground),
        contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            Row(Modifier.fillMaxWidth().padding(horizontal = 2.dp, vertical = 1.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("我的", fontSize = 19.sp, fontWeight = FontWeight.Bold, color = AutoScriptPalette.TextPrimary)
            }
        }
        item {
            Surface(shape = RoundedCornerShape(12.dp), color = Color.White) {
                Row(Modifier.fillMaxWidth().background(Brush.horizontalGradient(listOf(Color(0xFFF1EAFE), Color(0xFFFFF8FD), Color.White)))
                    .padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(38.dp).background(Color.White, RoundedCornerShape(11.dp)), contentAlignment = Alignment.Center) {
                        Image(painterResource(BrandR.drawable.zhigou_brand_mark), "智构 Logo", Modifier.size(34.dp))
                    }
                    Column(Modifier.weight(1f).padding(start = 10.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text("智构", color = AutoScriptPalette.TextPrimary, fontSize = 16.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold)
                        Text("本地自动化工作空间", color = AutoScriptPalette.TextSecondary, fontSize = 10.sp, lineHeight = 14.sp)
                    }
                    Column(Modifier.widthIn(max = 96.dp), horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(version, color = ProfileAccent, fontSize = 10.sp, lineHeight = 14.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text("本地版", color = AutoScriptPalette.TextSecondary, fontSize = 9.sp, lineHeight = 12.sp)
                    }
                }
            }
        }
        studioProfileSections.forEach { section ->
            item { LandingSectionTitle(section.title) }
            item { LandingEntryGroup(section.entries, labels, onEntry) }
        }
        item {
            Text("数据仅保存在本机 · 卸载前请导出备份", color = AutoScriptPalette.TextSecondary, fontSize = 10.sp,
                lineHeight = 14.sp, textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp))
        }
    }
}


@Composable
private fun LandingEntryGroup(entries: List<StudioEntry>, labels: StudioRuntimeLabels, onEntry: (StudioEntry) -> Unit) {
    val secondaryVisible = LocalDensity.current.fontScale <= 1.3f
    Surface(shape = RoundedCornerShape(12.dp), color = Color.White) {
        Column {
            entries.forEachIndexed { index, entry ->
                val subtitle = when (entry) {
                    StudioEntry.PERMISSIONS -> "悬浮窗 · 通知 · 电池与系统授权"
                    StudioEntry.ENVIRONMENT -> "${labels.runner} · Root ${labels.root} · 脚本 ${labels.execution}"
                    else -> null
                }
                val note = when (entry) {
                    StudioEntry.BACKUPS -> "本地槽位"
                    StudioEntry.DOCS -> "接口与积木"
                    StudioEntry.ABOUT -> "版本与隐私"
                    else -> null
                }
                Row(Modifier.fillMaxWidth().heightIn(min = if (subtitle == null) 38.dp else 46.dp)
                    .semantics { contentDescription = entry.title }
                    .clickable { onEntry(entry) }.padding(horizontal = 10.dp, vertical = 7.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    LandingIcon(entry.icon())
                    Column(Modifier.weight(1f).padding(horizontal = 9.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(entry.title, color = AutoScriptPalette.TextPrimary, fontSize = 12.sp, lineHeight = 16.sp, fontWeight = FontWeight.Medium)
                        if (subtitle != null) Text(subtitle, fontSize = 10.sp, lineHeight = 14.sp, color = AutoScriptPalette.TextSecondary)
                    }
                    if (note != null && secondaryVisible) Text(note, fontSize = 10.sp, color = AutoScriptPalette.TextSecondary,
                        modifier = Modifier.padding(end = 6.dp))
                    Icon(painterResource(R.drawable.ic_chevron_right_20), null, tint = AutoScriptPalette.TextSecondary, modifier = Modifier.size(14.dp))
                }
                if (index != entries.lastIndex) HorizontalDivider(Modifier.padding(start = 45.dp), color = Color(0xFFF1EFF6))
            }
        }
    }
}

@Composable
private fun LandingSectionTitle(title: String) {
    Text(title, fontSize = 11.sp, lineHeight = 15.sp, fontWeight = FontWeight.Medium, color = AutoScriptPalette.TextSecondary,
        modifier = Modifier.fillMaxWidth().padding(start = 2.dp, top = 2.dp))
}

@Composable
private fun LandingIcon(@DrawableRes icon: Int) {
    Box(Modifier.size(26.dp).background(ProfileAccent.copy(alpha = .07f), RoundedCornerShape(8.dp)), contentAlignment = Alignment.Center) {
        Icon(painterResource(icon), null, tint = ProfileAccent, modifier = Modifier.size(15.dp))
    }
}


@DrawableRes private fun StudioEntry.icon(): Int = when (this) {
    StudioEntry.PROJECTS, StudioEntry.CREATE -> R.drawable.nav_workspace_24
    StudioEntry.IMPORT -> R.drawable.ic_download_24
    StudioEntry.BACKUPS -> R.drawable.ic_backup_outline_24
    StudioEntry.PERMISSIONS -> R.drawable.editor_settings_24
    StudioEntry.ENVIRONMENT -> R.drawable.ic_memory_24
    StudioEntry.DOCS -> R.drawable.ic_code_24
    StudioEntry.GUIDE -> R.drawable.ic_book_outline_24
    StudioEntry.ABOUT -> R.drawable.ic_help_outline_24
}
