package com.autoscript.studio

import org.junit.Assert.*
import org.junit.Test

class VisionRunStatisticsTest {
    @Test fun transferDetailsAreOptionalValidatedAndNeverInventedForOldEvents() {
        val base = """{"version":1,"kind":"vision","algorithm":"capture-host-roundtrip","elapsedUs":30000,"status":"completed"}"""
        val details = base.dropLast(1) + """, "captureTransferUs":{"sendEncode":100,"sendWrite":200,"receiveRead":8000,"verify":900,"append":50,"packets":60}}"""
        val report = VisionRunStatistics.parse("$base\n$details")
        assertEquals(0, report.malformed)
        assertEquals(2, report.groups.first { it.algorithm == "capture-host-roundtrip" }.count)
        val verify = report.groups.first { it.algorithm == "capture-transfer-verify" }
        assertEquals(1, verify.count); assertEquals(0.9, verify.p50Ms, 0.0)
        val packets = report.groups.first { it.algorithm == "capture-transfer-packets" }
        assertEquals("count", packets.unit); assertEquals(60.0, packets.p50Ms, 0.0)
        for (bad in listOf(details.replace("\"verify\":900", "\"verify\":10000001"),
            details.replace("\"packets\":60", "\"packets\":1094"), details.replace("\"append\":50,", ""))) {
            val malformed = VisionRunStatistics.parse(bad)
            assertEquals(1, malformed.malformed); assertTrue(malformed.groups.isEmpty())
        }
    }
    private fun detailedMatch(status: String = "found") = """{"version":1,"kind":"vision","algorithm":"rust-rgba-first","elapsedUs":108000,"status":"$status","matchStagesUs":{"setup":200,"prefilterBuild":800,"searchCompare":107000},"matchWork":{"templatesVisited":1,"preparedCacheHits":1,"templatesPrepared":0,"prefiltersBuilt":1,"candidatesVisited":10000,"prefilterRejected":9000,"comparisonPixels":123456,"prefilterWork":187384}}"""

    @Test fun matchWorkUsesCountsAndStagesAreOptionalAndCollapsed() {
        val report = VisionRunStatistics.parse(detailedMatch() + "\n" + event(109000))
        assertEquals(0, report.malformed)
        val detail = report.groups.first { it.algorithm == "match-work-comparisonPixels" }
        assertEquals("count", detail.unit)
        assertEquals(123456.0, detail.p50Ms, 0.0)
        assertEquals(1, detail.count)
        assertEquals(107.0, report.groups.first { it.algorithm == "match-searchCompare" }.p50Ms, 0.0)
        val section = VisionLogPresentation.sections(VisionRunStatistics.parse(detailedMatch())).single()
        assertEquals(1, VisionLogPresentation.visibleRows(section, false).size)
        assertEquals(13, VisionLogPresentation.visibleRows(section, true).size)
        val ratio = report.groups.first { it.algorithm == "match-work-prefilterRejectPpm" }
        assertEquals("%", ratio.unit)
        assertEquals(90.0, ratio.p50Ms, 0.0)
        assertEquals("精确比较（像素）", VisionLogPresentation.algorithm(detail.algorithm))
    }

    @Test fun matchOutcomesStaySeparateAndMalformedWorkLeavesNoPartialSamples() {
        val report = VisionRunStatistics.parse(detailedMatch() + "\n" + detailedMatch("cancelled"))
        assertEquals(2, report.groups.count { it.algorithm == "match-searchCompare" })
        for (bad in listOf(detailedMatch().replace("\"comparisonPixels\":123456", "\"comparisonPixels\":-1"),
            detailedMatch().replace("\"prefilterWork\":187384", "\"unexpected\":187384"))) {
            val invalid = VisionRunStatistics.parse(bad)
            assertEquals(1, invalid.malformed)
            assertTrue(invalid.groups.isEmpty())
        }
    }
    @Test fun rejectionRatiosComeFromEachEventNotDividingCountPercentiles() {
        val first = detailedMatch().replace("\"candidatesVisited\":10000", "\"candidatesVisited\":100")
            .replace("\"prefilterRejected\":9000", "\"prefilterRejected\":90")
        val second = detailedMatch().replace("\"candidatesVisited\":10000", "\"candidatesVisited\":1000")
            .replace("\"prefilterRejected\":9000", "\"prefilterRejected\":100")
        val ratio = VisionRunStatistics.parse("$first\n$second").groups.first { it.unit == "%" }
        assertEquals(10.0, ratio.p50Ms, 0.0)
        assertEquals(90.0, ratio.p95Ms, 0.0)
        val zero = detailedMatch().replace("\"candidatesVisited\":10000", "\"candidatesVisited\":0")
            .replace("\"prefilterRejected\":9000", "\"prefilterRejected\":0")
        assertFalse(VisionRunStatistics.parse(zero).groups.any { it.unit == "%" })
        assertEquals(1, VisionRunStatistics.parse(first.replace("\"prefilterRejected\":90", "\"prefilterRejected\":101")).malformed)
    }

    @Test fun optionalBridgeStagesAreSeparateAndAbsentDataIsNotZero() {
        val detailed = """{"version":1,"kind":"vision","algorithm":"capture-host-roundtrip","elapsedUs":30000,"status":"completed","captureStagesUs":{"acquisition":20000,"transferVerified":8000,"framePublish":1000,"systemCapture":5000,"softwareReadback":6000,"pixelRead":2000,"pixelEncode":1000,"bridgeWrite":3000,"bridgeReceive":7000}}"""
        val legacy = """{"version":1,"kind":"vision","algorithm":"capture-host-roundtrip","elapsedUs":31000,"status":"completed","captureStagesUs":{"acquisition":21000,"transferVerified":8000,"framePublish":1000}}"""
        val report = VisionRunStatistics.parse("$detailed\n$legacy")
        assertEquals(0, report.malformed)
        assertEquals(10, report.groups.size)
        assertEquals(2, report.groups.first { it.algorithm == "capture-host-roundtrip" }.count)
        val receive = report.groups.first { it.algorithm == "capture-bridgeReceive" }
        assertEquals(1, receive.count)
        assertEquals(7.0, receive.p50Ms, 0.0)
        assertEquals("桥接收（重叠）", VisionLogPresentation.algorithm(receive.algorithm))
        val section = VisionLogPresentation.sections(report).single()
        assertEquals(4, VisionLogPresentation.visibleRows(section, false).size)
        assertEquals(10, VisionLogPresentation.visibleRows(section, true).size)
        val invalid = detailed.replace("\"systemCapture\":5000", "\"systemCapture\":10000001")
        val bad = VisionRunStatistics.parse(invalid)
        assertEquals(1, bad.malformed)
        assertTrue(bad.groups.isEmpty())
    }

    @Test fun incompleteCaptureStagesDoNotLeavePartialSamples() {
        val report = VisionRunStatistics.parse("""{"version":1,"kind":"vision","algorithm":"capture-host-roundtrip","elapsedUs":300000,"status":"completed","captureStagesUs":{"acquisition":100000}}""")
        assertTrue(report.groups.isEmpty())
        assertEquals(1, report.malformed)
    }
    @Test fun captureStagesRemainSeparateAndDifferentBackendsDoNotMix() {
        fun capture(backend: String) = """{"version":1,"kind":"vision","algorithm":"capture-host-roundtrip","elapsedUs":300000,"status":"completed","frame":[720,1280],"captureBackend":"$backend","captureTransport":"authenticated-stream","captureStagesUs":{"acquisition":100000,"transferVerified":20000,"framePublish":1000}}"""
        val report = VisionRunStatistics.parse(capture("root-surfacecontrol") + "\n" + capture("root-screencap-fallback"))
        assertEquals(8, report.groups.size)
        assertEquals(0, report.malformed)
        assertEquals(100.0, report.groups.first { it.algorithm == "capture-acquisition" }.p50Ms, 0.0)
        assertEquals(300.0, report.groups.first { it.algorithm == "capture-host-roundtrip" }.p50Ms, 0.0)
        assertTrue(report.groups.all { it.count == 1 })
    }
    private fun event(time: Int, status: String = "found", roi: Int = 100) =
        """{"version":1,"kind":"vision","algorithm":"rust-rgba-first","elapsedUs":$time,"status":"$status","frame":[720,1280],"roi":[0,0,$roi,100],"templateCount":1,"templateSizes":[[10,10]],"similarity":900}"""

    @Test fun nearestRankAndInputOutcomeGroupsAreSeparate() {
        val records = (1..100).map { event(it * 1000) } + event(999000, "not_found") + event(500000, roi = 200)
        val report = VisionRunStatistics.parse(records.joinToString("\n"))
        assertEquals(3, report.groups.size)
        val group = report.groups.first { it.count == 100 }
        assertEquals(50.0, group.p50Ms, 0.0)
        assertEquals(95.0, group.p95Ms, 0.0)
        assertEquals(99.0, group.p99Ms, 0.0)
    }
    @Test fun LossAndBadRecordsAreNotPassedOffAsSamples() {
        val report = VisionRunStatistics.parse(event(-1) + "\ninvalid\n" +
            """{"kind":"dropped","count":12}
{"kind":"trace"}""")
        assertTrue(report.groups.isEmpty())
        assertEquals(12L, report.dropped)
        assertEquals(2, report.malformed)
        assertEquals(1, report.traceCount)
    }
}
