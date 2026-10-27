package com.autoscript.studio

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.autoscript.core.designsystem.AutoScriptPalette

@Composable
internal fun StudioAboutScreen(onBack: () -> Unit, modifier: Modifier = Modifier) {
    BackHandler(onBack = onBack)
    val context = LocalContext.current
    val version = remember(context) {
        runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }
            .getOrNull() ?: "版本未知"
    }
    Column(modifier.fillMaxSize().background(StudioPaper)) {
        Row(Modifier.fillMaxWidth().heightIn(min = 52.dp).background(Color.White).padding(end = 14.dp),
            verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack, modifier = Modifier.semantics { contentDescription = "返回我的" }) {
                Text("‹", fontSize = 25.sp, color = AutoScriptPalette.Accent)
            }
            Text("关于智构", fontSize = 17.sp, color = AutoScriptPalette.TextPrimary)
        }
        HorizontalDivider(color = AutoScriptPalette.DividerSoft)
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), contentPadding = PaddingValues(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item {
                Surface(shape = RoundedCornerShape(12.dp), color = Color.White) {
                    Row(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Text("当前版本", fontSize = 13.sp, color = StudioInk, modifier = Modifier.weight(1f))
                        Text("$version (${BuildConfig.VERSION_CODE})", fontSize = 13.sp, color = StudioViolet)
                    }
                }
            }
            item {
                Surface(shape = RoundedCornerShape(12.dp), color = Color.White) { StudioUpdateCard() }
            }
        }
    }
}
