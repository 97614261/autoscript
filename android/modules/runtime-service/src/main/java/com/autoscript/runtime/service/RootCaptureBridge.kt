package com.autoscript.runtime.service

import android.graphics.Bitmap
import android.os.IBinder
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.OutputStream

/** Fixed privileged entry, separate from touch injection. No Context or shell reaches Lua. */
internal object RootCaptureBridge {
    @JvmStatic
    fun main(arguments: Array<String>) {
        if (arguments.isNotEmpty()) return
        val capture = runCatching { RootDisplayCapture() }.getOrNull() ?: return
        val input = DataInputStream(BufferedInputStream(System.`in`))
        val output = DataOutputStream(BufferedOutputStream(System.out, 64 * 1024))
        val transferBuffer = RootCaptureTransferBuffer()
        try {
            output.writeInt(0x41534331) // ASC1; all integer headers are big endian.
            output.flush()
            while (true) {
                val command = try { input.readInt() } catch (_: EOFException) { break }
                if (command !in 1..2) { output.writeInt(1); output.flush(); break }
                val timings = if (command == 2) RootCaptureStageTimings() else null
                val bitmap = capture.capture(timings)
                if (bitmap == null) { output.writeInt(3); output.flush(); break }
                try {
                    val size = checkedRootCaptureBytes(bitmap.width, bitmap.height)
                    output.writeInt(0)
                    output.writeInt(bitmap.width)
                    output.writeInt(bitmap.height)
                    output.writeInt(bitmap.width * 4)
                    output.writeInt(2) // BGRA8888, independent of Java/native byte order.
                    output.writeInt(size)
                    writeRootCapturePixels(bitmap.width, bitmap.height, output, transferBuffer, timings) { pixels, y, rows ->
                        bitmap.getPixels(pixels, 0, bitmap.width, 0, y, bitmap.width, rows)
                    }
                    val flushStarted = System.nanoTime()
                    output.flush()
                    timings?.let {
                        it.writeNs += System.nanoTime() - flushStarted
                        it.writeTrailer(output)
                        output.flush()
                    }
                } finally { bitmap.recycle() }
            }
        } catch (_: Exception) {
            // EOF/error closes the session. Never print pixels, project data or reflection details.
        }
    }
}

internal fun checkedRootCaptureBytes(width: Int, height: Int): Int {
    require(width in 1..16_384 && height in 1..16_384)
    val length = width.toLong() * height * 4
    require(length <= 64L * 1024 * 1024)
    return length.toInt()
}

internal fun encodeRootCaptureRow(argb: IntArray, bgra: ByteArray) {
    require(bgra.size == argb.size * 4)
    encodeRootCapturePixels(argb, bgra, argb.size)
}

internal fun encodeRootCapturePixels(argb: IntArray, bgra: ByteArray, count: Int) {
    require(count in 0..argb.size && count <= bgra.size / 4)
    var index = 0
    while (index < count) {
        val pixel = argb[index]
        val offset = index * 4
        bgra[offset] = pixel.toByte()
        bgra[offset + 1] = (pixel ushr 8).toByte()
        bgra[offset + 2] = (pixel ushr 16).toByte()
        bgra[offset + 3] = (pixel ushr 24).toByte()
        index++
    }
}

/** Bounded scratch only. No captured frame or Bitmap survives a request. */
internal class RootCaptureTransferBuffer {
    var pixels = IntArray(0)
        private set
    var bytes = ByteArray(0)
        private set

    fun prepare(width: Int, height: Int): Int {
        checkedRootCaptureBytes(width, height)
        val rows = minOf(32, (16_384 / width).coerceAtLeast(1), height)
        val count = width * rows
        if (pixels.size != count) {
            pixels = IntArray(count)
            bytes = ByteArray(count * 4)
        }
        return rows
    }
}

internal fun writeRootCapturePixels(width: Int, height: Int, output: OutputStream,
    buffer: RootCaptureTransferBuffer, timings: RootCaptureStageTimings? = null,
    nanoTime: () -> Long = System::nanoTime, readPixels: (IntArray, Int, Int) -> Unit) {
    val blockRows = buffer.prepare(width, height)
    var y = 0
    while (y < height) {
        val rows = minOf(blockRows, height - y)
        val count = width * rows
        var started = if (timings != null) nanoTime() else 0L
        readPixels(buffer.pixels, y, rows)
        if (timings != null) { timings.pixelReadNs += nanoTime() - started; started = nanoTime() }
        encodeRootCapturePixels(buffer.pixels, buffer.bytes, count)
        if (timings != null) { timings.encodeNs += nanoTime() - started; started = nanoTime() }
        output.write(buffer.bytes, 0, count * 4)
        if (timings != null) timings.writeNs += nanoTime() - started
        y += rows
    }
}

/** Per-request numbers only; pipe receive time overlaps these phases and must not be added. */
internal class RootCaptureStageTimings {
    var captureNs = 0L
    var readbackNs = 0L
    var pixelReadNs = 0L
    var encodeNs = 0L
    var writeNs = 0L

    fun writeTrailer(output: DataOutputStream) {
        for (value in longArrayOf(captureNs, readbackNs, pixelReadNs, encodeNs, writeNs)) {
            val micros = value / 1_000
            require(micros in 0..10_000_000)
            output.writeLong(micros)
        }
    }
}

private class RootDisplayCapture {
    private val surface = Class.forName("android.view.SurfaceControl")
    private val captureClass = runCatching { Class.forName("android.window.ScreenCapture") }.getOrDefault(surface)
    private val argsClass = Class.forName(captureClass.name + "\$DisplayCaptureArgs")
    private val builderClass = Class.forName(argsClass.name + "\$Builder")
    private val builder = builderClass.getConstructor(IBinder::class.java)
    private val build = builderClass.getMethod("build")
    private val captureDisplay = captureClass.getMethod("captureDisplay", argsClass)
    private val tokenClass = if (runCatching { surface.getMethod("getPhysicalDisplayIds") }.isSuccess) surface
        else Class.forName("com.android.server.display.DisplayControl")
    private val displayIds = tokenClass.getMethod("getPhysicalDisplayIds")
    private val displayToken = tokenClass.getMethod("getPhysicalDisplayToken", java.lang.Long.TYPE)

    fun capture(timings: RootCaptureStageTimings?): Bitmap? {
        val captureStarted = System.nanoTime()
        val ids = displayIds.invoke(null) as LongArray
        val id = ids.firstOrNull() ?: return null
        val token = displayToken.invoke(null, id) as? IBinder ?: return null
        val args = build.invoke(builder.newInstance(token))
        // Defaults: full current display, original scale, no secure/protected layers.
        val result = captureDisplay.invoke(null, args) ?: return null
        timings?.captureNs = System.nanoTime() - captureStarted
        val readbackStarted = System.nanoTime()
        val hardware = result.javaClass.getMethod("getHardwareBuffer").invoke(result) as AutoCloseable
        return try {
            ownedRootCaptureReadback(
                acquire = { result.javaClass.getMethod("asBitmap").invoke(result) as? Bitmap },
                transform = { bitmap ->
                    checkedRootCaptureBytes(bitmap.width, bitmap.height)
                    // getPixels keeps the same colors; an already-software bitmap needs no copy.
                    if (bitmap.config == Bitmap.Config.ARGB_8888) bitmap
                    else bitmap.copy(Bitmap.Config.ARGB_8888, false)
                },
                recycle = Bitmap::recycle,
                closeHardware = hardware::close,
            )
        } finally {
            timings?.readbackNs = System.nanoTime() - readbackStarted
        }
    }
}
