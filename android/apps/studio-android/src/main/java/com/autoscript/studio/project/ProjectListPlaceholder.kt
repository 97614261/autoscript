package com.autoscript.studio

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.autoscript.core.designsystem.AutoScriptPalette

/** Empty and filtered lists share an intrinsic-height layout, including at large font scales. */
@Composable
internal fun ProjectListPlaceholder(filtered: Boolean, enabled: Boolean, onAction: () -> Unit, modifier: Modifier = Modifier) {
    Surface(modifier.fillMaxWidth(), color = Color.White, shape = RoundedCornerShape(14.dp)) {
        Column(Modifier.padding(horizontal = 18.dp, vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Icon(painterResource(R.drawable.editor_folder_24), contentDescription = null,
                modifier = Modifier.size(24.dp), tint = AutoScriptPalette.Accent)
            Text(if (filtered) "没有匹配项目" else "还没有项目", fontSize = 14.sp,
                fontWeight = FontWeight.Bold, color = AutoScriptPalette.TextPrimary, textAlign = TextAlign.Center)
            Text(if (filtered) "换个名称试试，或清空搜索" else "新建项目，开始编排自动化流程", fontSize = 11.sp,
                color = AutoScriptPalette.TextSecondary, textAlign = TextAlign.Center)
            Surface(onClick = onAction, enabled = enabled, shape = RoundedCornerShape(9.dp),
                color = if (enabled) AutoScriptPalette.Accent else Color(0xFFE5E7EF),
                contentColor = if (enabled) Color.White else AutoScriptPalette.TextSecondary) {
                Box(Modifier.heightIn(min = 28.dp).padding(horizontal = 14.dp, vertical = 6.dp), contentAlignment = Alignment.Center) {
                    Text(if (filtered) "清空搜索" else "新建项目", fontSize = 11.sp, fontWeight = FontWeight.Medium, textAlign = TextAlign.Center)
                }
            }
        }
    }
}
