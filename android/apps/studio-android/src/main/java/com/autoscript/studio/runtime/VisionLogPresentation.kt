package com.autoscript.studio

/** Presentation only: never merge different inputs or add stage percentiles together. */
internal object VisionLogPresentation {
    data class Section(val configuration: String, val parameters: Map<String, String>, val rows: List<VisionRunStatistics.Group>)
    data class Outcome(val status: String, val rows: List<VisionRunStatistics.Group>)
    data class ScreenshotLine(val heading: String? = null, val group: VisionRunStatistics.Group? = null)

    fun isCapture(section: Section): Boolean = section.rows.any { it.algorithm in setOf("capture-host-roundtrip", "capture-region-host-roundtrip") }

    /** Display adjacent independent tables, never correlate events or merge input conditions. */
    fun screenshotSections(sections: List<Section>, selected: Section): List<Section> {
        if (isCapture(selected)) return listOf(selected)
        val captures = sections.filter { candidate -> isCapture(candidate) &&
            listOf("flowId", "nodeId", "frame").all { key ->
                selected.parameters[key] != null && selected.parameters[key] == candidate.parameters[key]
            }
        }
        return if (captures.size == 1) captures + selected else listOf(selected)
    }

    fun isTransferDetail(group: VisionRunStatistics.Group): Boolean = group.algorithm.startsWith("capture-transfer-")

    fun screenshotLines(sections: List<Section>, transferOnly: Boolean = false): List<ScreenshotLine> = sections.flatMap { section ->
        outcomes(section, expanded = true).flatMap { outcome ->
            val rows = outcome.rows.filter { isTransferDetail(it) == transferOnly }
            if (rows.isEmpty()) emptyList() else
                listOf(ScreenshotLine(heading = "${if (isCapture(section)) "截图" else "识图"} · ${if (transferOnly) "传输明细 · " else ""}${status(outcome.status)}")) +
                    rows.map { ScreenshotLine(group = it) }
        }
    }

    // Bound each screenshot page; continuation pages keep the same column headings.
    fun screenshotPageCapacity(heightDp: Int, fontScale: Float): Int =
        ((heightDp - 12) / (14f * fontScale.coerceAtLeast(1f))).toInt().coerceIn(1, 26)

    fun screenshotPages(lines: List<ScreenshotLine>, capacity: Int): List<List<ScreenshotLine>> {
        require(capacity > 0)
        val pages = mutableListOf<List<ScreenshotLine>>()
        var offset = 0
        while (offset < lines.size) {
            val page = mutableListOf<ScreenshotLine>()
            if (capacity > 1 && lines[offset].heading == null) {
                lines.take(offset).lastOrNull { it.heading != null }?.let { page += it.copy(heading = "${it.heading} · 续") }
            }
            while (page.size < capacity && offset < lines.size) {
                if (page.isNotEmpty() && page.size == capacity - 1 && lines[offset].heading != null && offset < lines.lastIndex) break
                page += lines[offset++]
            }
            pages += page
        }
        return pages
    }

    fun outcomes(section: Section, expanded: Boolean): List<Outcome> = visibleRows(section, expanded)
        .groupBy { it.status }.map { (status, rows) -> Outcome(status, rows) }

    private val captureOrder = listOf("capture-host-roundtrip", "capture-acquisition", "capture-transferVerified", "capture-framePublish",
        "capture-systemCapture", "capture-softwareReadback", "capture-pixelRead", "capture-pixelEncode", "capture-bridgeWrite", "capture-bridgeReceive",
        "capture-transfer-sendEncode", "capture-transfer-sendWrite", "capture-transfer-receiveRead", "capture-transfer-verify", "capture-transfer-append", "capture-transfer-packets")
    private val matchOrder = listOf("match-setup", "match-prefilterBuild", "match-searchCompare", "match-work-templatesVisited", "match-work-preparedCacheHits",
        "match-work-templatesPrepared", "match-work-prefiltersBuilt", "match-work-candidatesVisited", "match-work-prefilterRejected", "match-work-prefilterRejectPpm", "match-work-comparisonPixels", "match-work-prefilterWork")

    fun sections(report: VisionRunStatistics.Report): List<Section> = report.groups.groupBy { it.configuration }.map { (configuration, rows) ->
        Section(configuration, rows.first().parameters, rows.sortedBy { captureOrder.indexOf(if (it.algorithm == "capture-region-host-roundtrip") "capture-host-roundtrip" else it.algorithm).takeIf { index -> index >= 0 }
            ?: matchOrder.indexOf(it.algorithm).takeIf { index -> index >= 0 }?.let { index -> captureOrder.size + 1 + index } ?: captureOrder.size })
    }

    fun visibleRows(section: Section, expanded: Boolean): List<VisionRunStatistics.Group> =
        if (expanded) section.rows else section.rows.filterNot { captureOrder.indexOf(it.algorithm) >= 4 || it.algorithm in matchOrder }

    fun algorithm(value: String): String = when (value) {
        "match-setup" -> "准备 / 收尾"
        "match-prefilterBuild" -> "预筛构建"
        "match-searchCompare" -> "遍历 / 精确比较"
        "match-work-templatesVisited" -> "搜索模板（个）"
        "match-work-preparedCacheHits" -> "预处理复用（次）"
        "match-work-templatesPrepared" -> "现场预处理（次）"
        "match-work-prefiltersBuilt" -> "预筛表（个）"
        "match-work-candidatesVisited" -> "候选窗口（个）"
        "match-work-prefilterRejected" -> "预筛排除（个）"
        "match-work-prefilterRejectPpm" -> "预筛排除率（%）"
        "match-work-comparisonPixels" -> "精确比较（像素）"
        "match-work-prefilterWork" -> "构表比较（像素）"
        "capture-systemCapture" -> "系统捕获"
        "capture-softwareReadback" -> "软件位图读回"
        "capture-pixelRead" -> "读取像素"
        "capture-pixelEncode" -> "BGRA 转换"
        "capture-bridgeWrite" -> "桥写出 / 背压"
        "capture-bridgeReceive" -> "桥接收（重叠）"
        "capture-host-roundtrip" -> "截图总耗时"
        "capture-region-host-roundtrip" -> "区域截图总耗时"
        "capture-acquisition" -> "采集 / 桥读取"
        "capture-transferVerified" -> "传输 / 鉴权"
        "capture-framePublish" -> "帧入池"
        "capture-transfer-sendEncode" -> "发送封包 / 鉴权"
        "capture-transfer-sendWrite" -> "发送写出 / 背压"
        "capture-transfer-receiveRead" -> "接收等待 / 读取"
        "capture-transfer-verify" -> "接收验签 / 校验"
        "capture-transfer-append" -> "像素追加"
        "capture-transfer-packets" -> "像素数据包（个）"
        "rust-rgba-first" -> "找图 · 首结果"
        "rust-rgba-single" -> "找图 · 单模板"
        "rust-rgba-all" -> "找图 · 多结果"
        else -> value
    }

    fun status(value: String): String = when (value) {
        "completed" -> "完成"
        "found" -> "找到"
        "not_found" -> "未找到"
        "cancelled" -> "取消"
        "timeout" -> "超时"
        "budget_exceeded" -> "预算超限"
        "error" -> "错误"
        else -> value
    }

    fun summary(parameters: Map<String, String>): String = listOfNotNull(
        parameters["frame"]?.replace("[", "")?.replace("]", "")?.replace(",", "×"),
        parameters["captureBackend"]?.let { when (it) { "root-surfacecontrol" -> "常驻 Root"; "root-screencap-fallback" -> "兼容截图"; else -> it } },
        parameters["captureTransport"]?.let { when (it) {
            "authenticated-stream" -> "认证流式"
            "authenticated-pipeline" -> "认证流水线"
            else -> it
        } },
        parameters["templateCount"]?.let { "模板 $it" },
        parameters["templateCount"]?.toIntOrNull()?.takeIf { it > 0 }?.let {
            if (VisionRunComparison.hasTemplateIdentity(parameters)) "指纹已记录" else "内容未核验"
        },
        parameters["similarity"]?.let { "$it‰" },
        parameters["roi"]?.let { "ROI $it" },
        parameters["captureRegion"]?.let { "原屏区域 $it" },
    ).joinToString(" · ")

    fun details(section: Section): String {
        val labels = mapOf("flowId" to "插件", "nodeId" to "积木 ID", "frame" to "帧尺寸", "roi" to "ROI",
            "templateCount" to "模板数量", "templateSizes" to "模板尺寸", "templateFingerprintVersion" to "模板指纹口径",
            "templateFingerprints" to "模板内容指纹（按搜索顺序）", "tolerance" to "容差", "similarity" to "相似度‰",
            "direction" to "搜索方向", "limit" to "结果上限", "captureBackend" to "截图后端", "captureTransport" to "传输方式")
        return section.parameters.entries.joinToString("\n") { (name, value) -> "${labels[name] ?: name}：$value" }
            .ifBlank { section.configuration }
    }
}

internal fun runtimeLogBodyHeightDp(screenHeightDp: Int, fullScreen: Boolean = false): Int =
    if (fullScreen) (screenHeightDp - 82).coerceAtLeast(48) else (screenHeightDp - 120).coerceIn(96, 400)
