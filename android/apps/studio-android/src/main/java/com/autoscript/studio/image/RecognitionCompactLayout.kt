package com.autoscript.studio

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

internal fun recognitionFormSideBySide(width: Float, landscape: Boolean, fontScale: Float = 1f): Boolean =
    landscape && width.isFinite() && fontScale.isFinite() && fontScale > 0f && width / fontScale.coerceAtLeast(1f) >= 460f

@Composable
internal fun RecognitionDisclosure(title: String, expanded: Boolean, summary: String = "", onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min=28.dp).clickable(role=Role.Button,onClick=onClick)
        .padding(horizontal=2.dp,vertical=3.dp),verticalAlignment=Alignment.CenterVertically,
        horizontalArrangement=Arrangement.spacedBy(6.dp)) {
        Text(if(expanded) "▾" else "▸",fontSize=12.sp,color=MaterialTheme.colorScheme.primary)
        Text(title,fontSize=11.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)
        if(!expanded && summary.isNotBlank()) Text(summary,Modifier.weight(1f),fontSize=10.sp,
            color=MaterialTheme.colorScheme.onSurfaceVariant,maxLines=1,overflow=TextOverflow.Ellipsis)
    }
}
