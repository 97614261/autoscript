package com.autoscript.studio

import android.graphics.Bitmap
import androidx.compose.runtime.*
import com.autoscript.project.store.ProjectGlyphColor
import kotlinx.coroutines.*

private data class GlyphForegroundKey(val source: Bitmap?, val roi: ImageToolCodeGen.Roi?, val colors: List<ProjectGlyphColor>?)
internal data class GlyphForegroundPreview(val bitmap: Bitmap? = null, val message: String = "")

@Composable
internal fun rememberGlyphForegroundPreview(source: Bitmap?, roi: ImageToolCodeGen.Roi?, colors: List<ProjectGlyphColor>?, enabled: Boolean): GlyphForegroundPreview {
    val key = GlyphForegroundKey(source, roi, colors)
    var result by remember { mutableStateOf<Pair<GlyphForegroundKey, GlyphForegroundPreview>?>(null) }
    val owned = result?.second?.bitmap
    DisposableEffect(owned) { onDispose { owned?.recycle() } }
    LaunchedEffect(key, enabled) {
        result = null
        if (!enabled || source == null || roi == null) return@LaunchedEffect
        if (colors == null) {
            result = key to GlyphForegroundPreview(message = "颜色未填完整，暂显示原图")
            return@LaunchedEffect
        }
        try {
            delay(120)
            val width = source.width; val height = source.height
            require(width.toLong() * height <= GlyphDictionaryMaker.MAX_IMAGE_PIXELS)
            // Read the owned source on the UI thread before a replacement may recycle it.
            val pixels = IntArray(width * height)
            source.getPixels(pixels, 0, width, 0, 0, width, height)
            val output = withContext(Dispatchers.Default) {
                val work = currentCoroutineContext()
                val deadline = System.nanoTime() + 5_000_000_000L
                val mask = GlyphDictionaryMaker.foregroundMask(pixels, width, height,
                    roi.left, roi.top, roi.right, roi.bottom, colors) {
                    work.ensureActive(); require(System.nanoTime() < deadline) { "预览超时，请缩小选区" }
                }
                pixels.fill(0xff171923.toInt())
                for (y in 0 until roi.height) {
                    work.ensureActive()
                    for (x in 0 until roi.width) pixels[(roi.top + y) * width + roi.left + x] =
                        if (mask[y * roi.width + x]) -1 else 0xff000000.toInt()
                }
                pixels
            }
            ensureActive()
            result = key to GlyphForegroundPreview(Bitmap.createBitmap(output, width, height, Bitmap.Config.ARGB_8888),
                "白色为保留笔画 · 取色仍读取原图")
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: OutOfMemoryError) { result = key to GlyphForegroundPreview(message = "黑白预览内存不足，暂显示原图") }
        catch (invalid: Exception) { result = key to GlyphForegroundPreview(message = invalid.message ?: "黑白预览不可用，暂显示原图") }
    }
    return if (!enabled) GlyphForegroundPreview() else result?.takeIf { it.first == key }?.second
        ?: GlyphForegroundPreview(message = "正在生成黑白字形，暂显示原图…")
}
