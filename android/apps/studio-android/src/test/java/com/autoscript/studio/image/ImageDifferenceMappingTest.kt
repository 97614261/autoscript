package com.autoscript.studio

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CancellationException

class ImageDifferenceMappingTest {
    @Test fun diagnosticIgnoresTemplateAlphaAndDoesNotInventNearestSearchOrMutateInput() {
        val source=intArrayOf(0xff112233.toInt(),0xff113333.toInt(),0xffaabbcc.toInt())
        val template=intArrayOf(0xff112233.toInt(),0xff112233.toInt(),0)
        val result=analyzeImageDifference(source,template,0) {}
        assertEquals(2,result.compared);assertEquals(1,result.mismatched);assertEquals(500,result.scorePermille)
        assertEquals(17,result.maximumDelta);assertEquals(0,result.overlay[2]);assertEquals(0xff112233.toInt(),source[0])
        assertEquals(1000,analyzeImageDifference(source,template,17) {}.scorePermille)
        assertThrows(IllegalArgumentException::class.java) {analyzeImageDifference(source,IntArray(3),0) {}}
        assertThrows(CancellationException::class.java) {analyzeImageDifference(source,template,0) {throw CancellationException()}}
    }
    @Test fun geometryMatchesRustIntegerSamplingAndKeepsFileVersusScreenPolicySeparate() {
        val pipeline=ImageProcessingPipeline(8,6).append(ImagePixelOperation("crop",roi=ImageToolCodeGen.Roi(2,1,6,5)))
            .append(ImagePixelOperation("rotate90")).append(ImagePixelOperation("resize",8,8))
        assertEquals(4 to 3,pipeline.mapToOriginal(2,4))
        assertThrows(IllegalArgumentException::class.java) {pipeline.mapToOriginal(8,0)}
        assertEquals(2 to 4,ImageProcessingPipeline(8,6).append(ImagePixelOperation("flipY")).mapToOriginal(2,1))
    }
}
