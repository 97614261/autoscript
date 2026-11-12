package com.autoscript.studio

import com.autoscript.project.store.*
import com.google.gson.JsonObject
import java.io.File
import org.junit.Assert.*
import org.junit.Test

class ScalarValueSourceTest {
    private val variables = listOf(CalculationVariable("次数", ProjectVariableType.INTEGER, "局部"),
        CalculationVariable("数字文本", ProjectVariableType.STRING, "全局"), CalculationVariable("帧", ProjectVariableType.IMAGE, "局部"))

    @Test fun loopInputsPreserveExpressionsAndLegacyUnitsWhenReopened() {
        for (key in listOf("count", "time", "delay", "checkCount", "checkTime")) {
            val draft = applyLoopPickerSelection(LoopDraft(), key, "=int(数字文本) + 次数")
            val result = buildLoopInsertion(draft, variables)
            assertNull(result.error)
            val insertion = requireNotNull(result.insertion)
            val reopened = loopDraftFromArguments(insertion.kind, insertion.arguments)
            assertEquals(insertion.arguments, buildLoopInsertion(reopened, variables).insertion?.arguments)
        }
        val timed = LoopDraft(mode = LoopMode.TIMED, variableMode = true, timeVariable = "=float(数字文本)", unit = LoopTimeUnit.SECONDS)
        val args = requireNotNull(buildLoopInsertion(timed, variables).insertion).arguments
        assertEquals("seconds", args["durationUnit"].asString)
        assertEquals(timed.timeVariable, loopDraftForDialog(loopDraftFromArguments("control.cycle", args)).timeVariable)
    }

    @Test fun inputsAcceptExpressionsButLoopOutputDestinationsRemainNames() {
        assertNull(scalarSourceError("=int(数字文本)+次数", variables, setOf(ProjectVariableType.INTEGER)))
        assertNotNull(scalarSourceError("=其他插件变量+1", variables, scalarValueTypes))
        assertNotNull(scalarSourceError("=帧", variables, scalarValueTypes))
        assertNotNull(scalarSourceError("数字文本", variables, setOf(ProjectVariableType.INTEGER)))
        assertNotNull(scalarSourceError("=" + "中".repeat(342), variables, scalarValueTypes))
        assertNotNull(buildLoopInsertion(LoopDraft(mode = LoopMode.COUNT, countOutput = "=次数+1"), variables).error)
        assertNotNull(buildLoopInsertion(LoopDraft(iterationOutput = "=次数+1"), variables).error)
        assertEquals("次数", scalarSourceBinding(" 次数 ", variables))
        assertEquals("=int(数字文本)", scalarSourceBinding("int(数字文本)", variables))
    }

    @Test fun shortcutsCanComposeConvertedInputsWithoutQuotingThemAsText() {
        val state = QuickCalculationState("+", listOf(CalculationOperand(variable = "=int(数字文本)", type = ProjectVariableType.INTEGER),
            CalculationOperand(variable = "次数", type = ProjectVariableType.INTEGER)))
        val result = quickCalculationResult(state, variables)
        assertNull(result.error)
        assertEquals("(int(数字文本)) + 次数", result.expression)
        assertEquals(ProjectVariableType.INTEGER, result.type)
        assertEquals(state.operands[0], newQuickCalculationState("-", state, variables).operands[0])
        assertNull(decodeQuickCalculation(result.expression!!, variables))
    }

    @Test fun categoriesAreCompleteAndSearchFindsOperationsAcrossCategories() {
        val groups = quickCalculationOperations.map { it.group }.distinct()
        assertEquals(6, groups.size)
        assertEquals(quickCalculationOperations.map { it.id }.toSet(), groups.flatMap { filteredCalculationOperations(it, "") }.map { it.id }.toSet())
        assertTrue(filteredCalculationOperations("常用", "拆分").map { it.id }.containsAll(listOf("split", "splitAny")))
        assertEquals(listOf("itemInt"), filteredCalculationOperations("文本处理", "itemInt").map { it.id })
        assertTrue(filteredCalculationOperations("常用", "不存在操作").isEmpty())
        assertNull(scalarInputTypes("text.length", "resultVariable"))
        assertNull(scalarInputTypes("screen.frameinfo", "frameVariable"))
        assertEquals(setOf(ProjectVariableType.STRING), scalarInputTypes("text.length", "textVariable"))
        assertEquals(setOf(ProjectVariableType.INTEGER), scalarInputTypes("floating.image", "widthVariable"))
        assertNull(scalarInputTypes("system.screensize", "widthVariable"))
        assertEquals(setOf(ProjectVariableType.INTEGER),scalarInputTypes("vision.findimage","leftVariable"))
        assertEquals(setOf(ProjectVariableType.INTEGER),scalarInputTypes("screen.mappoint","xVariable"))
    }

    @Test fun maintenanceSearchAndSummariesUseReferencedNamesInsteadOfEncodedSources() {
        val args = JsonObject().apply {
            addProperty("level", "info"); addProperty("message", "结果")
            addProperty("valueVariable", "=text(次数)..\"数字文本\"")
        }
        val source = """{"flowSchemaVersion":1,"nodeId":"n1","blockId":"root","parentId":null,"orderKey":"a0","kind":"task.log","nodeVersion":1,"args":$args}"""
        val snapshot = ProjectSnapshot(File("."), ProjectManifestDocument(projectId="qa",name="qa", sourceMode=ProjectSourceMode.VISUAL,
            entryFlowId="main",flows=listOf(ProjectFlow("main","visual/flows/main.jsonl","root")),
            variables=listOf(ProjectVariable("次数",ProjectVariableScope.GLOBAL,type=ProjectVariableType.INTEGER))),null,
            flowSources=mapOf("main" to source))
        val editor = VisualEditorState.create(source,"root")
        assertTrue(visualVariableWarnings(editor,snapshot,"main").isEmpty())
        val search = editorProjectSearchIndex(listOf(EditorSearchFlow("main","主脚本",source,"root")))
        assertEquals(1,editorProjectSearch(search,"次数","variable").size)
        assertTrue(editorProjectSearch(search,"数字文本","variable").isEmpty())
        assertEquals("text(次数)..\"数字文本\"",ProgramLabelArguments(args,true).variable("valueVariable"))
        val missing = source.replace("text(次数)","text(缺失)")
        val warnings = visualVariableWarnings(VisualEditorState.create(missing,"root"),snapshot,"main")
        assertEquals(1,warnings.size)
        assertTrue(warnings.single().contains("缺失：计算来源未声明"))
    }

    @Test fun textDialogsAcceptConversionsAndStillRejectOutOfScopeSourcesAndOutputExpressions() {
        for (kind in listOf("clipboard.settext","input.pastetext")) {
            assertNull(runtimeToolArgumentError(kind,mapOf("textVariable" to "=text(次数)"),variables))
            assertNotNull(runtimeToolArgumentError(kind,mapOf("textVariable" to "=text(外部)"),variables))
            assertNotNull(runtimeToolArgumentError(kind,mapOf("textVariable" to "=text(次数)","resultVariable" to "=次数+1"),variables))
        }
        val inputs = blockPropertyTexts(requireNotNull(BlockCatalog.find("floating.show")),JsonObject())
        assertNull(floatingArgumentError("floating.show",inputs+("textVariable" to "=text(次数)"),emptyList(),setOf("数字文本"),variables))
        assertNotNull(floatingArgumentError("floating.show",inputs+("textVariable" to "=text(外部)"),emptyList(),setOf("数字文本"),variables))
    }

    @Test fun imageScalarInputsKeepExpressionsWhileFramesAndOutputsRemainBindings() {
        val contract = requireNotNull(BlockCatalog.find("vision.findimage"))
        val resource = JsonObject().apply { addProperty("kind","image"); addProperty("path","assets/images/demo.png") }
        val inputs = blockPropertyTexts(contract,JsonObject()) + mapOf("imagePath" to "assets/images/demo.png",
            "frameVariable" to "帧","foundVariable" to "找到","xVariable" to "横坐标","yVariable" to "纵坐标",
            "leftVariable" to "=次数+1")
        val result = parseRecognitionArguments(contract,inputs,listOf(resource))
        assertNull(result.second)
        assertEquals("=次数+1",result.first?.get("leftVariable")?.asString)
        assertNotNull(parseRecognitionArguments(contract,inputs+("foundVariable" to "=次数+1"),listOf(resource)).second)
        val mapped = blockPropertyTexts(requireNotNull(BlockCatalog.find("screen.mappoint")),JsonObject()) +
            mapOf("frameVariable" to "帧","resultVariable" to "数字文本","xVariable" to "=次数+1")
        assertNull(visionExtensionArgumentError("screen.mappoint",mapped,variables))
        assertNotNull(visionExtensionArgumentError("screen.mappoint",mapped+("xVariable" to "=外部+1"),variables))
    }
}
