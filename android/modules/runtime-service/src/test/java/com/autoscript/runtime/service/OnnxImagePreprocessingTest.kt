package com.autoscript.runtime.service

import java.nio.ByteBuffer
import kotlin.math.*
import org.junit.Assert.*
import org.junit.Test

class OnnxImagePreprocessingTest {
    @Test fun preparedCoordinatesAreBitExactToScalarAcrossStrideCropAndScale() {
        val random=java.util.Random(148)
        val shapes=listOf(1 to 1,3 to 2,101 to 96,96 to 96,320 to 96,97 to 194,4000 to 200) +
            List(60) { val h=random.nextInt(150)+1; (random.nextInt(h*15)+1) to h }
        for((w,h) in shapes) for(format in 1..2) {
            val stride=(w+4)*4+12
            val bytes=ByteArray(stride*(h+4)).also(random::nextBytes)
            val pixels=ByteBuffer.allocateDirect(bytes.size).put(bytes).apply {flip()}
            val metadata=intArrayOf(w+4,h+4,stride,format)
            val options=intArrayOf(2,2,w+2,h+2,0)
            val expected=scalar(pixels,metadata,options)
            val actual=prepareOnnxImage(pixels,metadata,options)
            assertEquals(expected.width,actual.width)
            assertEquals(expected.paddedWidth,actual.paddedWidth)
            assertArrayEquals("$w x $h format=$format",expected.tensor.map {it.toBits()}.toIntArray(),actual.tensor.map {it.toBits()}.toIntArray())
            assertEquals(0,pixels.position())
        }
    }

    // Previous divide/clamp-per-pixel implementation, independent of prepared coordinate arrays.
    private fun scalar(pixels:ByteBuffer,metadata:IntArray,options:IntArray):OnnxImageInput {
        val roi=checkedVisionRect(metadata[0],metadata[1],options)
        val width=ceil(48.0*roi.width/roi.height).toInt().coerceAtLeast(1)
        val padded=max(320,width)
        val tensor=FloatArray(3*48*padded)
        val channels=if(metadata[3]==1) intArrayOf(2,1,0) else intArrayOf(0,1,2)
        val half=roi.width==width*2 && roi.height==96
        fun value(x:Int,y:Int,c:Int)=pixels.get((roi.y+y.coerceIn(0,roi.height-1))*metadata[2]+(roi.x+x.coerceIn(0,roi.width-1))*4+channels[c]).toInt() and 255
        for(y in 0 until 48) {
            val fy=((y+.5)*(roi.height.toDouble()/48)-.5).toFloat()
            val top=floor(fy).toInt();val fractionY=fy-top
            val wy0=round((1f-fractionY)*2048f).toInt();val wy1=round(fractionY*2048f).toInt()
            for(x in 0 until width) {
                val fx=((x+.5)*(roi.width.toDouble()/width)-.5).toFloat()
                var left=floor(fx).toInt();var fractionX=fx-left
                if(left<0) {left=0;fractionX=0f}
                if(left>=roi.width-1) {left=roi.width-1;fractionX=0f}
                val wx0=round((1f-fractionX)*2048f).toInt();val wx1=round(fractionX*2048f).toInt()
                for(c in 0..2) {
                    val sample=if(half) (value(2*x,2*y,c)+value(2*x+1,2*y,c)+value(2*x,2*y+1,c)+value(2*x+1,2*y+1,c)+2)/4
                    else {
                        val upper=value(left,top,c)*wx0+value(left+1,top,c)*wx1
                        val lower=value(left,top+1,c)*wx0+value(left+1,top+1,c)*wx1
                        (((upper ushr 4)*wy0 ushr 16)+((lower ushr 4)*wy1 ushr 16)+2) ushr 2
                    }
                    tensor[c*48*padded+y*padded+x]=sample/127.5f-1f
                }
            }
        }
        return OnnxImageInput(width,padded,tensor)
    }

    @Test fun rgbaAndBgraProduceTheSameBgrPlanesAndZeroPadding() {
        fun input(bytes: ByteArray, format: Int): OnnxImageInput {
            val pixels = ByteBuffer.allocateDirect(4).put(bytes).apply { flip() }
            val result = prepareOnnxImage(pixels, intArrayOf(1, 1, 4, format), intArrayOf(0, 0, 1, 1, 0))
            assertEquals(0, pixels.position())
            return result
        }
        val rgba = input(byteArrayOf(10, 20, 30, 0), 1)
        val bgra = input(byteArrayOf(30, 20, 10, -1), 2)
        assertEquals(48, rgba.width)
        assertEquals(320, rgba.paddedWidth)
        assertArrayEquals(rgba.tensor, bgra.tensor, 0f)
        assertEquals(30 / 127.5f - 1f, rgba.tensor[0], 0f)
        assertEquals(20 / 127.5f - 1f, rgba.tensor[48 * 320], 0f)
        assertEquals(10 / 127.5f - 1f, rgba.tensor[2 * 48 * 320], 0f)
        assertEquals(0f, rgba.tensor[48], 0f)
    }

    @Test fun preservesStrideRoiAndSourceWhileCheckingCancellation() {
        val pixels = ByteBuffer.allocateDirect(3 * 20)
        for (i in 0 until pixels.capacity()) pixels.put(i, 90.toByte())
        pixels.put(20 + 4, 10).put(20 + 5, 20).put(20 + 6, 30)
        val before = ByteArray(pixels.capacity()).also { pixels.duplicate().get(it) }
        val result = prepareOnnxImage(pixels, intArrayOf(4, 3, 20, 1), intArrayOf(1, 1, 2, 2, 0))
        assertEquals(30 / 127.5f - 1f, result.tensor[0], 0f)
        val after = ByteArray(pixels.capacity()).also { pixels.duplicate().get(it) }
        assertArrayEquals(before, after)
        assertThrows(IllegalStateException::class.java) {
            prepareOnnxImage(pixels, intArrayOf(4, 3, 20, 1), intArrayOf(1, 1, 2, 2, 0)) { error("cancelled") }
        }
    }

    @Test fun rejectsOverwideAndMalformedInputBeforeAllocation() {
        val pixels = ByteBuffer.allocateDirect(100 * 4)
        assertThrows(IllegalArgumentException::class.java) {
            prepareOnnxImage(pixels, intArrayOf(100, 1, 400, 1), intArrayOf(0, 0, 100, 1, 0))
        }
        assertThrows(IllegalArgumentException::class.java) {
            prepareOnnxImage(ByteBuffer.allocate(4), intArrayOf(1, 1, 4, 1), intArrayOf(0, 0, 1, 1, 0))
        }
    }
}
