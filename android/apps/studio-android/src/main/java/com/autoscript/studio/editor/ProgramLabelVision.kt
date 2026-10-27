package com.autoscript.studio

internal fun ProgramLabelArguments.visionDetails(kind: String): String? = when (kind) {
    "vision.findimage", "vision.findallimages", "vision.findgray", "vision.findfeatures", "vision.findscaled" -> parts(
        fileInput("imageDirectory")?.let { "目录 $it" } ?: arraySize("imagePaths")?.takeIf { it > 0 }?.let { "$it 张模板" }
            ?: fileInput("imagePath"),
        if (compact) null else region(useEdgeVariables = kind == "vision.findimage"),
        score(if (kind == "vision.findfeatures") "minimumScorePermille" else "similarityPermille")?.let {
            if (kind == "vision.findfeatures") "${if (compact) "内点≥" else "最低内点比 "}$it"
            else "${if (compact) "≥" else "相似度≥"}$it"
        },
        if (kind == "vision.findscaled") score("minScalePermille")?.let { "缩放 $it 至 ${score("maxScalePermille") ?: "?"}" } else null,
        if (text("successAction") != null && text("successAction") != "none") enum("successAction")?.let {
            (if (kind == "vision.findallimages" && boolean("allMatches") == true) "逐项" else if (compact) "" else "找到后") + it
        } else null,
        output() ?: matchOutputs())
    "vision.findcolor", "vision.findallcolor", "vision.countcolor", "vision.components" -> parts(
        color("rgb"), text("tolerance")?.let { "容差 $it" }, region(), output() ?: matchOutputs())
    "vision.findmulticolor", "vision.findallmulticolor" -> parts(color("anchorRgb")?.let { "基准 $it" },
        arraySize("samples")?.let { "$it 个偏移点" } ?: text("samples")?.let { "已配置色点" }, region(), output() ?: matchOutputs())
    "vision.comparecolor", "vision.getcolor" -> parts(pointArgument(), color("rgb"),
        if (kind == "vision.comparecolor") text("tolerance")?.let { "容差 $it" } else null, output())
    "vision.readresult", "vision.result" -> parts(text("sourceVariable"),
        text("index")?.let { "第 $it 项" }, enum("field"), output() ?: output("targetVariable"))
    "vision.resultaction" -> parts(text("sourceVariable"),
        if (text("selection") == "all") "最多 ${input("limit")} 项" else enum("selection"), enum("anchor"), enum("action"),
        if (text("action") in setOf("tapWait", "pressRelease")) "${input("durationMs")}ms" else null)
    "ocr.alphanumeric", "ocr.alphanumericv5" -> parts(region(),
        if (kind == "ocr.alphanumeric" && boolean("includeSymbols") == true) "含符号" else null,
        score(if (kind == "ocr.alphanumeric") "minimumConfidencePermille" else "minimumScorePermille")?.let { "置信度≥$it" },
        output() ?: output("textVariable"))
    "ocr.glyph", "ocr.glyphdetails" -> parts(fileInput("dictionaryPath"), region(),
        additionalColorCount()?.let { "${it + 1} 色采样" }, output() ?: output("textVariable"))
    "ocr.findtext" -> parts(text("detailsVariable"), input("text", quoted = true), output())
    "screen.capture", "screen.captureregion" -> parts(region(), output())
    "screen.loadimage" -> parts(fileInput("imagePath"), output())
    "screen.crop" -> parts(text("frameVariable"), region(), output())
    "screen.release", "screen.frameinfo" -> parts(text("frameVariable"), output())
    "screen.seriesrelease", "screen.seriesframe" -> parts(text("sourceVariable"), text("index")?.let { "第 $it 帧" }, output())
    "screen.savepng" -> parts(text("frameVariable"), text("path")?.let { "保存到 $it" })
    "screen.mappoint" -> parts(text("frameVariable"), point("x", "y"), output())
    "screen.compareframes" -> parts(parts(text("frameVariable"), text("previousFrameVariable")), region(), output())
    "screen.series" -> parts(text("maxFrames")?.let { "最多 $it 帧" }, text("durationMs")?.let { "${it}ms" },
        text("targetFps")?.let { "$it 帧/秒" }, output())
    "screen.transform" -> parts(text("frameVariable"), when (text("mode")) {
        "resize" -> "${input("width")} × ${input("height")}"; "threshold" -> "阈值 ${input("threshold")}"
        "filter" -> text("filter")?.let { "规则 $it" }; "mask" -> text("mask")?.let { "规则 $it" }
        "despeckle", "erode", "dilate", "open", "close" -> text("cleanup")?.let { "规则 $it" }
        else -> if (transformTitle(text("mode")) == null) text("mode")?.let { "模式 $it" } else null
    }, output())
    "legacy.duodianbise", "legacy.duodianzhaose" -> parts(legacyRegion(), arraySize("pattern")?.let { "$it 个色点" },
        text("minimumMatchPercent")?.let { "匹配≥$it%" }, output())
    "legacy.getrectcolornum" -> parts(legacyRegion(), arraySize("colors")?.let { "$it 组颜色" }, output())
    "legacy.getrgbcolor" -> parts("RGB(${input("red")}, ${input("green")}, ${input("blue")})", output())
    else -> null
}

private fun ProgramLabelArguments.matchOutputs(): String? = parts(
    output("foundVariable"), if (text("xVariable") != null || text("yVariable") != null)
        "坐标→(${text("xVariable") ?: "?"}, ${text("yVariable") ?: "?"})" else null,
).takeIf(String::isNotBlank)
