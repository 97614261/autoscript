package com.autoscript.studio

import org.junit.Assert.*
import org.junit.Test

class RunnerDependencyPlanTest {
    @Test fun compiledDemandsIgnoreUnusedDefaultDeclarationsAndRejectUnknownKinds() {
        val declarations = listOf("vision.opencv", "ocr.onnx", "screen.capture")
        assertEquals(RunnerTemplateFamily.FULL, RunnerDependencyPlan.fromCapabilities(declarations).family)
        assertEquals(RunnerTemplateFamily.BASE, RunnerDependencyPlan.fromCompiledRequirements(emptySet()).family)
        assertEquals(RunnerTemplateFamily.OPENCV, RunnerDependencyPlan.fromCompiledRequirements(setOf("opencv")).family)
        assertEquals(RunnerTemplateFamily.ONNX, RunnerDependencyPlan.fromCompiledRequirements(setOf("onnx")).family)
        assertEquals(RunnerTemplateFamily.FULL, RunnerDependencyPlan.fromCompiledRequirements(setOf("opencv", "onnx")).family)
        assertThrows(IllegalArgumentException::class.java) { RunnerDependencyPlan.fromCompiledRequirements(setOf("external")) }
        assertEquals(listOf("vision.opencv", "ocr.onnx", "screen.capture"), declarations)
    }
    @Test fun basicImagesDoNotImplyOpenCvOrOnnx() {
        val plan = RunnerDependencyPlan.fromCapabilities(listOf("screen.capture", "vision.pixel", "vision.template", "ocr.glyph"))
        assertEquals(RunnerTemplateFamily.BASE, plan.family)
        assertEquals(listOf("基础 Runner / Rust 图像"), plan.componentLabels)
    }

    @Test fun eachOptionalDeclarationSelectsItsOwnArtifactFamily() {
        assertEquals(RunnerTemplateFamily.OPENCV, RunnerDependencyPlan.fromCapabilities(listOf("vision.opencv")).family)
        val ocr = RunnerDependencyPlan.fromCapabilities(listOf("ocr.onnx"))
        assertEquals(RunnerTemplateFamily.ONNX, ocr.family)
        assertFalse(ocr.openCv)
        assertTrue(ocr.componentLabels.contains("字母数字模型"))
        assertEquals(RunnerTemplateFamily.FULL, RunnerDependencyPlan.fromCapabilities(listOf("vision.opencv", "ocr.onnx")).family)
    }

    @Test fun doesNotModifyExistingCapabilityDeclarationsOrTreatUnknownNamesAsInstalled() {
        val capabilities = mutableListOf("audio.control", "vision.opencv.unknown", "ocr.onnx.unknown")
        val original = capabilities.toList()
        assertEquals(RunnerTemplateFamily.BASE, RunnerDependencyPlan.fromCapabilities(capabilities).family)
        assertEquals(original, capabilities)
    }
}
