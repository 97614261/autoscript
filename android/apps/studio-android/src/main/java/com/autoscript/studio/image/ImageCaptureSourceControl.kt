package com.autoscript.studio

import android.content.Context
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.autoscript.core.designsystem.AutoScriptPalette

private const val IMAGE_CAPTURE_SOURCE_KEY = "default-capture-source"
internal fun imageCapturePreferences(context: Context) =
    context.applicationContext.getSharedPreferences("image-tools", Context.MODE_PRIVATE)

internal fun readImageCaptureSource(context: Context): ImageCaptureSource = runCatching {
    ImageCaptureSource.fromPreference(imageCapturePreferences(context).getString(IMAGE_CAPTURE_SOURCE_KEY, null))
}.getOrDefault(ImageCaptureSource.EACH_CALL)

internal fun writeImageCaptureSource(context: Context, source: ImageCaptureSource) {
    imageCapturePreferences(context).edit().putString(IMAGE_CAPTURE_SOURCE_KEY, source.name).apply()
}

@Composable
internal fun rememberDefaultImageCaptureSource(): ImageCaptureSource {
    val context = LocalContext.current.applicationContext
    return remember(context) { readImageCaptureSource(context) }
}

@Composable
internal fun ImageCaptureSourceControl(
    source: ImageCaptureSource,
    label: String = "图像来源",
    showDescription: Boolean = true,
    onSelect: ((ImageCaptureSource) -> Unit)? = null,
) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(label, Modifier.widthIn(max = 66.dp), fontSize = 11.sp, color = AutoScriptPalette.TextSecondary)
            if (onSelect == null) {
                Text(source.title, fontSize = 11.sp, color = AutoScriptPalette.TextSecondary)
            } else StudioToolSegments(source.name, ImageCaptureSource.entries.map { it.name to it.title }, Modifier.weight(1f)) { key ->
                onSelect(ImageCaptureSource.valueOf(key))
            }
        }
        if (showDescription) Text(source.description, fontSize = 10.sp, lineHeight = 13.sp, color = AutoScriptPalette.TextSecondary)
    }
}
