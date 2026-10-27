package com.autoscript.studio

import com.autoscript.project.store.*
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CancellationException

class ImageAdvancedPixelsTest {
    @Test fun polygonMatchesRustGoldenForPixelCentresConcavityAndReversedWinding() {
        val triangle=listOf(ProjectMaskPoint(0,0),ProjectMaskPoint(4,0),ProjectMaskPoint(0,4))
        val concave=listOf(ProjectMaskPoint(0,0),ProjectMaskPoint(4,0),ProjectMaskPoint(4,1),ProjectMaskPoint(1,1),ProjectMaskPoint(1,4),ProjectMaskPoint(0,4))
        val goldens=listOf(triangle to "1110110010000000",concave to "1111100010001000")
        val source=IntArray(16) {0x80112233.toInt()}
        for((points,golden) in goldens) for(order in listOf(points,points.reversed())) {
            val operations=polygonSelectionOperations(4,4,order,crop=false,keep=true)
            val mask=requireNotNull(operations.single().mask)
            assertEquals(mask,ProjectImageMask.decode(mask.encode()))
            val result=applyImageMask(source,4,4,mask) {}
            assertEquals(golden,result.joinToString("") {if(it==0) "0" else "1"})
            result.filter {it!=0}.forEach {assertEquals(0x80112233.toInt(),it)}
            assertThrows(CancellationException::class.java) {var calls=0;applyImageMask(source,4,4,mask) {if(++calls==3) throw CancellationException()}}
        }
        assertArrayEquals(IntArray(16) {0x80112233.toInt()},source)
        val cropped=polygonSelectionOperations(4,4,listOf(ProjectMaskPoint(1,1),ProjectMaskPoint(4,1),ProjectMaskPoint(1,4)),crop=true,keep=true)
        assertEquals(listOf("mask","crop"),cropped.map {it.mode})
        assertEquals(3 to 3,cropped.last().dimensions(4,4))
        assertEquals(1,polygonSelectionOperations(4,4,triangle,crop=true,keep=false).size)
    }

    @Test fun polygonRejectsDegenerateGeometryAndExcessiveRasterWork() {
        for(points in listOf(listOf(ProjectMaskPoint(0,0),ProjectMaskPoint(1,1),ProjectMaskPoint(2,2)),
            listOf(ProjectMaskPoint(0,0),ProjectMaskPoint(0,0),ProjectMaskPoint(2,1)))) {
            assertThrows(IllegalArgumentException::class.java) {ProjectMaskStroke("polygon",true,0,points)}
        }
        val points=listOf(ProjectMaskPoint(0,0),ProjectMaskPoint(4096,0),ProjectMaskPoint(4096,1024),ProjectMaskPoint(0,1024))+(1..124).map {ProjectMaskPoint(it,0)}
        val expensive=ProjectMaskStroke("polygon",true,0,points)
        assertThrows(IllegalArgumentException::class.java) {ProjectImageMask(4096,1024,List(4) {expensive})}
        assertThrows(IllegalArgumentException::class.java) {ProjectImageMask(2,2,listOf(expensive))}
    }

    @Test fun maskGoldenRestoresSourceAndPreservesAlphaWithStrictGeometry() {
        val source=IntArray(9) {-1}
        val ignored=ProjectMaskStroke("rect",false,0,listOf(ProjectMaskPoint(0,0),ProjectMaskPoint(2,3)))
        val mask=ProjectImageMask(3,3,listOf(ignored))
        val output=processImagePixels(source,3,3,ImagePixelOperation("mask",mask=mask)) {}.argb
        assertArrayEquals(intArrayOf(0,0,-1,0,0,-1,0,0,-1),output)
        val restored=mask.copy(strokes=mask.strokes+ProjectMaskStroke("brush",true,1,listOf(ProjectMaskPoint(0,1))))
        assertEquals(-1,applyImageMask(source,3,3,restored) {}[3])
        assertArrayEquals(IntArray(9) {-1},source)
        assertEquals(mask,ProjectImageMask.decode(mask.encode()))
        assertThrows(IllegalArgumentException::class.java) {mask.validate(4,3)}
        assertThrows(IllegalArgumentException::class.java) {applyImageMask(source,3,3,ProjectImageMask(3,3,
            listOf(ProjectMaskStroke("rect",false,0,listOf(ProjectMaskPoint(0,0),ProjectMaskPoint(3,3)))))) {}}
        assertThrows(CancellationException::class.java) {applyImageMask(source,3,3,mask) {throw CancellationException()}}
    }
    @Test fun decimalNoiseAndMorphologyHaveExplicitIntegerSemantics() {
        val source=IntArray(25) {0xff000000.toInt()};for(index in listOf(0,6,7,11,12)) source[index]=-1
        val spec=ProjectImageCleanup(minArea=2)
        val cleaned=applyImageCleanup(source,5,5,"despeckle",spec) {}
        assertEquals(0xff000000.toInt(),cleaned[0]);assertEquals(-1,cleaned[6]);assertEquals(-1,source[0])
        assertArrayEquals(source,applyImageCleanup(source,5,5,"despeckle",spec.copy(minArea=1)) {})
        val square=IntArray(9) {-1}
        assertArrayEquals(intArrayOf(0xff000000.toInt(),0xff000000.toInt(),0xff000000.toInt(),0xff000000.toInt(),-1,
            0xff000000.toInt(),0xff000000.toInt(),0xff000000.toInt(),0xff000000.toInt()),applyImageCleanup(square,3,3,"erode",spec) {})
        assertArrayEquals(square,applyImageCleanup(IntArray(9) {if(it==4) -1 else 0xff000000.toInt()},3,3,"dilate",spec) {})
        assertEquals(spec,ProjectImageCleanup.decode(spec.encode()))
        assertThrows(IllegalArgumentException::class.java) {spec.validate("open",4096,1024)}
        assertThrows(IllegalArgumentException::class.java) {ImagePixelOperation("gray",cleanup=spec).dimensions(1,1)}
        assertThrows(CancellationException::class.java) {applyImageCleanup(source,5,5,"close",spec) {throw CancellationException()}}
    }
    @Test fun maskAndCleanupCompileIntoTheExistingOwnedFramePipeline() {
        val mask=ProjectImageMask(3,3,listOf(ProjectMaskStroke("rect",false,0,listOf(ProjectMaskPoint(0,0),ProjectMaskPoint(1,1)))))
        val steps=ImageProcessingPipeline(3,3).append(ImagePixelOperation("mask",mask=mask))
            .append(ImagePixelOperation("despeckle",cleanup=ProjectImageCleanup(minArea=1)))
        val variables=listOf(CalculationVariable("scratch",ProjectVariableType.IMAGE,"局部"),CalculationVariable("output",ProjectVariableType.IMAGE,"局部"))
        val blocks=imageProcessingBlocks(steps,null,"scratch","output",variables,emptyList())
        assertEquals(mask.encode(),blocks[1].second["mask"].asString)
        assertEquals(ProjectImageCleanup(minArea=1).encode(),blocks[3].second["cleanup"].asString)
        assertEquals(listOf("screen.capture","screen.transform","screen.release","screen.transform","screen.release"),blocks.map {it.first.kind})
    }
}
