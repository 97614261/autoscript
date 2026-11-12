package com.autoscript.runtime.service

import org.junit.Assert.*
import org.junit.Test

class VisionSearchPolygonTest {
    private fun options(vararg points: Int) = intArrayOf(0,0,4,4,900,VisionSearchPolygon.OPTIONS_MARKER,points.size/2)+points

    @Test fun fillMatchesPixelCentresAndTemplateOriginsMatchExhaustiveGeometry() {
        for (shape in listOf(options(0,0,4,0,0,4), options(0,0,4,0,4,1,1,1,1,4,0,4))) {
            val polygon=requireNotNull(VisionSearchPolygon.fromOptions(shape))
            for (w in 1..4) for (h in 1..4) {
                val origins=polygon.templateOrigins(w,h) {}
                for (y in 0..4) for (x in 0..4) assertEquals("$x,$y/$w,$h",
                    polygon.containsRect(x,y,w,h),origins.spans(y).any {x in it})
            }
        }
        val triangle=requireNotNull(VisionSearchPolygon.fromOptions(options(0,0,4,0,0,4)))
        assertEquals("1110110010000000",(0..3).joinToString("") {y->(0..3).joinToString("") {x->if(triangle.spans(y).any {x in it})"1" else "0"}})
    }

    @Test fun malformedShapesAndCancellationAreRejectedWhileOldOptionsRemainValid() {
        assertNull(VisionSearchPolygon.fromOptions(intArrayOf(0,0,4,4,900)))
        for (shape in listOf(options(0,0,4,4,0,4,4,0),options(0,0,4,0,2,0,0,4),options(0,0,5,0,0,4),options(0,0,4,0,0,0))) {
            assertThrows(IllegalArgumentException::class.java) {VisionSearchPolygon.fromOptions(shape)}
        }
        assertThrows(IllegalStateException::class.java) {VisionSearchPolygon.fromOptions(options(0,0,4,0,0,4)) {error("cancelled")}}
        val polygon=requireNotNull(VisionSearchPolygon.fromOptions(options(0,0,4,0,0,4)))
        assertThrows(IllegalStateException::class.java) {polygon.templateOrigins(2,2) {error("cancelled")}}
    }
}
