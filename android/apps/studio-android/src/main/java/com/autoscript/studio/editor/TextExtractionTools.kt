package com.autoscript.studio

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.autoscript.core.designsystem.AutoScriptPalette

internal const val TEXT_EXTRACTION_ENTRY = "studio.text-extraction"
internal val textExtractionTools = listOf(
    "text.regexall" to "正则提取全部",
    "text.regexextract" to "正则提取首项",
    "text.regexmatch" to "正则匹配判断",
    "text.regexreplace" to "正则替换",
    "text.betweennth" to "取两段标记之间的文本",
    "text.split" to "按分隔符拆分",
    "text.replace" to "替换文本",
    "text.numberat" to "按顺序提取数字",
)
internal val textExtractionKinds = textExtractionTools.map { it.first }.toSet()
internal val textRuleKinds = setOf("text.regexall", "text.regexextract", "text.regexmatch", "text.regexreplace")

internal fun textRegexResultHint(kind: String): String = when (kind) {
    "text.regexall" -> "全部匹配写入 JSON 文本列表，无匹配为 []。"
    "text.regexextract" -> "首个匹配写入文本变量，无匹配为空文本。"
    "text.regexmatch" -> "文本中有匹配返回整数 1，否则为 0；整段匹配请用 ^ 和 $。"
    "text.regexreplace" -> "所有匹配替换为填写的文字；无匹配保留原文，$1 等不展开。"
    else -> error("Not a regex text tool: $kind")
}

internal data class TextExtractionRule(val id: String, val label: String, val pattern: String)
internal val textExtractionRules = listOf(
    TextExtractionRule("decimal", "数字（含正负号、小数）", "[+-]?(?:[0-9]+(?:\\.[0-9]+)?|\\.[0-9]+)"),
    TextExtractionRule("digits", "连续数字", "[0-9]+"),
    TextExtractionRule("letters", "英文字母", "[A-Za-z]+"),
    TextExtractionRule("alphanumeric", "字母与数字", "[A-Za-z0-9]+"),
    TextExtractionRule("chinese", "中文", "\\p{Han}+"),
    TextExtractionRule("tokens", "按空白分开的内容", "\\S+"),
)

/** A preset changes only the rule; the user's source and result bindings stay intact. */
internal fun applyTextExtractionRule(inputs: Map<String, String>, id: String): Map<String, String> {
    val rule = textExtractionRules.firstOrNull { it.id == id }
    require(rule != null || id == "custom")
    return inputs + ("patternVariable" to "") + (rule?.let { mapOf("pattern" to it.pattern) } ?: emptyMap())
}

@Composable
internal fun TextExtractionToolsDialog(onDismiss: () -> Unit, onCreate: (String) -> Unit) {
    StudioPanelDialog("通用文本处理", onDismiss, showFooter = false) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text("处理任意文本或文本变量；识别结果、文件内容、接口返回都可作为来源。", fontSize = 11.sp)
            textExtractionTools.forEach { (kind, label) ->
                TextButton({ onCreate(kind) }, modifier = Modifier.fillMaxWidth(),
                    contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp)) {
                    Text(label, fontSize = 12.sp)
                }
            }
            Text("函数库 → 文本 → 正则也可直接加入。支持常用规则、自定义正则和变量规则；提取全部返回 JSON 文本列表，可用集合工具读取。",
                fontSize = 10.sp, color = AutoScriptPalette.TextSecondary)
        }
    }
}
