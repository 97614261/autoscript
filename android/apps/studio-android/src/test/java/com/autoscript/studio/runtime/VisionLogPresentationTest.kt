package com.autoscript.studio

import org.junit.Assert.*
import org.junit.Test

class VisionLogPresentationTest {
    @Test fun transferSheetDoesNotDisplaceExistingScreenshotMetrics() {
        val base = event()
        val detailed = base.dropLast(1) + """, "captureTransferUs":{"sendEncode":100,"sendWrite":200,"receiveRead":8000,"verify":900,"append":50,"packets":60}}"""
        val section = VisionLogPresentation.sections(VisionRunStatistics.parse(detailed)).single()
        val main = VisionLogPresentation.screenshotLines(listOf(section))
        val details = VisionLogPresentation.screenshotLines(listOf(section), transferOnly = true)
        assertEquals(4, main.mapNotNull { it.group }.size)
        assertEquals(6, details.mapNotNull { it.group }.size)
        assertEquals(section.rows.toSet(), (main + details).mapNotNull { it.group }.toSet())
        assertTrue(details.first().heading!!.contains("传输明细"))
        assertEquals(VisionLogPresentation.screenshotLines(VisionLogPresentation.sections(VisionRunStatistics.parse(base))), main)
    }
    private fun event(algorithm: String = "capture-host-roundtrip", backend: String = "root-surfacecontrol", node: String = "node-a", status: String = "completed") =
        """{"version":1,"kind":"vision","flowId":"main","nodeId":"$node","algorithm":"$algorithm","elapsedUs":33890,"status":"$status","frame":[720,1280],"captureBackend":"$backend","captureTransport":"authenticated-stream","captureStagesUs":{"acquisition":15760,"transferVerified":12000,"framePublish":100}}"""

    @Test fun captureRowsShareOneConditionWithoutMergingBackendsNodesOrStatuses() {
        val report = VisionRunStatistics.parse(listOf(event(), event(backend = "root-screencap-fallback"), event(node = "node-b"), event(status = "cancelled")).joinToString("\n"))
        val sections = VisionLogPresentation.sections(report)
        assertEquals(3, sections.size)
        assertEquals(5, sections.first().rows.size)
        assertTrue(sections.first().rows.any { it.status == "cancelled" && it.count == 1 })
        assertEquals(33.89, sections.first().rows.first().p50Ms, 0.0)
        assertEquals(15.76, sections.first().rows.first { it.algorithm == "capture-acquisition" }.p50Ms, 0.0)
        assertEquals("main", sections.first().parameters["flowId"])
    }

    @Test fun summariesHideIdsButDetailsKeepExactInputsAndUnknownAlgorithms() {
        val section = VisionLogPresentation.sections(VisionRunStatistics.parse(event())).single()
        assertEquals("720×1280 · 常驻 Root · 认证流式", VisionLogPresentation.summary(section.parameters))
        assertFalse(VisionLogPresentation.summary(section.parameters).contains("node-a"))
        assertTrue(VisionLogPresentation.details(section).contains("积木 ID：node-a"))
        assertEquals("custom-new", VisionLogPresentation.algorithm("custom-new"))
        assertEquals("预算超限", VisionLogPresentation.status("budget_exceeded"))
    }

    @Test fun panelIsBoundedInPortraitAndLandscape() {
        assertEquals(400, runtimeLogBodyHeightDp(800))
        assertEquals(160, runtimeLogBodyHeightDp(280))
        assertEquals(96, runtimeLogBodyHeightDp(100))
        assertEquals(718, runtimeLogBodyHeightDp(800, fullScreen = true))
        assertEquals(198, runtimeLogBodyHeightDp(280, fullScreen = true))
    }

    @Test fun compactOutcomeHeadersNeverCombineCompletedAndCancelledSamples() {
        val section = VisionLogPresentation.sections(VisionRunStatistics.parse(event() + "\n" + event(status = "cancelled"))).single()
        val outcomes = VisionLogPresentation.outcomes(section, expanded = true)
        assertEquals(2, outcomes.size)
        assertTrue(outcomes.all { outcome -> outcome.rows.all { it.status == outcome.status } })
        assertEquals(4, outcomes.first { it.status == "completed" }.rows.size)
        assertEquals(1, outcomes.first { it.status == "cancelled" }.rows.size)
    }

    @Test fun screenshotSheetOnlyAssociatesUniqueCaptureWithSameNodeAndFrame() {
        val capture = VisionLogPresentation.sections(VisionRunStatistics.parse(event())).single()
        val match = capture.copy(configuration = "match", parameters = capture.parameters + ("roi" to "[1,2,3,4]"),
            rows = capture.rows.filter { it.algorithm == "capture-host-roundtrip" }.map { it.copy(algorithm = "rust-rgba-first", status = "found") })
        assertEquals(listOf(capture, match), VisionLogPresentation.screenshotSections(listOf(capture, match), match))
        assertEquals(listOf(capture), VisionLogPresentation.screenshotSections(listOf(capture, match), capture))
        val otherNode = capture.copy(configuration = "node-b", parameters = capture.parameters + ("nodeId" to "node-b"))
        val otherFrame = capture.copy(configuration = "frame-b", parameters = capture.parameters + ("frame" to "[1080,1920]"))
        assertEquals(listOf(match), VisionLogPresentation.screenshotSections(listOf(otherNode, otherFrame, match), match))
        val fallback = capture.copy(configuration = "fallback", parameters = capture.parameters + ("captureBackend" to "root-screencap-fallback"))
        assertEquals(listOf(match), VisionLogPresentation.screenshotSections(listOf(capture, fallback, match), match))
        val unidentified = match.copy(parameters = emptyMap())
        assertEquals(listOf(unidentified), VisionLogPresentation.screenshotSections(listOf(capture, unidentified), unidentified))
    }

    @Test fun screenshotSheetKeepsEveryMetricAndOutcomeWithoutSummingPercentiles() {
        val section = VisionLogPresentation.sections(VisionRunStatistics.parse(event() + "\n" + event(status = "cancelled"))).single()
        val lines = VisionLogPresentation.screenshotLines(listOf(section))
        assertEquals(section.rows.groupingBy { it }.eachCount(), lines.mapNotNull { it.group }.groupingBy { it }.eachCount())
        assertEquals(listOf("截图 · 完成", "截图 · 取消"), lines.mapNotNull { it.heading })
        assertEquals(26, VisionLogPresentation.screenshotPageCapacity(400, 1f))
        assertEquals(13, VisionLogPresentation.screenshotPageCapacity(400, 2f))
        assertEquals(1, VisionLogPresentation.screenshotPageCapacity(10, 1f))
        for (capacity in 1..26) {
            val pages = VisionLogPresentation.screenshotPages(lines, capacity)
            assertTrue(pages.all { it.size <= capacity })
            assertEquals(lines.mapNotNull { it.group }, pages.flatten().mapNotNull { it.group })
            if (capacity > 1) assertTrue(pages.all { it.first().heading != null })
        }
    }
}
