package com.autoscript.studio

import java.util.Locale
import com.google.gson.JsonParser

/** Compares recorded conditions, not screen pixels or causality. Missing facts are never inferred. */
internal object VisionRunComparison {
    data class Sample(val metadata: Map<String, String>, val report: VisionRunStatistics.Report)
    data class Row(val before: VisionRunStatistics.Group, val after: VisionRunStatistics.Group) {
        fun change(before: Double, after: Double): String = if (before == 0.0) "—" else
            String.format(Locale.ROOT, "%+.1f%%", (after / before - 1.0) * 100.0)
    }
    data class Report(val rows: List<Row>, val warnings: List<String>, val blocked: String? = null) {
        fun clipboardText(): String = text().also { require(it.toByteArray(Charsets.UTF_8).size <= 128 * 1024) }

        fun text(): String = buildString {
            appendLine("智构识图日志对比 · 左基线 / 右当前")
            blocked?.let { appendLine(it) }
            warnings.forEach { appendLine(it) }
            rows.forEachIndexed { index, row ->
                appendLine("${index + 1}. ${row.after.algorithm} · ${row.after.status} · 单位 ${row.after.unit}")
                appendLine("样本 ${row.before.count} → ${row.after.count}")
                listOf("P50" to (row.before.p50Ms to row.after.p50Ms),
                    "P95" to (row.before.p95Ms to row.after.p95Ms),
                    "P99" to (row.before.p99Ms to row.after.p99Ms)).forEach { (label, values) ->
                    appendLine(String.format(Locale.ROOT, "%s %.2f → %.2f (%s)", label, values.first, values.second,
                        row.change(values.first, values.second)))
                }
                appendLine(row.after.configuration)
            }
        }
    }

    fun compare(before: Sample, after: Sample): Report {
        val warnings = mutableListOf("仅比较记录条件；找图要求模板内容指纹一致，屏幕画面未核验，不自动判断改动收益。", "分段百分位不可相加；正数增加、负数减少，工作量变化不等于速度变化。")
        val environment = listOf("projectId", "abi", "androidApi", "device", "screen", "design")
        val missing = environment.filter { before.metadata[it].isNullOrBlank() || after.metadata[it].isNullOrBlank() }
        if (missing.isNotEmpty()) return Report(emptyList(), warnings, "环境信息缺失：${missing.joinToString()}，不计算变化。")
        val changed = environment.filter { before.metadata[it] != after.metadata[it] }
        if (changed.isNotEmpty()) return Report(emptyList(), warnings, "环境或项目不同：${changed.joinToString()}，不混合对比。")
        fun inspect(label: String, sample: Sample) {
            val metadata = sample.metadata
            if (metadata["status"] == "running") warnings += "$label 仍在运行，仅为已写入快照。"
            val bad = listOf("droppedRecords", "truncatedRecords", "visionEngineDropped").any { (metadata[it]?.toLongOrNull() ?: 0) > 0 }
            if (bad || sample.report.dropped > 0 || sample.report.malformed > 0 || metadata["writerError"] == "true") {
                warnings += "$label 有丢弃、限额、格式或写盘问题，样本不完整。"
            }
            if ((metadata["evictedEventRecords"]?.toLongOrNull() ?: 0) > 0) warnings += "$label 已轮转，仅比较保留窗口，不代表全程。"
            if (metadata["rootSessionChanged"] == "true") warnings += "$label Root会话曾中断或更换。"
        }
        inspect("基线", before); inspect("当前", after)
        fun key(group: VisionRunStatistics.Group) = Triple(group.algorithm,
            group.configuration + "|" + group.parameters["templateFingerprintVersion"] + "|" + group.parameters["templateFingerprints"], group.status)
        fun complete(group: VisionRunStatistics.Group): Boolean {
            val fields = if (group.algorithm.startsWith("capture-")) listOf("frame", "captureBackend", "captureTransport")
                else listOf("frame", "roi", "templateCount", "templateSizes", "tolerance", "similarity", "direction", "comparisonBudget", "timeoutMs")
            return fields.all { !group.parameters[it].isNullOrBlank() } &&
                (group.algorithm.startsWith("capture-") || hasTemplateIdentity(group.parameters))
        }
        if ((before.report.groups + after.report.groups).any { !complete(it) }) warnings += "部分指标缺少输入/后端口径或完整模板指纹，不计算变化；旧日志仍可查看。"
        val previous = before.report.groups.filter(::complete).associateBy(::key)
        val rows = after.report.groups.filter(::complete).mapNotNull { group ->
            previous[key(group)]?.takeIf { it.unit == group.unit }?.let { Row(it, group) }
        }
        if (rows.any { it.before.count < 100 || it.after.count < 100 }) warnings += "部分行不足100个样本，尾部百分位参考性有限。"
        val unmatched = before.report.groups.size + after.report.groups.size - rows.size * 2
        if (unmatched > 0) warnings += "$unmatched 行没有相同算法/条件/结果的对照，未计算变化。"
        return Report(rows, warnings, if (rows.isEmpty()) "没有相同记录条件和结果的指标。" else null)
    }

    internal fun hasTemplateIdentity(parameters: Map<String, String>): Boolean = runCatching {
        if (parameters["templateFingerprintVersion"] != "sha256-rgba-v1") return false
        val count = parameters["templateCount"]?.toIntOrNull() ?: return false
        if (count !in 1..64) return false
        val hashes = JsonParser.parseString(parameters["templateFingerprints"] ?: return false).asJsonArray
        hashes.size() == count && hashes.all { hash -> hash.isJsonPrimitive && hash.asJsonPrimitive.isString &&
            hash.asString.matches(Regex("[0-9a-f]{64}")) }
    }.getOrDefault(false)
}
