package com.autoscript.runtime.service

import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import java.nio.ByteBuffer

/** OpenCV matching source-set conversion. ONNX has no reference to this class. */
internal object NativeCvBuffers {
    // The Android AAR ships libopencv_java4.so. Core's generated desktop name is opencv_java4100.
    private val loaded by lazy { System.loadLibrary("opencv_java4"); Core.setNumThreads(1); true }
    fun load() { check(loaded) }
    fun image(pixels: ByteBuffer, metadata: IntArray): Mat {
        validateVisionBuffer(pixels.capacity(), pixels.remaining(), pixels.isDirect, metadata)
        return Mat(metadata[1], metadata[0], CvType.CV_8UC4, pixels.asReadOnlyBuffer(), metadata[2].toLong())
    }
}
