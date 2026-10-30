package com.autoscript.studio

import android.app.Instrumentation
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.os.Bundle
import com.autoscript.project.store.ProjectResourceKind
import com.autoscript.project.store.ProjectSnapshot
import com.autoscript.project.store.ProjectStore
import com.autoscript.project.store.ProjectVariableType
import com.autoscript.runtime.client.RuntimeClient
import com.google.gson.JsonObject
import java.io.ByteArrayOutputStream
import kotlin.math.ceil

/** Reuses the failing public sample through production compilation, without an emulator GPU. */
internal object OcrUsageAcceptanceFixture {
    fun run(test: Instrumentation, client: RuntimeClient, store: ProjectStore, initial: ProjectSnapshot,
        record: (String, () -> Unit) -> Unit): ProjectSnapshot {
        val rt = OwnedUsageRuntime(client, store, initial)
        rt.variables("frame" to ProjectVariableType.IMAGE, "text" to ProjectVariableType.STRING,
            "score" to ProjectVariableType.INTEGER)
        try {
            record("ocr-original-public-digits-retain-the-previously-missing-three") {
                val expected = "1234567890"
                val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = Color.BLACK; textSize = 32f; typeface = Typeface.SANS_SERIF
                }
                val bitmap = Bitmap.createBitmap(ceil(paint.measureText(expected)).toInt() + 24, 56, Bitmap.Config.ARGB_8888)
                val width = bitmap.width
                val bytes = try {
                    Canvas(bitmap).apply { drawColor(Color.WHITE); drawText(expected, 12f, 39f, paint) }
                    ByteArrayOutputStream().apply { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, this)) }.toByteArray()
                } finally { bitmap.recycle() }
                val before = store.openProject(rt.id).manifest.resources.map { it["path"].asString }.toSet()
                val path = store.importResource(rt.id, ProjectResourceKind.IMAGE, "public-original-ocr.png", bytes.inputStream(), before)
                    .manifest.resources.single { it["path"].asString !in before }["path"].asString
                val roi = JsonObject().apply {
                    addProperty("left", 0); addProperty("top", 0); addProperty("right", width); addProperty("bottom", 56)
                }
                rt.run(listOf(rt.block("screen.loadimage", "imagePath" to path, "resultVariable" to "frame"),
                    rt.block("ocr.alphanumeric", "frameVariable" to "frame", "autoCapture" to false,
                        "region" to roi, "textVariable" to "text", "scoreVariable" to "score"),
                    rt.block("screen.release", "frameVariable" to "frame"))) {
                    test.sendStatus(1, Bundle().apply {
                        putString("stream", "QA_PUBLIC_OCR: expected=$expected actual=${it["text"]} score=${it["score"]}\n")
                    })
                    check(it["text"] == expected) { "QA_ORIGINAL_OCR:$it" }
                }
            }
            return store.openProject(rt.id)
        } finally { rt.stop() }
    }
}
