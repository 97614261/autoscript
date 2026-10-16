package com.autoscript.studio

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RecognitionPreviewOwnershipTest {
    @Test fun publishedPreviewIsNotRecycledByAnEffectReadingMutableState() {
        val source = File("src/main/java/com/autoscript/studio/image/VisualImageRecognitionDialog.kt").readText()
        val loader = source.substringAfter("private fun recognitionPreview(").substringBefore("internal val visualRecognitionKinds")
        assertFalse(loader.contains("preview.preview?.bitmap?.recycle()"))
        assertTrue(loader.contains("prepared?.recycle()"))
        assertTrue(loader.contains("while (max(bounds.outWidth, bounds.outHeight) / sample > 768)"))
    }
}
