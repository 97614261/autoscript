package com.autoscript.studio

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.junit.Assert.*
import org.junit.Test

class CompactProgramLabelTest {
    private fun label(kind: String, json: String, flowNames: Map<String, String>? = null, row: Boolean = false): String =
        compactProgramLabel(BlockCatalog.find(kind), JsonParser.parseString(json).asJsonObject, kind, flowNames, row)

    @Test fun coordinatesAndSwipeShowEndpointsAndDuration() {
        assertTrue(label("input.tap", """{"x":320,"y":640}""").endsWith("· (320, 640)"))
        assertTrue(label("input.swipe", """{"x1":1,"y1":2,"x2":3,"y2":4,"durationMs":500}""")
            .endsWith("· (1, 2) → (3, 4) · 500ms"))
    }

    @Test fun pluginReturnStatusIsReadableWithoutBooleanInternals() {
        assertEquals("返回成功", label("flow.status.return", """{"success":true}"""))
        assertEquals("返回失败", label("flow.status.return", """{"success":false}"""))
        assertEquals("返回成功 / 失败 · 未设置", label("flow.status.return", "{}"))
    }

    @Test fun assignmentsDistinguishStringFromNumber() {
        assertTrue(label("variable.set", """{"name":"count","value":3}""").endsWith("count = 3"))
        assertTrue(label("variable.set", """{"name":"count","value":"3"}""").endsWith("count = \"3\""))
        assertTrue(label("variable.copy", """{"name":"result","sourceName":"count"}""").endsWith("result ← count"))
    }

    @Test fun dynamicValuesDoNotShowInactiveFixedValues() {
        val repeat = label("control.repeat", """{"times":0,"timesVariable":"count"}""")
        assertTrue(repeat.endsWith("变量 count 次"))
        assertFalse(repeat.contains("0 次"))
        assertTrue(label("task.sleep", """{"milliseconds":0,"millisecondsVariable":"delay"}""")
            .endsWith("变量 delay ms"))
    }

    @Test fun compoundConditionNeverShowsInactiveComparison() {
        val summary = label("control.if", """{"variable":"unused","value":false,"condition":{"type":"all","conditions":[]}}""")
        assertTrue(summary.endsWith("复合条件（未完成）"))
        assertFalse(summary.contains("unused"))
        assertTrue(label("control.if", """{"variable":"count","operator":"greaterThan","value":0,"valueVariable":"limit"}""")
            .endsWith("count > 变量 limit"))
    }

    @Test fun visionShowsTemplatesInsteadOfFrameInternals() {
        val summary = label("vision.findallimages", """{"frameVariable":"frame","imagePaths":["images/a.png","images/b.png"],"resultVariable":"matches"}""")
        assertTrue(summary.endsWith("2 张模板 · → matches"))
        assertFalse(summary.contains("frame"))
    }

    @Test fun summaryIsBoundedSingleLineAndDoesNotMutateData() {
        val args = JsonObject().apply { addProperty("message", "hello\n\t" + "x".repeat(5000)) }
        val original = args.toString()
        val summary = compactProgramLabel(BlockCatalog.find("task.comment"), args, "task.comment")
        assertFalse(summary.contains('\n'))
        assertFalse(summary.contains('\t'))
        assertTrue(summary.endsWith("…"))
        assertTrue(summary.length < 120)
        assertEquals(original, args.toString())
    }

    @Test fun missingAndStructuredValuesRemainExplicit() {
        assertTrue(label("input.tap", "{}").endsWith("(?, ?)"))
        assertTrue(label("variable.set", """{"name":"data","value":{}}""").endsWith("结构化值"))
        assertEquals("unknown.kind", compactProgramLabel(null, null, "unknown.kind"))
        assertEquals(BlockCatalog.find("input.tap")!!.title, compactProgramLabel(BlockCatalog.find("input.tap"), null, "input.tap"))
    }

    @Test fun loopTitlesDescribeActualModeNotEveryAvailableMode() {
        assertEquals("无限循环", label("control.cycle", """{"yieldMs":1}"""))
        assertEquals("限时循环 · 限时 5000ms", label("control.cycle", """{"yieldMs":1,"durationMs":5000}"""))
        assertEquals("限时循环 · 限时 变量 时长 秒", label("control.cycle",
            """{"yieldMs":1,"durationVariable":"时长","durationUnit":"seconds"}"""))
        assertEquals("次数循环 · 5 次", label("control.repeat", """{"times":5}"""))
        assertEquals("限时循环 · 限时 0ms", label("control.cycle", """{"yieldMs":1,"durationMs":0}"""))
    }

    @Test fun oldBoundedWhileNeverPretendsToBeUnbounded() {
        val summary = label("control.while", """{"always":true,"maxIterations":100,"variable":"unused"}""")
        assertEquals("循环（次数保护） · 保护上限 100 次", summary)
        assertFalse(summary.contains("无限"))
        assertFalse(summary.contains("unused"))
        assertEquals("条件循环 · 次数 < 3 · 保护上限 100 次", label("control.while",
            """{"variable":"次数","operator":"lessThan","value":3,"maxIterations":100}"""))
    }

    @Test fun loopHelpersShowMetricAndOnlyItsActiveUnit() {
        assertEquals("达到次数跳出 · 变量 上限 次", label("control.loopcheck",
            """{"metric":"count","limit":0,"limitVariable":"上限","unit":"minutes"}"""))
        assertEquals("读取循环耗时 · 秒 · → 耗时", label("control.loopmetric",
            """{"metric":"elapsed","unit":"seconds","name":"耗时"}"""))
    }

    @Test fun dynamicPromptUsesExpressionAndRealChannel() {
        assertEquals("动态提示 · 运行提示 · text(次数)", label("task.message",
            """{"expression":"text(次数)","channel":"output"}"""))
        assertEquals("动态提示 · 日志 · \"完成\"", label("task.message",
            """{"expression":"\"完成\"","channel":"console"}"""))
        assertEquals("关闭运行提示", label("task.promptenabled", """{"enabled":false}"""))
        assertEquals("输出日志 · 警告 · 变量 错误信息", label("task.log",
            """{"level":"warn","message":"inactive","valueVariable":"错误信息"}"""))
    }

    @Test fun pluginBranchTitleDoesNotConsumeTheParameterSeparator() {
        val summary = label("flow.call.branch", """{"targetFlowId":"flow-123","arguments":{"1":true,"2":"x"}}""",
            mapOf("flow-123" to "插件2"))
        assertEquals("调用插件", compactProgramRowText(summary).title)
        assertEquals("插件2 · 2 个参数", compactProgramRowText(summary).details)
    }

    @Test fun everyPluginReferenceResolvesItsNameWithoutChangingStoredIdentity() {
        listOf("flow.call", "flow.call.branch", "task.spawn", "timer.every").forEach { kind ->
            val args = JsonParser.parseString("""{"targetFlowId":"flow-123","arguments":{}}""").asJsonObject
            val original = args.toString()
            val contract = BlockCatalog.find(kind)
            assertTrue(compactProgramLabel(contract, args, kind, mapOf("flow-123" to "恢复操作"), true).contains("恢复操作"))
            assertFalse(compactProgramLabel(contract, args, kind).contains("flow-123"))
            assertTrue(compactProgramLabel(contract, args, kind, emptyMap()).contains("插件不存在"))
            assertTrue(compactProgramLabel(contract, args, kind, mapOf("flow-123" to "新名字")).contains("新名字"))
            assertEquals(original, args.toString())
        }
        assertTrue(label("flow.call", "{}", emptyMap()).endsWith("未选择插件"))
    }

    @Test fun compactVisionPutsTemplateThresholdAndActionBeforeLongRegionAndOutputs() {
        val args = """{"imagePath":"assets/images/恢复按钮.png","region":{"left":0,"top":1,"right":720,"bottom":1280},"similarityPermille":900,"successAction":"tap","foundVariable":"找到"}"""
        val summary = label("vision.findimage", args, row = true)
        assertEquals("找图 · 恢复按钮.png · ≥90% · 点击 · →找到", summary)
        assertFalse(summary.contains("(0"))
        val full = label("vision.findimage", args)
        assertTrue(full.contains("区域 (0, 1)–(720, 1280)"))
        assertTrue(full.contains("找到后点击"))
    }

    @Test fun compactPromptsAndLoopsStaySpecificWithoutRepeatingTheirTitle() {
        assertEquals("确认框 · 是否继续？ · →选择", label("ui.confirm",
            """{"title":"请确认","message":"是否继续？","resultVariable":"选择"}""", row = true))
        assertEquals("动态弹出 · text(次数)", label("task.message",
            """{"channel":"popup","expression":"text(次数)"}""", row = true))
        assertEquals("无限循环", label("control.cycle", "{}", row = true))
        assertEquals("限时循环 · 5000ms", label("control.cycle", """{"durationMs":5000}""", row = true))
        assertEquals("次数循环 · \$次数 次", label("control.repeat", """{"times":0,"timesVariable":"次数"}""", row = true))
        assertEquals("等待 · 100ms", label("task.sleep", """{"milliseconds":100}""", row = true))
    }

    @Test fun compactLongValuesLeaveRoomForOtherKeyParametersAndKeepFullSummaryAvailable() {
        val message = "测试文案".repeat(30)
        val args = JsonObject().apply {
            addProperty("title", "默认标题"); addProperty("message", message); addProperty("resultVariable", "选择")
        }
        val contract = BlockCatalog.find("ui.confirm")
        val short = compactProgramLabel(contract, args, "ui.confirm", rowSummary = true)
        assertTrue(short.contains("… · →选择"))
        assertFalse(short.contains("默认标题"))
        assertTrue(short.length < 36)
        assertTrue(compactProgramLabel(contract, args, "ui.confirm").contains("默认标题"))
        assertEquals(message, args["message"].asString)
    }

    @Test fun processingShowsOnlyParametersOfSelectedMode() {
        assertEquals("图像灰度化 · 原图 · → 灰度图", label("screen.transform",
            """{"frameVariable":"原图","mode":"gray","width":0,"height":0,"threshold":128,"resultVariable":"灰度图"}"""))
        assertEquals("图像缩放 · 原图 · 100 × 50 · → 小图", label("screen.transform",
            """{"frameVariable":"原图","mode":"resize","width":100,"height":50,"threshold":128,"resultVariable":"小图"}"""))
        assertEquals("图像二值化 · 原图 · 阈值 90 · → 黑白图", label("screen.transform",
            """{"frameVariable":"原图","mode":"threshold","threshold":90,"resultVariable":"黑白图"}"""))
    }

    @Test fun recognitionShowsActualTemplateRegionAndThreshold() {
        val summary = label("vision.findimage", """{"imagePath":"assets/images/恢复.png","templateVariable":"命中模板","region":{"left":0,"top":1,"right":200,"bottom":300},"leftVariable":"左边","similarityPermille":925,"successAction":"tap","foundVariable":"找到"}""")
        assertTrue(summary.contains("恢复.png"))
        assertFalse(summary.contains("变量 命中模板"))
        assertTrue(summary.contains("区域 (变量 左边, 1)–(200, 300)"))
        assertTrue(summary.contains("相似度≥92.5%"))
        assertTrue(summary.contains("找到后点击"))
        assertTrue(summary.endsWith("→ 找到"))
        assertTrue(label("vision.findgray", """{"imagePath":"assets/images/a.png"}""").startsWith("灰度找图 · a.png"))
    }

    @Test fun colorsAndStructuredPointsAreReadable() {
        assertEquals("单点比色 · (10, 20) · #FF0000 · 容差 5 · → 相同", label("vision.comparecolor",
            """{"point":{"x":10,"y":20},"rgb":16711680,"tolerance":5,"resultVariable":"相同"}"""))
        assertTrue(label("vision.findmulticolor", """{"anchorRgb":0,"samples":[{},{}]}""").contains("2 个偏移点"))
    }

    @Test fun uiReadsAndParameterlessCommandsHideStaleValues() {
        assertEquals("界面操作 · 标签1 · 读取文字 · → 内容", label("ui.command",
            """{"controlId":"标签1","operation":"get:text","value":"inactive","resultVariable":"内容"}"""))
        assertEquals("界面操作 · window · 显示窗口", label("ui.command",
            """{"controlId":"window","operation":"show","value":"inactive"}"""))
        assertEquals("界面操作 · 标签1 · 设置文字 · 变量 文案", label("ui.command",
            """{"controlId":"标签1","operation":"text","value":"inactive","valueVariable":"文案"}"""))
    }

    @Test fun commonUtilityOperationsShowActiveInputsAndResult() {
        assertEquals("取绝对值 · 变量 数值 · → 结果", label("math.unary",
            """{"operation":"abs","value":0,"valueVariable":"数值","resultVariable":"结果"}"""))
        assertEquals("替换全部文本 · 变量 原文 · \"旧\" → \"新\" · → 输出", label("text.replace",
            """{"text":"inactive","textVariable":"原文","needle":"旧","replacement":"新","resultVariable":"输出"}"""))
        assertEquals("替换数据文件一行 · 变量 文件 · 第 3 行 · 变量 内容 · → 成功", label("files.writeline",
            """{"path":"inactive","pathVariable":"文件","line":3,"text":"inactive","textVariable":"内容","resultVariable":"成功"}"""))
        assertTrue(label("data.sort", """{"jsonVariable":"集合","mode":"numberDesc","resultVariable":"排序"}""")
            .contains("数值降序 · 变量 集合 · → 排序"))
    }

    @Test fun httpSummaryDoesNotDumpHeadersOrBodyAndPrefersVariableUrl() {
        assertEquals("HTTP请求 · POST · 变量 地址 · → 响应", label("network.request",
            """{"method":"POST","url":"inactive","urlVariable":"地址","headersJson":"secret header","body":"secret body","resultVariable":"响应"}"""))
    }

    @Test fun boundedResultActionsDoNotClaimToActOnEveryResultOrAutomaticallyReleaseHold() {
        assertEquals("识别结果动作 · 结果 · 最多 3 项 · 中心点 · 按下再弹起 · 200ms", label("vision.resultaction",
            """{"sourceVariable":"结果","selection":"all","limit":3,"anchor":"center","action":"pressRelease","durationMs":200}"""))
        assertTrue(label("vision.resultaction", """{"selection":"first","action":"hold"}""").endsWith("首项 · 按下不弹起"))
    }

    @Test fun glyphColorsAndLegacyRegionUseTheirActualStoredFormats() {
        assertTrue(label("ocr.glyph", """{"dictionaryPath":"assets/glyph/a.asglyph","additionalColors":"FF0000:5;00FF00:6","textVariable":"文字"}""")
            .endsWith("a.asglyph · 3 色采样 · → 文字"))
        val summary = label("legacy.duodianzhaose", """{"region":{"left":10,"top":20,"width":30,"height":40},"pattern":[{}],"minimumMatchPercent":100,"resultVariable":"结果"}""")
        assertTrue(summary.contains("起点 (10, 20)，宽高 30 × 40"))
        assertFalse(summary.contains("区域未完成"))
    }

    @Test fun aliasesStillUseActualParametersWithoutChangingExecutionData() {
        val summary = label("control.repeat", """{"times":3}""")
        assertEquals("重试 · 3 次", applyProgramDisplayLabel(summary, com.autoscript.studio.compiler.NodeDisplaySettings("重试", true)))
        assertEquals("重试", applyProgramDisplayLabel(summary, com.autoscript.studio.compiler.NodeDisplaySettings("重试", false)))
    }

    @Test fun everyCatalogEntryHandlesEmptyAndUnfinishedStructuredArgumentsWithoutMutation() {
        BlockCatalog.all.forEach { contract ->
            listOf(JsonObject(), JsonObject().apply {
                contract.properties.forEach { add(it.path, JsonObject()) }
            }).forEach { args ->
                val original = args.toString()
                listOf(false, true).forEach { short ->
                    val summary = compactProgramLabel(contract, args, contract.kind, emptyMap(), rowSummary = short)
                    assertTrue(contract.kind, summary.isNotBlank())
                    assertTrue(contract.kind, summary.length <= 261)
                    assertFalse(contract.kind, summary.any { it.isISOControl() })
                }
                assertEquals(contract.kind, original, args.toString())
            }
        }
    }
}
