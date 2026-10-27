package com.autoscript.studio

import android.app.Activity
import android.app.Instrumentation
import android.content.pm.ActivityInfo
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Point
import android.os.SystemClock
import android.view.View
import com.autoscript.runtime.client.RuntimeClient
import com.autoscript.runtime.client.ScreenshotPreviewResult
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** New Root captures of an owned asymmetric public surface, including actual rotation. */
internal object RootCaptureGeometryFixture {
    fun run(test: Instrumentation, owner: Activity, client: RuntimeClient, record: (String, () -> Unit) -> Unit,
        drawContent: ((Canvas, View) -> Unit)? = null,
        regionCheck: ((Point, Point, Int, Int) -> Unit)? = null) {
        val original = owner.requestedOrientation
        fun size() = Point().also { @Suppress("DEPRECATION") owner.windowManager.defaultDisplay.getRealSize(it) }
        val initial = size()
        fun await(predicate: () -> Boolean) {
            val end = SystemClock.elapsedRealtime() + 15_000
            while (!predicate() && SystemClock.elapsedRealtime() < end) SystemClock.sleep(50)
            check(predicate()) { "QA_CAPTURE_GEOMETRY_TIMEOUT" }
        }
        var color = Color.rgb(29, 97, 173)
        var drawn: CountDownLatch? = null
        val alternate = Color.rgb(183, 53, 89)
        lateinit var surface: View
        test.runOnMainSync {
            surface = object : View(owner) {
                override fun onDraw(canvas: Canvas) {
                    canvas.drawColor(color)
                    val paint = android.graphics.Paint().apply { this.color = alternate }
                    canvas.drawRect(0f, 0f, width * .3f, height * .4f, paint)
                    drawContent?.invoke(canvas, this)
                    drawn?.countDown()
                }
            }
            owner.setContentView(surface)
        }
        try {
            for ((phase, orientation) in listOf("portrait" to ActivityInfo.SCREEN_ORIENTATION_PORTRAIT,
                "landscape" to ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE, "portrait-restored" to ActivityInfo.SCREEN_ORIENTATION_PORTRAIT)) {
                test.runOnMainSync { owner.requestedOrientation = orientation }
                await { size().let { (it.x > it.y) == (orientation == ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE) } &&
                    surface.width > 0 && surface.height > 0 && (surface.width > surface.height) == (orientation == ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE) }
                for (generation in 1..2) record("root-new-frame-$phase-generation-$generation") {
                    val draw = CountDownLatch(1)
                    val frames = CountDownLatch(1)
                    test.runOnMainSync {
                        color = if (generation == 1) Color.rgb(29, 97, 173) else Color.rgb(41, 161, 73)
                        drawn = draw
                        surface.invalidate()
                    }
                    check(draw.await(5, TimeUnit.SECONDS)) { "QA_CAPTURE_DRAW_TIMEOUT" }
                    test.runOnMainSync { surface.postOnAnimation { surface.postOnAnimation { frames.countDown() } } }
                    check(frames.await(5, TimeUnit.SECONDS)) { "QA_CAPTURE_PRESENT_TIMEOUT" }
                    val first = Point()
                    val second = Point()
                    test.runOnMainSync {
                        val location = IntArray(2)
                        surface.getLocationOnScreen(location)
                        first.set(location[0] + surface.width / 6, location[1] + surface.height / 5)
                        second.set(location[0] + surface.width * 2 / 3, location[1] + surface.height * 2 / 3)
                    }
                    var settledCaptures = 0
                    await {
                        val ready = runBlocking { withTimeout(20_000) { client.capturePreviewWhenReady {} } }
                        check(ready is ScreenshotPreviewResult.Success) { "QA_CAPTURE_GEOMETRY_BACKEND" }
                        try {
                            val image = requireNotNull(BitmapFactory.decodeFile(ready.file.absolutePath))
                            try {
                                settledCaptures++
                                val dimensions = size()
                                image.width == dimensions.x && image.height == dimensions.y &&
                                    first.x in 0 until image.width && second.x in 0 until image.width &&
                                    first.y in 0 until image.height && second.y in 0 until image.height &&
                                    image.getPixel(first.x, first.y) == alternate && image.getPixel(second.x, second.y) == color
                            } finally { image.recycle() }
                        } finally { check(ready.file.delete()) { "QA_CAPTURE_GEOMETRY_FILE_CLEANUP" } }
                    }
                    test.sendStatus(1, android.os.Bundle().apply { putString("stream", "QA_FRAME_SETTLE_CAPTURES:$phase:$generation:$settledCaptures\n") })
                    val result = runBlocking { withTimeout(20_000) { client.capturePreviewWhenReady {} } }
                    check(result is ScreenshotPreviewResult.Success) { "QA_CAPTURE_GEOMETRY_BACKEND" }
                    try {
                        val image = requireNotNull(BitmapFactory.decodeFile(result.file.absolutePath))
                        try {
                            val dimensions = size()
                            check(image.width == dimensions.x && image.height == dimensions.y) { "QA_CAPTURE_GEOMETRY_DIMENSIONS" }
                            check(image.getPixel(first.x, first.y) == alternate && image.getPixel(second.x, second.y) == color) { "QA_CAPTURE_GEOMETRY_PIXELS_OR_STALE" }
                        } finally { image.recycle() }
                    } finally { check(result.file.delete()) { "QA_CAPTURE_GEOMETRY_FILE_CLEANUP" } }
                    regionCheck?.invoke(first,second,alternate,color)
                }
            }
        } finally {
            test.runOnMainSync { owner.requestedOrientation = restoredScreenOrientation(original) }
            await { size() == initial }
        }
    }
}
