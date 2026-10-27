package com.autoscript.studio

/** Summaries count operation rows only, never the derived timing/work rows. */
internal object RuntimeLogHealth {
    data class Counts(val captures: Int, val found: Int, val notFound: Int, val failed: Int, val cancelled: Int)

    fun counts(report: VisionRunStatistics.Report): Counts {
        fun isCapture(group: VisionRunStatistics.Group) = group.algorithm in setOf("capture-host-roundtrip", "capture-region-host-roundtrip")
        val primary = report.groups.filter { isCapture(it) ||
            (!it.algorithm.startsWith("capture-") && !it.algorithm.startsWith("match-")) }
        fun count(predicate: (VisionRunStatistics.Group) -> Boolean) = primary.filter(predicate).sumOf { it.count }
        return Counts(
            count { isCapture(it) && it.status == "completed" },
            count { it.status == "found" }, count { it.status == "not_found" },
            count { it.status in setOf("error", "failed", "timeout", "budget_exceeded") }, count { it.status == "cancelled" },
        )
    }

    fun duration(ms: Long?): String {
        if (ms == null || ms < 0) return "未知"
        val seconds = ms / 1_000
        return if (seconds < 3_600) "${seconds / 60}:${(seconds % 60).toString().padStart(2, '0')}"
        else "${seconds / 3_600}:${(seconds / 60 % 60).toString().padStart(2, '0')}:${(seconds % 60).toString().padStart(2, '0')}"
    }

    fun runSummary(metadata: Map<String, String>): String = buildString {
        append("${metadata["abi"] ?: "未知 ABI"} · ${metadata["screen"] ?: "未知尺寸"}")
        append(" · ${if (metadata["status"] == "running") "已记录" else "运行"} ${duration(metadata["durationMs"]?.toLongOrNull())}")
        append(" · Root ${duration(metadata["rootSessionAgeMs"]?.toLongOrNull())}")
        if (metadata["rootSessionChanged"] == "true") append("（已断开）")
    }

    fun visionSummary(report: VisionRunStatistics.Report, metadata: Map<String, String>): String {
        val counts = counts(report)
        val names = listOf("visionCaptures", "visionFound", "visionNotFound", "visionFailed", "visionCancelled")
        val cumulative = names.map { metadata[it]?.toLongOrNull()?.takeIf { n -> n >= 0 } }
        val whole = cumulative.all { it != null }
        val values = if (whole) cumulative.filterNotNull() else listOf(counts.captures, counts.found, counts.notFound, counts.failed, counts.cancelled).map(Int::toLong)
        val engineDropped = metadata["visionEngineDropped"]?.toLongOrNull()?.coerceAtLeast(0) ?: report.dropped.coerceAtLeast(0)
        val dropped = (metadata["droppedRecords"]?.toLongOrNull() ?: 0).coerceAtLeast(0) + engineDropped
        return "${if (whole) "累计 " else ""}截图 ${values[0]} · 找到 ${values[1]} · 未找到 ${values[2]} · 失败 ${values[3]} · 取消 ${values[4]} · 丢弃 $dropped"
    }
}
