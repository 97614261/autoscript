package com.autoscript.studio

import com.autoscript.project.store.ProjectMaskPoint
import org.junit.Assert.*
import org.junit.Test

class ImageToolbarActionsTest {
    private val triangle = listOf(ProjectMaskPoint(10,10),ProjectMaskPoint(40,10),ProjectMaskPoint(10,35))

    @Test fun polygonCanBeSavedWithoutChangingToCropMode() {
        val selected=polygonToolSelection(triangle,ImageToolSelection())
        assertNull(imageSelectionProblem(selected,80,60))
        assertEquals(selected,selected.clearedFor(ImageToolMode.CROP))
        assertEquals(selected,selected.clearedFor(ImageToolMode.REGION))
        assertEquals(ImageToolCodeGen.Roi(10,10,40,35),selected.box)
    }

    @Test fun incompleteCrossedAndOutsideShapesStayEditableButCannotBeSaved() {
        val two=triangle.take(2)
        assertEquals("再加1个顶点",imageSelectionProblem(polygonToolSelection(two,ImageToolSelection()),80,60))
        val crossed=listOf(ProjectMaskPoint(10,10),ProjectMaskPoint(40,35),ProjectMaskPoint(10,35),ProjectMaskPoint(40,10))
        assertNotNull(imageSelectionProblem(polygonToolSelection(crossed,ImageToolSelection()),80,60))
        assertNotNull(imageSelectionProblem(polygonToolSelection(triangle,ImageToolSelection()),20,20))
        assertNotNull(imageSelectionProblem(ImageToolSelection(box=ImageToolCodeGen.Roi(0,0,0,10)),80,60))
    }

    @Test fun stalePolygonBoundsNeverSilentlySaveAnotherRectangle() {
        assertNotNull(imageSelectionProblem(ImageToolSelection(box=ImageToolCodeGen.Roi(0,0,40,35),polygon=triangle),80,60))
    }

    @Test fun saveWithoutARegionUsesTheCurrentImageEvenAfterOnlyPickingColors() {
        val full = ImageSaveTarget(ImageToolCodeGen.Roi(0, 0, 80, 60), emptyList())
        assertEquals(full, imageSaveTarget(ImageToolSelection(), 80, 60))
        assertEquals(full, imageSaveTarget(ImageToolSelection(points = listOf(ImageToolCodeGen.PickedPoint(5, 8, 0xFFFFFF))), 80, 60))
        assertEquals(ImageSaveTarget(ImageToolCodeGen.Roi(0, 0, 20, 10), emptyList()),
            imageSaveTarget(ImageToolSelection(), 20, 10))
        assertNull(imageSaveTarget(ImageToolSelection(), 0, 60))
    }

    @Test fun saveKeepsTheExactRectangleOrPolygonWithoutFallingBackOnInvalidShapes() {
        val rectangle = ImageToolSelection(box = ImageToolCodeGen.Roi(4, 7, 16, 28))
        assertEquals(ImageSaveTarget(requireNotNull(rectangle.box), emptyList()), imageSaveTarget(rectangle, 80, 60))
        val polygon = polygonToolSelection(triangle, ImageToolSelection())
        assertEquals(ImageSaveTarget(requireNotNull(polygon.box), triangle), imageSaveTarget(polygon, 80, 60))
        assertNull(imageSaveTarget(polygonToolSelection(triangle.take(2), ImageToolSelection()), 80, 60))
        assertNull(imageSaveTarget(polygon.copy(box = rectangle.box), 80, 60))
        assertNull(imageSaveTarget(rectangle, 10, 10))
        val crossed = listOf(ProjectMaskPoint(10,10), ProjectMaskPoint(40,35), ProjectMaskPoint(10,35), ProjectMaskPoint(40,10))
        assertNull(imageSaveTarget(polygonToolSelection(crossed, ImageToolSelection()), 80, 60))
    }

    @Test fun explicitWholeImageSaveDoesNotApplyAPolygonEvenIfItHasFullImageBounds() {
        val fullBounds = listOf(ProjectMaskPoint(0,0), ProjectMaskPoint(80,0), ProjectMaskPoint(0,60))
        val selection = polygonToolSelection(fullBounds, ImageToolSelection())
        assertEquals(fullBounds, imageSaveTarget(selection, 80, 60)?.polygon)
        assertEquals(ImageSaveTarget(ImageToolCodeGen.Roi(0,0,80,60), emptyList()),
            imageSaveTarget(selection, 80, 60, wholeImage = true))
        assertNotNull(imageSaveTarget(polygonToolSelection(fullBounds.take(2), ImageToolSelection()), 80, 60, wholeImage = true))
    }

    @Test fun pendingSaveTargetOwnsItsPolygonCoordinates() {
        val points = triangle.toMutableList()
        val target = requireNotNull(imageSaveTarget(polygonToolSelection(points, ImageToolSelection()), 80, 60))
        points.clear()
        assertEquals(triangle, target.polygon)
    }

    @Test fun changingShapeAndUndoKeepsOneCompleteGesturePerEntry() {
        val history=ImagePolygonHistory(emptyList())
        triangle.indices.forEach {assertTrue(history.publish(triangle.take(it+1)))}
        history.synchronize(triangle)
        assertTrue(history.canUndo)
        history.undo();assertEquals(triangle.take(2),history.points)
        history.redo();assertEquals(triangle,history.points)
        history.publish(triangle);history.undo();assertEquals(triangle.take(2),history.points)
        assertFalse(history.publish(listOf(triangle[0],triangle[0])))
        assertEquals(triangle.take(2),history.points)
    }

    @Test fun redoIsDiscardedOnNewEditAndExternalSourceDoesNotRestoreOldVertices() {
        val history=ImagePolygonHistory(triangle)
        history.publish(emptyList());history.undo()
        history.publish(triangle.take(2));assertFalse(history.canRedo)
        history.synchronize(emptyList())
        assertFalse(history.canUndo);assertFalse(history.canRedo);assertTrue(history.points.isEmpty())
    }

    @Test fun historyAndVerticesRemainBounded() {
        val history=ImagePolygonHistory(emptyList())
        repeat(50) {history.publish(listOf(ProjectMaskPoint(it,10)))}
        var undone=0
        while(history.canUndo) {history.undo();undone++}
        assertEquals(32,undone)
        assertFalse(history.publish(List(129) {ProjectMaskPoint(it,20)}))
    }

    @Test fun allPreviousProcessingAndRecognitionActionsRemainReachableFromStrips() {
        val keys=(imageQuickProcessing+imageQuickTests).map {it.first}
        assertEquals(keys.size,keys.toSet().size)
        assertTrue(keys.containsAll(listOf("crop","keep","ignore","gray","filter","threshold","mask","cleanup","resize",
            "rotate270","rotate90","rotate180","flipX","flipY","recipe","coordinate","insert","saveAll","match","ocr","glyph")))
    }
}
