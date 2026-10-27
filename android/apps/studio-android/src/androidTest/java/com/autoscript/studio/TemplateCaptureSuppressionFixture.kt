package com.autoscript.studio

import android.app.Activity
import android.app.Instrumentation
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.os.Bundle
import android.os.SystemClock
import android.view.View
import android.view.Window
import android.view.WindowManager
import android.widget.TextView
import androidx.activity.ComponentDialog
import com.autoscript.runtime.client.RuntimeClient
import com.autoscript.runtime.client.ScreenshotPreviewResult
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Content-free pixel diagnostic for native dim-layer removal during editor capture. */
internal object TemplateCaptureSuppressionFixture {
    fun run(test: Instrumentation, owner: Activity, client: RuntimeClient, record: (String, () -> Unit) -> Unit) {
        val expected = Color.rgb(224, 160, 96)
        val draw = CountDownLatch(1)
        lateinit var surface: View
        val session = EditorOverlaySession({})
        lateinit var dialog: ComponentDialog
        test.runOnMainSync {
            surface = object : View(owner) {
                override fun onDraw(canvas: Canvas) { canvas.drawColor(expected); draw.countDown() }
            }
            owner.setContentView(surface)
            dialog = ComponentDialog(owner).apply {
                requestWindowFeature(Window.FEATURE_NO_TITLE)
                window!!.apply {
                    setType(editorOverlayWindowType())
                    setBackgroundDrawableResource(android.R.color.transparent)
                    addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
                    attributes = attributes.apply { dimAmount = .25f; width = 120; height = 80 }
                }
                setContentView(TextView(owner).apply { text = "PUBLIC_QA_DIM"; setBackgroundColor(Color.WHITE) })
            }
        }
        check(draw.await(5, TimeUnit.SECONDS)) { "QA_DIM_SURFACE_DRAW" }
        fun pixel(label: String): Int {
            val result = runBlocking { withTimeout(20_000) { client.capturePreviewWhenReady {} } }
            check(result is ScreenshotPreviewResult.Success) { "QA_DIM_CAPTURE" }
            try {
                val bitmap = requireNotNull(BitmapFactory.decodeFile(result.file.path))
                try {
                    val location = IntArray(2)
                    test.runOnMainSync { surface.getLocationOnScreen(location) }
                    val value = bitmap.getPixel(location[0] + surface.width / 4, location[1] + surface.height / 4)
                    test.sendStatus(1, Bundle().apply { putString("stream", "QA_DIM_PIXEL:$label:${Integer.toHexString(value)}\n") })
                    return value
                } finally { bitmap.recycle() }
            } finally { check(result.file.delete()) { "QA_DIM_CAPTURE_CLEANUP" } }
        }
        try {
            record("capture-dim-plain-baseline") { SystemClock.sleep(300); check(pixel("plain") == expected) }
            test.runOnMainSync {
                session.register(dialog)
                dialog.window!!.attributes = dialog.window!!.attributes.apply { dimAmount = .25f }
            }
            SystemClock.sleep(500)
            record("capture-dim-shown-baseline") { check(pixel("shown") != expected) }
            runBlocking { awaitEditorCaptureSurface(owner, session, "capture:qa-dim") }
            val hiddenPixel = pixel("hidden-after-capture-barrier")
            SystemClock.sleep(500)
            record("capture-dim-fully-settled") { check(pixel("hidden-settled") == expected) }
            record("capture-dim-hidden-at-editor-delay") { check(hiddenPixel == expected) { "QA_DIM_CAPTURE_STILL_DARK" } }
        } finally { test.runOnMainSync { session.closeDialogs() } }
    }
}
