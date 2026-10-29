package com.autoscript.studio

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp

@Composable
internal fun <T> StudioCompactGrid(
    items: List<T>,
    cellDp: Int,
    maximum: Int,
    modifier: Modifier = Modifier,
    content: @Composable (T, Modifier) -> Unit,
) {
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val columns = studioCompactGridColumns(maxWidth.value, LocalDensity.current.fontScale, cellDp, maximum)
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            items.chunked(columns).forEach { row ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    row.forEach { content(it, Modifier.weight(1f)) }
                    repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
    }
}
