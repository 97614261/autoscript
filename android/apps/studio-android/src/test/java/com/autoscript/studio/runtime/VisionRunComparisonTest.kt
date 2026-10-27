package com.autoscript.studio

import org.junit.Assert.*
import org.junit.Test

class VisionRunComparisonTest {
    private val metadata = mapOf("projectId" to "project", "abi" to "x86_64", "androidApi" to "32",
        "device" to "synthetic", "screen" to "720x1280", "design" to "720x1280", "status" to "stopped")
    private val parameters = mapOf("frame" to "[720,1280]", "roi" to "[149,407,546,879]", "templateCount" to "1",
        "templateSizes" to "[[200,122]]", "templateFingerprintVersion" to "sha256-rgba-v1", "templateFingerprints" to "[\"${"a".repeat(64)}\"]",
        "tolerance" to "0", "similarity" to "900", "direction" to "0", "comparisonBudget" to "1000000000", "timeoutMs" to "5000")
    private fun sample(time: Double = 10.0, status: String = "found", configuration: String = "frame=[720,1280]", count: Int = 300,
        meta: Map<String, String> = metadata, unit: String = "ms") = VisionRunComparison.Sample(meta,
        VisionRunStatistics.Report(listOf(VisionRunStatistics.Group("rust-rgba-first", configuration, status, count, time, time * 2, time * 3, parameters, unit)), 0, 0, 0))

    @Test fun sameConditionsCompareAllPercentilesAndCounts() {
        val report = VisionRunComparison.compare(sample(), sample(8.0, count = 455))
        assertNull(report.blocked)
        val row = report.rows.single()
        assertEquals("-20.0%", row.change(row.before.p50Ms, row.after.p50Ms))
        assertTrue(report.text().contains("样本 300 → 455"))
        assertTrue(report.text().contains("P99 30.00 → 24.00"))
        assertTrue(report.warnings.any { it.contains("模板内容") })
    }
    @Test fun differentEnvironmentOrMissingFactsBlocksComparison() {
        for (key in listOf("projectId", "abi", "androidApi", "device", "screen", "design")) {
            assertNotNull(VisionRunComparison.compare(sample(), sample(meta = metadata + (key to "different"))).blocked)
            assertNotNull(VisionRunComparison.compare(sample(), sample(meta = metadata - key)).blocked)
        }
    }
    @Test fun conditionsOutcomesAndUnitsNeverMix() {
        for (other in listOf(sample(configuration = "different-roi"), sample(status = "cancelled"), sample(unit = "count"))) {
            val report = VisionRunComparison.compare(sample(), other)
            assertTrue(report.rows.isEmpty())
            assertNotNull(report.blocked)
        }
    }
    @Test fun lossesRotationRunningAndRootChangesStayVisible() {
        val report = VisionRunComparison.compare(sample(meta = metadata + mapOf("droppedRecords" to "1", "evictedEventRecords" to "2000")),
            sample(meta = metadata + mapOf("writerError" to "true", "rootSessionChanged" to "true", "status" to "running")))
        assertTrue(report.warnings.any { it.contains("保留窗口") })
        assertTrue(report.warnings.any { it.contains("不完整") })
        assertTrue(report.warnings.any { it.contains("仍在运行") })
        assertTrue(report.warnings.any { it.contains("会话") })
    }
    @Test fun zeroBaselineNeverProducesInfinityAndSmallSamplesWarn() {
        val report = VisionRunComparison.compare(sample(0.0, count = 2), sample())
        assertEquals("—", report.rows.single().change(0.0, 10.0))
        assertTrue(report.warnings.any { it.contains("不足100") })
        assertFalse(report.text().contains("Infinity"))
    }
    @Test fun missingInputParametersAreNotInventedFromEqualDimensions() {
        val complete = sample()
        val old = complete.copy(report = complete.report.copy(groups = complete.report.groups.map { it.copy(parameters = it.parameters - "templateSizes") }))
        val report = VisionRunComparison.compare(old, complete)
        assertTrue(report.rows.isEmpty())
        assertTrue(report.warnings.any { it.contains("缺少输入") })
    }
    @Test fun oversizedClipboardReportIsRejectedRatherThanSilentlyTruncated() {
        val report = VisionRunComparison.compare(sample(), sample(8.0))
        assertEquals(report.text(), report.clipboardText())
        val large = report.copy(rows = List(1000) { report.rows.single() })
        assertThrows(IllegalArgumentException::class.java) { large.clipboardText() }
    }
    @Test fun sameSizeDifferentContentsAndOldMissingFingerprintsAreNotCompared() {
        val original = sample()
        for (fields in listOf(parameters - "templateFingerprints",
            parameters + ("templateFingerprints" to "[\"${"b".repeat(64)}\"]"),
            parameters + ("templateFingerprintVersion" to "unknown"))) {
            val changed = original.copy(report = original.report.copy(groups = original.report.groups.map { it.copy(parameters = fields) }))
            assertTrue(VisionRunComparison.compare(original, changed).rows.isEmpty())
        }
        assertFalse(VisionRunComparison.hasTemplateIdentity(parameters + ("templateCount" to "2")))
        assertFalse(VisionRunComparison.hasTemplateIdentity(parameters + ("templateFingerprints" to "[1]")))
    }
    @Test fun regionalCaptureComparisonRequiresActualBoundsAndReadbackMode() {
        val fields = mapOf("frame" to "[230,152]", "captureBackend" to "surface-control", "captureTransport" to "authenticated-stream",
            "captureRegion" to "[10,20,240,172]", "captureReadback" to "region")
        fun region(parameters: Map<String, String>) = VisionRunComparison.Sample(metadata,
            VisionRunStatistics.Report(listOf(VisionRunStatistics.Group("capture-region-host-roundtrip",
                parameters.entries.sortedBy { it.key }.joinToString(), "completed", 100, 8.0, 12.0, 20.0, parameters, "ms")), 0, 0, 0))
        assertEquals(1, VisionRunComparison.compare(region(fields), region(fields)).rows.size)
        for (key in listOf("captureRegion", "captureReadback"))
            assertTrue(VisionRunComparison.compare(region(fields - key), region(fields - key)).rows.isEmpty())
        assertTrue(VisionRunComparison.compare(region(fields), region(fields + ("captureReadback" to "full-fallback"))).rows.isEmpty())
        assertTrue(VisionRunComparison.compare(region(fields), region(fields + ("captureRegion" to "[20,20,250,172]"))).rows.isEmpty())
    }
}
