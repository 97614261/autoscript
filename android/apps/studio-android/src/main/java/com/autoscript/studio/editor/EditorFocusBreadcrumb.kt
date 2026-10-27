package com.autoscript.studio

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
internal fun EditorFocusBreadcrumb(path: List<EditorFocusCrumb>, onFocus: (String?) -> Unit) {
    if (path.isEmpty()) return
    Row(Modifier.fillMaxWidth().background(Color(0xFFF4F7FF)).padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Text("全部", fontSize = 10.sp, color = EditorBlue,
            modifier = Modifier.heightIn(min = 24.dp).clickable(onClickLabel = "返回完整积木树") { onFocus(null) }
                .padding(horizontal = 5.dp, vertical = 5.dp))
        Row(Modifier.weight(1f).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(3.dp),
            verticalAlignment = Alignment.CenterVertically) {
            path.forEachIndexed { index, crumb ->
                Text("›", fontSize = 10.sp, color = Color(0xFF8390A5))
                Text(crumb.label, fontSize = 10.sp, color = if (index == path.lastIndex) Color(0xFF536078) else EditorBlue,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.widthIn(max = 100.dp).heightIn(min = 24.dp)
                        .semantics { contentDescription = "聚焦路径：${crumb.label}" }
                        .clickable(enabled = index != path.lastIndex) { onFocus(crumb.id) }.padding(horizontal = 3.dp, vertical = 5.dp))
            }
        }
    }
}
