package com.autoscript.runtime.service

import android.graphics.Bitmap
import android.graphics.Matrix
import android.os.Build
import android.os.IBinder
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.IntBuffer

/** Fixed privileged entry, separate from touch injection. No Context or shell reaches Lua. */
internal object RootCaptureBridge {
    @JvmStatic
    fun main(arguments: Array<String>) {
        if (arguments.isNotEmpty()) return
        val capture = runCatching {
            if (Build.VERSION.SDK_INT in 24..28) LegacyRootDisplayCapture() else ModernRootDisplayCapture()
        }.getOrNull() ?: return
        val input = DataInputStream(BufferedInputStream(System.`in`))
        val output = DataOutputStream(BufferedOutputStream(System.out, 64 * 1024))
        val transferBuffer = RootCaptureTransferBuffer()
        try {
            output.writeInt(0x41534331) // ASC1; all integer headers are big endian.
            output.flush()
            while (true) {
                val command = try { input.readInt() } catch (_: EOFException) { break }
                if (command !in 1..3) { output.writeInt(1); output.flush(); break }
                val region = if (command == 3) RootCaptureRegion.read(input) else null
                val before = if (region != null) RootCaptureDisplayGeometry.read() else null
                if (region != null) check(region.matchesScreen(requireNotNull(before)))
                val timings = if (command != 1) RootCaptureStageTimings() else null
                val bitmap = capture.capture(timings)
                if (bitmap == null) { output.writeInt(3); output.flush(); break }
                try {
                    writeBitmap(bitmap, region, before, output, transferBuffer, timings)
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

    private fun writeBitmap(bitmap: Bitmap, region: RootCaptureRegion?, before: RootCaptureDisplayGeometry?,
        output: DataOutputStream, buffer: RootCaptureTransferBuffer, timings: RootCaptureStageTimings?) {
        if (region != null) {
            check(region.matchesScreen(requireNotNull(before)))
            check(bitmap.width == region.screenWidth && bitmap.height == region.screenHeight)
            check(before == RootCaptureDisplayGeometry.read())
        }
        val width = region?.width ?: bitmap.width
        val height = region?.height ?: bitmap.height
        val size = checkedRootCaptureBytes(width, height)
        output.writeInt(0)
        output.writeInt(width)
        output.writeInt(height)
        output.writeInt(width * 4)
        output.writeInt(2) // BGRA8888, independent of Java/native byte order.
        output.writeInt(size)
        if (region != null) {
            output.writeInt(region.screenWidth)
            output.writeInt(region.screenHeight)
            output.writeInt(requireNotNull(before).rotation)
            output.writeInt(1) // Region getPixels/transfer, not source-region system capture.
        }
        writeRootCapturePixels(width, height, output, buffer, timings) { pixels, y, rows ->
            bitmap.getPixels(pixels, 0, width, region?.left ?: 0, (region?.top ?: 0) + y, width, rows)
        }
        if (region != null) check(before == RootCaptureDisplayGeometry.read())
    }
}

internal data class RootCaptureRegion(val screenWidth: Int, val screenHeight: Int,
    val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val width get() = right - left
    val height get() = bottom - top
    init {
        checkedRootCaptureBytes(screenWidth, screenHeight)
        require(left >= 0 && top >= 0 && left < right && top < bottom && right <= screenWidth && bottom <= screenHeight)
    }
    fun matchesScreen(geometry: RootCaptureDisplayGeometry) = screenWidth == geometry.width && screenHeight == geometry.height
    companion object {
        fun read(input: DataInputStream) = RootCaptureRegion(input.readInt(), input.readInt(), input.readInt(),
            input.readInt(), input.readInt(), input.readInt())
    }
}

internal data class RootCaptureDisplayGeometry(val width: Int, val height: Int, val rotation: Int) {
    companion object {
        private val reader by lazy { Reader() }
        fun read(): RootCaptureDisplayGeometry = reader.read()
    }

    private class Reader {
        private val type = Class.forName("android.hardware.display.DisplayManagerGlobal")
        private val manager = type.getMethod("getInstance").invoke(null)
        private val getInfo = type.getMethod("getDisplayInfo", Integer.TYPE)
        private val infoType = Class.forName("android.view.DisplayInfo")
        private val width = infoType.getField("logicalWidth")
        private val height = infoType.getField("logicalHeight")
        private val rotation = infoType.getField("rotation")

        fun read(): RootCaptureDisplayGeometry {
            val info = requireNotNull(getInfo.invoke(manager, 0))
            return RootCaptureDisplayGeometry(width.getInt(info), height.getInt(info), rotation.getInt(info))
                .also { require(it.rotation in 0..3) }
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
internal class RootCaptureTransferBuffer(
    private val maximumPixels: Int = 16_384,
    private val maximumRows: Int = 32,
) {
    init { require(maximumPixels in 16_384..65_536 && maximumRows in 32..128) }
    var pixels = IntArray(0)
        private set
    var bytes = ByteArray(0)
        private set
    private var encoded: IntBuffer? = null

    fun encode(count: Int) {
        require(count in 0..pixels.size)
        val target = requireNotNull(encoded)
        target.clear()
        target.put(pixels, 0, count)
    }

    fun prepare(width: Int, height: Int): Int {
        checkedRootCaptureBytes(width, height)
        val rows = minOf(maximumRows, (maximumPixels / width).coerceAtLeast(1), height)
        val count = width * rows
        if (pixels.size != count) {
            pixels = IntArray(count)
            bytes = ByteArray(count * 4)
            // ARGB integers in explicit little endian are BGRA bytes, including alpha.
            encoded = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asIntBuffer()
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
        buffer.encode(count)
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

internal fun legacyRootCaptureMethod(surface: Class<*>, bitmap: Class<*>): java.lang.reflect.Method =
    surface.getMethod("screenshot", Integer.TYPE, Integer.TYPE).also {
        require(java.lang.reflect.Modifier.isStatic(it.modifiers) && it.returnType == bitmap)
    }

internal fun legacyRootCaptureRotationDegrees(rotation: Int): Float {
    require(rotation in 0..3)
    return if (rotation == 0) 0f else -90f * rotation
}

private interface RootDisplayCapture {
    fun capture(timings: RootCaptureStageTimings?): Bitmap?
}

private class LegacyRootDisplayCapture : RootDisplayCapture {
    private val screenshot = legacyRootCaptureMethod(Class.forName("android.view.SurfaceControl"), Bitmap::class.java)
    private val displayManagerClass = Class.forName("android.hardware.display.DisplayManagerGlobal")
    private val displayManager = displayManagerClass.getMethod("getInstance").invoke(null)
    private val getDisplayInfo = displayManagerClass.getMethod("getDisplayInfo", Integer.TYPE)
    private val displayInfoClass = Class.forName("android.view.DisplayInfo")
    private val rotation = displayInfoClass.getField("rotation")
    private val logicalWidth = displayInfoClass.getField("logicalWidth")
    private val logicalHeight = displayInfoClass.getField("logicalHeight")

    private fun geometry(): List<Int> {
        val info = requireNotNull(getDisplayInfo.invoke(displayManager, 0))
        return listOf(rotation.getInt(info), logicalWidth.getInt(info), logicalHeight.getInt(info))
    }

    override fun capture(timings: RootCaptureStageTimings?): Bitmap? {
        val started = System.nanoTime()
        val before = geometry()
        // Zero dimensions retain native resolution; no secure-layer option is requested.
        val source = screenshot.invoke(null, 0, 0) as? Bitmap ?: return null
        timings?.captureNs = System.nanoTime() - started
        val readbackStarted = System.nanoTime()
        return try {
            ownedRootCaptureReadback(
                acquire = { source },
                transform = { bitmap ->
                    checkedRootCaptureBytes(bitmap.width, bitmap.height)
                    check(before == geometry())
                    // Legacy screenshots are in natural orientation, like UiAutomation on API24-28.
                    val degrees = legacyRootCaptureRotationDegrees(before[0])
                    val result = if (degrees != 0f) Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height,
                        Matrix().apply { postRotate(degrees) }, false)
                    else if (bitmap.config == Bitmap.Config.ARGB_8888) bitmap else bitmap.copy(Bitmap.Config.ARGB_8888, false)
                    try {
                        check(result.width == before[1] && result.height == before[2])
                        result
                    } catch (failure: Exception) {
                        if (result !== bitmap) result.recycle()
                        throw failure
                    }
                },
                recycle = Bitmap::recycle,
                closeHardware = {},
            )
        } finally {
            timings?.readbackNs = System.nanoTime() - readbackStarted
        }
    }
}

private class ModernRootDisplayCapture : RootDisplayCapture {
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

    override fun capture(timings: RootCaptureStageTimings?): Bitmap? {
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
