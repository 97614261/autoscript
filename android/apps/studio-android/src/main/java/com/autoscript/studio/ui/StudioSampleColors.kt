package com.autoscript.studio

import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Replace only the chosen color; malformed coordinates, tolerances and empty rows stay editable. */
internal fun studioReplaceSampleColor(value: String, row: Int, column: Int, color: String): String {
    require(studioParseColor(color, StudioColorFormat.RGB_HEX) != null)
    val rows = value.split(';').toMutableList()
    require(rows.size <= 256 && row in rows.indices)
    val fields = rows[row].split(',').toMutableList()
    require(column in fields.indices)
    fields[column] = color
    rows[row] = fields.joinToString(",")
    return rows.joinToString(";")
}

@Composable
internal fun StudioSampleColors(value: String, colorColumn: Int, onChange: (String) -> Unit) {
    val rows = remember(value) { value.takeIf { it.length <= 16384 }?.split(';')?.takeIf { it.size <= 256 }.orEmpty() }
    val choices = rows.mapIndexedNotNull { index, row ->
        row.split(',').getOrNull(colorColumn)?.let { Triple(index, it.trim(), row) }
    }
    if (choices.isEmpty()) return
    StudioCompactGrid(choices, cellDp = 100, maximum = 4) { (index, color, _), cell ->
        key(value, index) {
            Row(cell, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("色${index + 1}", fontSize = 10.sp)
                StudioColorButton("色点${index + 1}", color, StudioColorFormat.RGB_HEX) {
                    onChange(studioReplaceSampleColor(value, index, colorColumn, it))
                }
            }
        }
    }
}
