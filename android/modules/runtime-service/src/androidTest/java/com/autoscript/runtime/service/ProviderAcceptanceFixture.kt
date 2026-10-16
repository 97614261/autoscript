package com.autoscript.runtime.service

import android.content.Context
import android.content.ContextWrapper
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.os.Debug
import android.os.SystemClock
import com.autoscript.runtime.vision.components.AndroidVisionComponents
import com.autoscript.runtime.vision.components.VisionComponentKind
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FilterInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CancellationException
import kotlin.math.ceil

/** Fixed trusted SDK APKs, real private import/load. No Studio selections or user content. */
internal object ProviderAcceptanceFixture {
    fun run(context: Context, soak: Boolean = false): JSONObject {
        val cases = JSONArray()
        val report = JSONObject().put("scope", "base APK; real signed external providers; synthetic images only")
            .put("cases", cases).put("pass", false)
        val started = SystemClock.elapsedRealtime()
        fun checkpoint() { check(SystemClock.elapsedRealtime() - started < if (soak) 420_000 else 100_000) { "PROVIDER_SUITE_TIMEOUT" } }
        report.put("soak", soak)
        fun case(name: String, work: () -> Unit) {
            val row = JSONObject().put("case", name).put("pass", false); cases.put(row)
            checkpoint(); work(); row.put("pass", true)
        }
        val sandbox = File(context.filesDir, "acceptance-provider-sandbox-v2")
        try {
            check(bundledVisionKinds().isEmpty()) { "BUNDLED_SDK_WOULD_MASK_PROVIDER" }
            check(sandbox.mkdirs() || sandbox.isDirectory)
            check(sandbox.canonicalFile == sandbox.absoluteFile)
            val isolated = object : ContextWrapper(context) {
                override fun getApplicationContext(): Context = this
                override fun getFilesDir(): File = sandbox
            }
            val store = AndroidVisionComponents.store(isolated)
            case("actual-signed-manifest-metadata") {
                val file = File(sandbox, "metadata-probe.apk")
                check(!file.exists()) { "UNKNOWN_PROBE_FILE" }
                try {
                    context.assets.open("fixed-providers/opencv.apk").use { input -> file.outputStream().use { output ->
                        val bytes = ByteArray(65_536)
                        while (true) { checkpoint(); val count = input.read(bytes); if (count < 0) break; output.write(bytes, 0, count) }
                    } }
                    check(file.setReadOnly())
                    @Suppress("DEPRECATION")
                    val info = requireNotNull(context.packageManager.getPackageArchiveInfo(file.path, PackageManager.GET_PERMISSIONS))
                    val permissions = info.requestedPermissions.orEmpty().toList() + info.permissions.orEmpty().map { it.name }
                    report.put("manifestPermissionCount", permissions.size)
                        .put("androidPermissions", JSONArray(permissions.filter { it.matches(Regex("android\\.permission\\.[A-Z_]{1,100}")) }))
                        .put("otherPermissionCount", permissions.count { !it.startsWith("android.permission.") })
                } finally { check(!file.exists() || file.delete()) }
            }
            // The same explicit test-owned sandbox allows the next process to reclaim only dead leases.
            report.put("retainedBeforeLoad", store.recoverPrepared(::checkpoint))
            val abi = requireNotNull(visionProcessAbi(android.os.Process.is64Bit(), System.getProperty("os.arch").orEmpty(), android.os.Build.SUPPORTED_ABIS.toList()))
            report.put("processAbi", abi)
            val loader = FixedVisionProviderLoader()
            val loaded = linkedMapOf<Int, LoadedVisionProvider>()
            try {
                for (kind in listOf(VisionComponentKind.OPENCV, VisionComponentKind.ONNX)) {
                    val asset = "fixed-providers/${kind.id}.apk"
                    case("${kind.id}-reject-tampered-signature") {
                        val directory = File(sandbox, "vision-providers")
                        val before = directory.listFiles().orEmpty().map { it.name }.toSet()
                        var position = 0L
                        try {
                            store.import({ object : FilterInputStream(context.assets.open(asset)) {
                                override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
                                    val count = super.read(bytes, offset, length)
                                    if (count > 0) {
                                        if (position <= 64 && position + count > 64) {
                                            val index = offset + (64 - position).toInt(); bytes[index] = (bytes[index].toInt() xor 1).toByte()
                                        }
                                        position += count
                                    }
                                    return count
                                }
                            } }, ::checkpoint)
                            error("TAMPERED_COMPONENT_ACCEPTED")
                        } catch (_: IllegalArgumentException) { check(position > 64) }
                        check(directory.listFiles().orEmpty().map { it.name }.toSet() == before) { "TAMPERED_IMPORT_LEAK" }
                    }
                    case("${kind.id}-cancel-import") {
                        var read = 0L
                        try {
                            store.import({ object : FilterInputStream(context.assets.open(asset)) {
                                override fun read(bytes: ByteArray, offset: Int, length: Int): Int =
                                    super.read(bytes, offset, length).also { if (it > 0) read += it }
                            } }) { checkpoint(); if (read >= 65_536) throw CancellationException() }
                            error("CANCEL_NOT_PROPAGATED")
                        } catch (_: CancellationException) { check(read >= 65_536) }
                        check(File(sandbox, "vision-providers").listFiles().orEmpty().none { it.name.startsWith("incoming-") })
                    }
                    var verified: com.autoscript.runtime.vision.components.VerifiedVisionComponent? = null
                    case("${kind.id}-signed-import-and-explicit-selection") {
                        store.clearSelection(kind, ::checkpoint)
                        val imported = store.import({ context.assets.open(asset) }, ::checkpoint)
                        check(imported.descriptor.kind == kind && store.selectionId(kind, ::checkpoint) == null)
                        store.select(kind, imported.sha256, ::checkpoint)
                        check(store.selected(kind, ::checkpoint)?.sha256 == imported.sha256)
                        verified = imported
                    }
                    val component = requireNotNull(verified)
                    case("${kind.id}-unsupported-abi") {
                        try { store.prepare(component.sha256, kind, "armeabi-v7a", ::checkpoint).close(); error("BAD_ABI_ACCEPTED") }
                        catch (_: IllegalArgumentException) { }
                    }
                    case("${kind.id}-cancel-preparation") {
                        val root = File(sandbox, "vision-providers/prepared")
                        val before = root.listFiles().orEmpty().map { it.name }.toSet()
                        var cancelledInsideSnapshot = false
                        try {
                            store.prepare(component.sha256, kind, abi) {
                                checkpoint()
                                if (root.listFiles().orEmpty().any { it.name !in before }) {
                                    cancelledInsideSnapshot = true; throw CancellationException()
                                }
                            }.close()
                            error("CANCEL_NOT_PROPAGATED")
                        } catch (_: CancellationException) { check(cancelledInsideSnapshot) }
                        check(root.listFiles().orEmpty().map { it.name }.toSet() == before) { "CANCEL_SNAPSHOT_LEAK" }
                    }
                    case("${kind.id}-load-close-and-reuse-pinned-snapshot") {
                        val prepared = store.prepare(component.sha256, kind, abi, ::checkpoint)
                        val apk = prepared.apk
                        check(!apk.parentFile!!.canWrite() && !prepared.nativeDirectory.canWrite()) { "SNAPSHOT_DIRECTORY_NOT_SEALED" }
                        val first = loader.load(prepared, abi, ::checkpoint)
                        first.close()
                        check(apk.isFile) { "PINNED_SNAPSHOT_REMOVED" }
                        val reused = requireNotNull(loader.loadExisting(kind, component.sha256, abi, ::checkpoint))
                        loaded[if (kind == VisionComponentKind.OPENCV) 5108 else 6101] = reused
                        // recoverPrepared returns RETAINED count, not the number it removed.
                        check(store.recoverPrepared(::checkpoint) == loaded.size && apk.isFile) { "ACTIVE_LEASE_NOT_RETAINED" }
                    }
                }
                val source = object : NativeVisionBackendSource {
                    override fun resolve(operation: Int, work: NativeVisionWork): NativeVisionBackend = requireNotNull(loaded[operation])
                    override fun close() { loaded.values.forEach { it.close() } }
                }
                NativeVisionAdapter(source).use { adapter ->
                    val frame = Bitmap.createBitmap(96, 64, Bitmap.Config.ARGB_8888)
                    val template = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)
                    val text = "AaBb09Zz"
                    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; textSize = 32f; typeface = Typeface.SANS_SERIF }
                    val letters = Bitmap.createBitmap(ceil(paint.measureText(text)).toInt() + 24, 56, Bitmap.Config.ARGB_8888)
                    try {
                        frame.eraseColor(Color.WHITE)
                        for (y in 0 until 8) for (x in 0 until 8) {
                            val gray = 20 + (x * 37 + y * 17) % 180
                            val color = Color.rgb(gray, gray, gray)
                            template.setPixel(x, y, color); frame.setPixel(31 + x, 23 + y, color)
                        }
                        Canvas(letters).apply { drawColor(Color.WHITE); drawText(text, 12f, 39f, paint) }
                        val pixels = rgba(frame); val target = rgba(template); val textPixels = rgba(letters)
                        var id = 7000L
                        fun dispatch(operation: Int): ByteArray = if (operation == 5108)
                            adapter.dispatch(++id, operation, pixels, metadata(frame), target, metadata(template), intArrayOf(10, 12, 86, 58, 990))
                        else adapter.dispatch(++id, operation, textPixels, metadata(letters), null, intArrayOf(), intArrayOf(0, 0, letters.width, letters.height, 500))
                        fun verify(operation: Int, result: ByteArray) {
                            check(result.firstOrNull() == 0.toByte()) { "EXTERNAL_SDK_OPERATION_FAILED" }
                            val values = ByteBuffer.wrap(result).order(ByteOrder.LITTLE_ENDIAN).apply { get() }
                            if (operation == 5108) check(result.size == 11 && values.int == 31 && values.int == 23 && values.short.toInt() >= 990) { "EXTERNAL_GRAY_RESULT" }
                            else {
                                check(values.short.toInt() in 500..1000)
                                val count = values.remaining()
                                check(count in 1..256)
                                val bytes = ByteArray(count); values.get(bytes)
                                check(bytes.toString(Charsets.UTF_8) == text) { "EXTERNAL_OCR_RESULT" }
                            }
                        }
                        val repetitions = if (soak) listOf(5108 to 20_000, 6101 to 2_000) else listOf(5108 to 200, 6101 to 40)
                        for ((operation, loops) in repetitions) case("$operation-real-sdk-stability") {
                            val coldStart = SystemClock.elapsedRealtimeNanos(); verify(operation, dispatch(operation))
                            val coldMs = (SystemClock.elapsedRealtimeNanos() - coldStart) / 1e6
                            val timings = ArrayList<Double>()
                            val memory = JSONArray()
                            repeat(loops) { sample ->
                                checkpoint()
                                val begin = SystemClock.elapsedRealtimeNanos(); verify(operation, dispatch(operation))
                                timings.add((SystemClock.elapsedRealtimeNanos() - begin) / 1e6)
                                if (sample % (loops / 20).coerceAtLeast(10) == 0 || sample == loops - 1) memory.put(JSONObject().put("sample", sample)
                                    .put("nativeHeapBytes", Debug.getNativeHeapAllocatedSize()).put("pssKiB", Debug.getPss()))
                            }
                            val sorted = timings.sorted()
                            fun p(fraction: Double) = sorted[(ceil(sorted.size * fraction).toInt() - 1).coerceIn(0, sorted.lastIndex)]
                            val row = cases.getJSONObject(cases.length() - 1)
                            row.put("samples", loops).put("coldMs", coldMs).put("p50Ms", p(.5)).put("p95Ms", p(.95)).put("p99Ms", p(.99))
                                .put("frame", if (operation == 5108) JSONArray(listOf(96, 64)) else JSONArray(listOf(letters.width, letters.height)))
                                .put("roi", if (operation == 5108) JSONArray(listOf(10, 12, 86, 58)) else JSONArray(listOf(0, 0, letters.width, letters.height)))
                                .put("template", if (operation == 5108) JSONArray(listOf(8, 8)) else JSONObject.NULL).put("memorySamples", memory)
                        }
                        case("pre-cancelled-request-and-subsequent-reuse") {
                            adapter.cancel(id + 1)
                            val result = dispatch(6101)
                            check(result.firstOrNull() == 1.toByte() && result.copyOfRange(1, result.size).toString(Charsets.US_ASCII) == "VISION_CANCELLED_OR_TIMEOUT")
                            verify(6101, dispatch(6101)); verify(5108, dispatch(5108))
                        }
                    } finally { frame.recycle(); template.recycle(); letters.recycle() }
                }
            } finally { loaded.values.forEach { runCatching { it.close() } } }
            report.put("pass", true).put("retained", "test-owned APKs and process-pinned snapshots; next process recovers dead leases")
        } catch (failure: Throwable) {
            report.put("error", failure.javaClass.simpleName)
                .put("code", failure.message?.takeIf { it.matches(Regex("[A-Z_]{1,80}")) } ?: "PROVIDER_ACCEPTANCE_FAILED")
                .put("locations", JSONArray(failure.stackTrace.filter { it.className.startsWith("com.autoscript.") }
                    .take(6).map { "${it.className.substringAfterLast('.')}:${it.lineNumber}" }))
        }
        return report.put("elapsedMs", SystemClock.elapsedRealtime() - started)
    }

    private fun metadata(bitmap: Bitmap) = intArrayOf(bitmap.width, bitmap.height, bitmap.width * 4, 1)
    private fun rgba(bitmap: Bitmap): ByteBuffer = ByteBuffer.allocateDirect(bitmap.width * bitmap.height * 4).apply {
        for (y in 0 until bitmap.height) for (x in 0 until bitmap.width) {
            val value = bitmap.getPixel(x, y)
            put(Color.red(value).toByte()); put(Color.green(value).toByte()); put(Color.blue(value).toByte()); put(255.toByte())
        }
        flip()
    }
}
