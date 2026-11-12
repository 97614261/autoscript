package com.autoscript.studio

import com.autoscript.runtime.api.RecognitionPreviewReply as Reply
import org.junit.Assert.*
import org.junit.Test

class ImageWorkspaceConvenienceTest {
    private val source = Any()
    private val region = ImageToolCodeGen.Roi(10, 20, 110, 60)
    private fun draft() = ImageOcrDraft(Reply.LATIN_TEXT,"",GlyphPaletteDraft.from(0xFFFFFF,16,""),
        "650","2",true,listOf("bad","24","80","20"),source,region)

    @Test fun returnKeepsEvenUnfinishedInputButChangedImageResetsGeometry() {
        val value=draft()
        assertEquals(value.bounds,value.boundsFor(source,region.copy()))
        assertEquals(listOf("10","20","100","40"),value.boundsFor(Any(),region))
        assertEquals("650",value.threshold)
    }
    @Test fun newSelectionOverridesOldOcrRegionWithoutTouchingOtherSettings() {
        val value=draft()
        assertEquals(listOf("0","0","40","12"),value.boundsFor(source,ImageToolCodeGen.Roi(0,0,40,12)))
        assertEquals(listOf("bad","24","80","20"),value.bounds)
    }
    @Test fun workspaceDraftsNeverLeakIntoANewWorkspace() {
        val first=ImageWorkspaceDrafts().apply { ocr=draft();insertion=ImageInsertionDraft("a","b",true,false,mapOf("text" to "result")) }
        val second=ImageWorkspaceDrafts()
        assertNotNull(first.ocr);assertNull(second.ocr);assertNull(second.insertion);assertNull(second.match)
    }
    @Test fun fixedTemplateChecksBothAxesAndAcceptsExactFit() {
        assertNull(fixedTemplateSizeProblem(Reply.TEMPLATE,100,40,region))
        assertTrue(requireNotNull(fixedTemplateSizeProblem(Reply.TEMPLATE,101,40,region)).contains("101×40px"))
        assertTrue(requireNotNull(fixedTemplateSizeProblem(Reply.GRAY_TEMPLATE,90,41,region)).contains("未执行识别"))
        assertTrue(requireNotNull(fixedTemplateSizeProblem(Reply.TEMPLATE,-1,-1,region)).contains("无法读取"))
    }
    @Test fun fixedSizeRuleDoesNotRejectScalingOrFeatures() {
        assertNull(fixedTemplateSizeProblem(Reply.SCALED_TEMPLATE,200,80,region))
        assertNull(fixedTemplateSizeProblem(Reply.FEATURE_TEMPLATE,200,80,region))
    }
    @Test fun missGuidanceIsSpecificAndNotAnInventedCause() {
        fun message(operation:Int)=recognitionPreviewMessage(Reply(1,1,Reply.NOT_FOUND,operation))
        assertTrue(message(Reply.TEMPLATE).contains("不能确定具体原因"))
        assertTrue(message(Reply.GLYPH).contains("字库样本"))
        assertTrue(message(Reply.LATIN_TEXT).contains("单行文字"))
        assertFalse(message(Reply.TEMPLATE).contains("截图失败"))
        assertTrue(recognitionPreviewMessage(Reply(1,1,Reply.ENGINE_ERROR,Reply.TEMPLATE)).contains("引擎失败"))
    }
}
