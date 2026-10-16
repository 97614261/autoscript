package com.autoscript.runtime.service

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.ParcelFileDescriptor
import android.system.Os
import android.system.OsConstants
import com.autoscript.engine.jni.NativeEngineBridge
import com.autoscript.runtime.api.RecognitionPreviewReply as Reply
import com.autoscript.runtime.api.RuntimeProtocol
import org.json.JSONObject
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.io.ByteArrayOutputStream
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Isolated from script resources and the script's native adapter. Binder owns the input FDs. */
internal class RuntimeRecognitionPreview(source: NativeVisionBackendSource, busy: AtomicBoolean,
    private val generation: () -> Long, private val runtimeState: () -> Int) : AutoCloseable {
    private val gate = RecognitionPreviewGate(busy)
    private val adapter = NativeVisionAdapter(source, ownsSource = false)
    private val deadlines = Executors.newSingleThreadScheduledExecutor { task -> Thread(task, "preview-deadline").apply { isDaemon = true } }
    private data class Image(val width: Int, val height: Int, val rgba: ByteArray) {
        fun direct(): ByteBuffer = ByteBuffer.allocateDirect(rgba.size).put(rgba).apply { flip() }
        fun metadata() = intArrayOf(width, height, width * 4, 1)
    }

    fun cancel(id: Long, expectedGeneration: Long) {
        gate.cancel(id, expectedGeneration)
        adapter.cancel(id)
        NativeEngineBridge.nativeCancelRecognitionPreview(id)
    }

    fun test(id: Long, expectedGeneration: Long, operation: Int, frame: ParcelFileDescriptor,
        asset: ParcelFileDescriptor?, options: IntArray): Reply = frame.use { source -> asset.use { target ->
        fun failure(status: Int) = Reply(id, expectedGeneration, status, operation)
        if (expectedGeneration != generation()) return failure(Reply.SESSION_MISMATCH)
        if (id <= 0 || !Reply.validOptions(operation, options) || source.statSize !in 1..MAX_FILE_BYTES ||
            (operation != Reply.ALPHANUMERIC && (target == null || target.statSize !in 1..if (operation in setOf(Reply.GLYPH,Reply.GLYPH_DETAILS)) MAX_DICTIONARY_BYTES else MAX_FILE_BYTES)) ||
            (operation == Reply.ALPHANUMERIC && target != null)) return failure(Reply.INVALID_INPUT)
        if (runtimeState() in setOf(RuntimeProtocol.STATE_RUNNING, RuntimeProtocol.STATE_PAUSED, RuntimeProtocol.STATE_STOPPING)) return failure(Reply.BUSY)
        val work = gate.begin(id, expectedGeneration) ?: return failure(Reply.BUSY)
        var expiry: java.util.concurrent.ScheduledFuture<*>? = null
        try {
            expiry = deadlines.schedule({ cancel(id, expectedGeneration) }, (work.deadline-System.nanoTime()).coerceAtLeast(1), TimeUnit.NANOSECONDS)
            gate.status(work, generation())?.let { return failure(it) }
            val image = decode(source)
            require(options[2] <= image.width && options[3] <= image.height)
            gate.status(work, generation())?.let { return failure(it) }
            val result = when (operation) {
                Reply.GLYPH, Reply.GLYPH_DETAILS -> {
                    val dictionary = ParcelFileDescriptor.AutoCloseInputStream(requireNotNull(target).dup()).use { input ->
                        val output = ByteArrayOutputStream()
                        val buffer = ByteArray(16_384)
                        while (true) {
                            gate.status(work, generation())?.let { return failure(it) }
                            val count = input.read(buffer)
                            if (count < 0) break
                            require(output.size().toLong() + count <= MAX_DICTIONARY_BYTES)
                            output.write(buffer, 0, count)
                        }
                        require(output.size() > 0); output.toByteArray()
                    }
                    val data = JSONObject(requireNotNull(NativeEngineBridge.nativeTestGlyph(id, image.width, image.height, image.rgba, dictionary, options)))
                    val status = data.getInt("status")
                    if(operation == Reply.GLYPH_DETAILS && status in setOf(Reply.SUCCESS,Reply.NOT_FOUND) && !data.has("details")) return failure(Reply.BUDGET_EXCEEDED)
                    val text = (if(operation == Reply.GLYPH_DETAILS && data.has("details")) data.getJSONObject("details").toString() else data.getString("text")).also { require(it.toByteArray(Charsets.UTF_8).size <= Reply.MAX_TEXT_LENGTH) }
                    Reply(id, expectedGeneration, data.getInt("status"), operation, text = text,
                        scorePermille = data.getInt("score"), coveragePermille = data.getInt("coverage"))
                }
                Reply.TEMPLATE -> {
                    val template = decode(requireNotNull(target))
                    val cropped = crop(image, options)
                    val found = requireNotNull(NativeEngineBridge.nativeTestTemplateConfigured(id,cropped.width, cropped.height, cropped.rgba,
                        template.width, template.height, template.rgba, options[6], options[4], options[7]))
                    require(found.size == 4)
                    Reply(id, expectedGeneration, found[0], operation, x = if (found[0] == Reply.SUCCESS) found[1] + options[0] else -1,
                        y = if (found[0] == Reply.SUCCESS) found[2] + options[1] else -1, scorePermille = found[3])
                }
                else -> {
                    val template = if (operation == Reply.GRAY_TEMPLATE) decode(requireNotNull(target)) else null
                    val response = adapter.dispatch(id, if (operation == Reply.GRAY_TEMPLATE) 5108 else 6101,
                        image.direct(), image.metadata(), template?.direct(), template?.metadata() ?: intArrayOf(), options.take(5).toIntArray(), deadlineNanos = work.deadline)
                    decodeNativePreviewReply(id, expectedGeneration, operation, response)
                }
            }
            gate.status(work, generation())?.let { failure(it) } ?: result
        } catch (_: Exception) {
            failure(gate.status(work, generation()) ?: Reply.INVALID_INPUT)
        } catch (_: LinkageError) {
            failure(Reply.ENGINE_ERROR).copy(text = "VISION_LIBRARY_UNAVAILABLE")
        } finally {
            expiry?.cancel(false)
            gate.finish(work)
        }
    } }

    private fun decode(file: ParcelFileDescriptor): Image {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFileDescriptor(file.fileDescriptor, null, bounds)
        require(bounds.outWidth in 1..4096 && bounds.outHeight in 1..4096 && bounds.outWidth.toLong() * bounds.outHeight <= 4_194_304)
        Os.lseek(file.fileDescriptor, 0, OsConstants.SEEK_SET)
        val bitmap = requireNotNull(BitmapFactory.decodeFileDescriptor(file.fileDescriptor, null,
            BitmapFactory.Options().apply { inScaled = false; inPreferredConfig = Bitmap.Config.ARGB_8888 }))
        try {
            val colors = IntArray(bitmap.width * bitmap.height)
            bitmap.getPixels(colors, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
            val rgba = ByteArray(colors.size * 4)
            colors.forEachIndexed { i, color ->
                rgba[i * 4] = (color ushr 16).toByte(); rgba[i * 4 + 1] = (color ushr 8).toByte()
                rgba[i * 4 + 2] = color.toByte(); rgba[i * 4 + 3] = (color ushr 24).toByte()
            }
            return Image(bitmap.width, bitmap.height, rgba)
        } finally { bitmap.recycle() }
    }

    private fun crop(image: Image, options: IntArray): Image {
        if (options[0] == 0 && options[1] == 0 && options[2] == image.width && options[3] == image.height) return image
        val width = options[2] - options[0]; val height = options[3] - options[1]
        val bytes = ByteArray(width * height * 4)
        for (y in 0 until height) {
            val offset = ((options[1] + y) * image.width + options[0]) * 4
            image.rgba.copyInto(bytes, y * width * 4, offset, offset + width * 4)
        }
        return Image(width, height, bytes)
    }

    override fun close() {
        gate.close()?.let { cancel(it.id, it.generation) }
        deadlines.shutdownNow(); adapter.close()
    }
    companion object { const val MAX_FILE_BYTES = 24L * 1024 * 1024; const val MAX_DICTIONARY_BYTES = 8L * 1024 * 1024 }
}

internal fun decodeNativePreviewReply(id: Long, generation: Long, operation: Int, response: ByteArray): Reply {
    if (response.isEmpty() || response[0].toInt() != 0) {
        val code = if (response.size in 2..65) com.autoscript.runtime.api.RecognitionPreviewErrors.accepted(
            response.copyOfRange(1, response.size).toString(Charsets.US_ASCII)) else ""
        return Reply(id, generation, Reply.ENGINE_ERROR, operation, text = code)
    }
    val payload = ByteBuffer.wrap(response, 1, response.size - 1).order(ByteOrder.LITTLE_ENDIAN)
    return when (operation) {
        Reply.GRAY_TEMPLATE -> if (payload.remaining() == 0) Reply(id, generation, Reply.NOT_FOUND, operation) else {
            require(payload.remaining() == 10)
            Reply(id, generation, Reply.SUCCESS, operation, x = payload.int, y = payload.int, scorePermille = payload.short.toInt() and 0xFFFF)
        }
        Reply.ALPHANUMERIC -> {
            require(payload.remaining() in 2..1026)
            val score = payload.short.toInt() and 0xFFFF
            val bytes = ByteArray(payload.remaining()).also(payload::get)
            val text = bytes.toString(Charsets.US_ASCII)
            require(text.all { it in 'A'..'Z' || it in 'a'..'z' || it in '0'..'9' } && score in 0..1000)
            Reply(id, generation, if (text.isEmpty()) Reply.NOT_FOUND else Reply.SUCCESS, operation, text, scorePermille = score)
        }
        else -> error("unsupported native preview")
    }
}
