package com.autoscript.studio

import android.app.Instrumentation
import android.graphics.*
import android.view.MotionEvent
import android.view.View
import androidx.activity.ComponentActivity
import com.autoscript.project.store.*
import com.autoscript.runtime.client.RuntimeClient
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.ByteArrayOutputStream
import java.util.concurrent.atomic.AtomicInteger
import org.json.JSONArray
import org.json.JSONObject

/** Deterministic pixels, actual Rust recognition and input delivered only to the public QA View. */
internal object LibraryVisionUsageFixture {
    fun run(test: Instrumentation, activity: ComponentActivity, client: RuntimeClient, store: ProjectStore,
        initial: ProjectSnapshot, record: (String, () -> Unit) -> Unit): ProjectSnapshot {
        val rt = OwnedUsageRuntime(client, store, initial)
        rt.variables("frame" to ProjectVariableType.IMAGE, "second" to ProjectVariableType.IMAGE,
            "series" to ProjectVariableType.STRING, "value" to ProjectVariableType.INTEGER,
            "x" to ProjectVariableType.INTEGER, "y" to ProjectVariableType.INTEGER, "score" to ProjectVariableType.INTEGER,
            "text" to ProjectVariableType.STRING, "details" to ProjectVariableType.STRING)
        val taps = AtomicInteger()
        test.runOnMainSync { activity.setContentView(object : View(activity) {
            init { setBackgroundColor(Color.BLACK) }
            override fun onTouchEvent(event: MotionEvent): Boolean {
                if (event.actionMasked == MotionEvent.ACTION_UP) taps.incrementAndGet()
                return true
            }
        }) }
        fun import(name: String, bitmap: Bitmap): String {
            val bytes = ByteArrayOutputStream().apply { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, this)) }.toByteArray()
            val before = store.openProject(rt.id).manifest.resources.map { it["path"].asString }.toSet()
            return store.importResource(rt.id, ProjectResourceKind.IMAGE, name, bytes.inputStream(), before)
                .manifest.resources.single { it["path"].asString !in before }["path"].asString
        }
        val template = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)
        val scene = Bitmap.createBitmap(320, 300, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.BLACK) }
        val scenePath: String
        val templatePath: String
        try {
            for (y in 0 until 8) for (x in 0 until 8) {
                val rgb = Color.rgb((x * 31 + y * 13) % 256, (x * 17 + y * 47) % 256, (x * 71 + y * 29) % 256)
                template.setPixel(x, y, rgb); scene.setPixel(140 + x, 180 + y, rgb)
            }
            for (y in 80 until 84) for (x in 10 until 16) scene.setPixel(x, y, Color.RED)
            scene.setPixel(50, 90, 0xff123456.toInt()); scene.setPixel(51, 90, 0xff654321.toInt())
            scenePath = import("public-vision-scene.png", scene); templatePath = import("public-vision-template.png", template)
        } finally { template.recycle(); scene.recycle() }
        val region = JsonObject().apply { addProperty("left", 0); addProperty("top", 0); addProperty("right", 320); addProperty("bottom", 300) }
        fun loaded(nodes: List<VisualBlockTree>, during: () -> Unit = {}, inspect: (Map<String, String>) -> Unit) =
            rt.run(listOf(rt.block("screen.loadimage", "imagePath" to scenePath, "resultVariable" to "frame")) + nodes +
                rt.block("screen.release", "frameVariable" to "frame"), during = during, inspect = inspect)
        fun image(kind: String, vararg args: Pair<String, Any>) = rt.block(kind, "frameVariable" to "frame", "imagePath" to templatePath,
            "autoCapture" to false, "region" to region, "similarityPermille" to 1000, "foundVariable" to "value",
            "xVariable" to "x", "yVariable" to "y", "scoreVariable" to "score", *args)
        try {
            record("library-vision.getcolor-comparecolor-countcolor-exact-public-pixels") {
                val point = JsonObject().apply { addProperty("x", 10); addProperty("y", 80) }
                loaded(listOf(rt.block("vision.getcolor", "frameVariable" to "frame", "point" to point, "resultVariable" to "x"),
                    rt.block("vision.comparecolor", "frameVariable" to "frame", "point" to point, "rgb" to 0xff0000, "resultVariable" to "y"),
                    rt.block("vision.countcolor", "frameVariable" to "frame", "rgb" to 0xff0000, "region" to region, "resultVariable" to "value"))) {
                    check(it["x"] == "16711680" && it["y"] == "1" && it["value"] == "24")
                }
            }
            record("library-vision.findcolor-and-findallcolor-return-real-coordinates") {
                loaded(listOf(rt.block("vision.findcolor", "frameVariable" to "frame", "rgb" to 0xff0000, "region" to region,
                    "foundVariable" to "value", "xVariable" to "x", "yVariable" to "y"),
                    rt.block("vision.findallcolor", "frameVariable" to "frame", "rgb" to 0xff0000, "region" to region, "limit" to 100,
                        "resultVariable" to "details"))) {
                    check(it["value"] == "1" && it["x"] == "10" && it["y"] == "80" && JSONArray(it["details"].orEmpty()).length() == 24)
                }
            }
            record("library-vision.findmulticolor-and-findallmulticolor-match-offset-pattern") {
                loaded(listOf(rt.block("vision.findmulticolor", "frameVariable" to "frame", "anchorRgb" to 0x123456,
                    "samples" to JsonParser.parseString("[{\"x\":1,\"y\":0,\"rgb\":6636321,\"tolerance\":0}]"), "region" to region, "foundVariable" to "value", "xVariable" to "x", "yVariable" to "y"),
                    rt.block("vision.findallmulticolor", "frameVariable" to "frame", "samples" to "[{\"dx\":0,\"dy\":0,\"rgb\":1193046,\"tolerance\":0},{\"dx\":1,\"dy\":0,\"rgb\":6636321,\"tolerance\":0}]",
                        "left" to 0, "top" to 0, "right" to 320, "bottom" to 300, "resultVariable" to "details"))) {
                    check(it["value"] == "1" && it["x"] == "50" && it["y"] == "90" && JSONArray(it["details"].orEmpty()).length() == 1)
                }
            }
            record("library-vision.components-reports-known-connected-area") {
                loaded(listOf(rt.block("vision.components", "frameVariable" to "frame", "rgb" to 0xff0000, "left" to 0, "top" to 0,
                    "right" to 320, "bottom" to 300, "resultVariable" to "details"))) {
                    val matches = JSONArray(it["details"].orEmpty()); check(matches.length() == 1 && matches.getJSONObject(0).getInt("area") == 24)
                }
            }
            for (kind in listOf("vision.findimage", "vision.findgray")) record("library-$kind-real-positive-and-negative-match") {
                loaded(listOf(image(kind))) { check(it["value"] == "1" && it["x"] == "140" && it["y"] == "180") { "QA_VISION_POSITIVE:$kind:$it" } }
                val absent = JsonObject().apply { addProperty("left", 0); addProperty("top", 0); addProperty("right", 40); addProperty("bottom", 40) }
                loaded(listOf(image(kind, "region" to absent))) { check(it["value"] == "0") }
            }
            record("library-vision.findallimages-result-and-resultaction-delivers-real-tap") {
                val before = taps.get()
                loaded(listOf(rt.block("vision.findallimages", "frameVariable" to "frame", "autoCapture" to false, "imagePaths" to JsonParser.parseString("[\"$templatePath\"]"),
                    "similarityPermille" to 1000, "region" to region, "resultVariable" to "details"),
                    rt.block("vision.result", "sourceVariable" to "details", "index" to 0, "field" to "count", "targetVariable" to "value"),
                    rt.block("vision.resultaction", "sourceVariable" to "details", "shape" to "IMAGES", "screenConfirmed" to true))) {
                    check(it["value"] == "1" && taps.get() == before + 1)
                }
            }
            record("library-vision.findscaled-readresult-returns-original-template-scale") {
                loaded(listOf(rt.block("vision.findscaled", "frameVariable" to "frame", "imagePath" to templatePath, "left" to 0, "top" to 0,
                    "right" to 320, "bottom" to 300, "similarityPermille" to 1000, "minScalePermille" to 1000, "maxScalePermille" to 1000,
                    "stepPermille" to 100, "resultVariable" to "details"), rt.block("vision.readresult", "sourceVariable" to "details",
                    "shape" to "SCALED_IMAGE", "index" to 1, "field" to "scalePermille", "resultVariable" to "value"))) { check(it["value"] == "1000") }
            }
            record("library-screen.crop-transform-frameinfo-and-savepng-real-pixels") {
                loaded(listOf(rt.block("screen.crop", "frameVariable" to "frame", "left" to 140, "top" to 180, "right" to 148, "bottom" to 188,
                    "resultVariable" to "second"), rt.block("screen.frameinfo", "frameVariable" to "second", "resultVariable" to "text"),
                    rt.block("screen.savepng", "frameVariable" to "second", "path" to "saved.png"), rt.block("screen.release", "frameVariable" to "second"),
                    rt.block("screen.transform", "frameVariable" to "frame", "mode" to "rotate90", "resultVariable" to "second"),
                    rt.block("screen.frameinfo", "frameVariable" to "second", "resultVariable" to "details"), rt.block("screen.release", "frameVariable" to "second"),
                    rt.block("files.exists", "path" to "saved.png", "resultVariable" to "value"))) {
                    check(JSONObject(it["text"].orEmpty()).getInt("width") == 8 && JSONObject(it["text"].orEmpty()).getInt("height") == 8)
                    check(JSONObject(it["details"].orEmpty()).getInt("width") == 300 && it["value"] == "1")
                }
            }
            record("library-screen.compareframes-identical-real-pixels-report-zero-change") {
                loaded(listOf(rt.block("screen.loadimage", "imagePath" to scenePath, "resultVariable" to "second"),
                    rt.block("screen.compareframes", "frameVariable" to "frame", "previousFrameVariable" to "second", "left" to 0,
                    "top" to 0, "right" to 320, "bottom" to 300, "resultVariable" to "details"), rt.block("screen.release", "frameVariable" to "second"))) {
                    check(JSONObject(it["details"].orEmpty()).getInt("changedPixels") == 0)
                }
            }
            record("library-screen.captureregion-and-mappoint-restore-origin") {
                rt.run(listOf(rt.block("screen.captureregion", "left" to 100, "top" to 150, "right" to 140, "bottom" to 190,
                    "resultVariable" to "frame"), rt.block("screen.mappoint", "frameVariable" to "frame", "x" to 0, "y" to 0, "resultVariable" to "text"),
                    rt.block("screen.release", "frameVariable" to "frame"))) {
                    val mapped = JSONObject(it["text"].orEmpty()); check(mapped.getInt("x") == 100 && mapped.getInt("y") == 150)
                }
            }
            record("library-screen.series-seriesframe-seriesrelease-real-root-captures") {
                rt.run(listOf(rt.block("screen.series", "maxFrames" to 2, "durationMs" to 800, "targetFps" to 4, "allowPartial" to false,
                    "resultVariable" to "series"), rt.block("screen.seriesframe", "sourceVariable" to "series", "index" to 1, "resultVariable" to "frame"),
                    rt.block("screen.frameinfo", "frameVariable" to "frame", "resultVariable" to "text"),
                    rt.block("screen.seriesrelease", "sourceVariable" to "series"))) { check(JSONObject(it["text"].orEmpty()).getBoolean("hasScreenOrigin")) }
            }
            record("library-ocr.alphanumeric-reads-public-digits-from-real-image") {
                val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; textSize = 32f; typeface = Typeface.SANS_SERIF }
                val digits = Bitmap.createBitmap(kotlin.math.ceil(paint.measureText("1234567890")).toInt() + 24, 56, Bitmap.Config.ARGB_8888)
                val path: String; val width = digits.width
                try { Canvas(digits).apply { drawColor(Color.WHITE); drawText("1234567890", 12f, 39f, paint) }; path = import("public-original-ocr.png", digits) }
                finally { digits.recycle() }
                val roi = JsonObject().apply { addProperty("left", 0); addProperty("top", 0); addProperty("right", width); addProperty("bottom", 56) }
                rt.run(listOf(rt.block("screen.loadimage", "imagePath" to path, "resultVariable" to "frame"),
                    rt.block("ocr.alphanumeric", "frameVariable" to "frame", "autoCapture" to false, "region" to roi,
                        "textVariable" to "text", "scoreVariable" to "score"), rt.block("screen.release", "frameVariable" to "frame"))) {
                    check(it["text"] == "1234567890") { "QA_ORIGINAL_OCR:$it" }
                }
            }
            return store.openProject(rt.id)
        } finally { rt.stop() }
    }
}
