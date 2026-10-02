package com.autoscript.studio

import com.autoscript.runtime.api.generated.GeneratedApiContracts

/**
 * Lua 编辑器插入片段的守门：只允许调用 R0 契约里真实存在的脚本 API。
 *
 * 悬浮面板的图像/工具/循环/常用等弹窗是按参考产品版式复刻的，它们生成的片段里曾包含
 * `Vision.*`、`Capture.*`、`Runtime.*`、`Net.*` 这类**本项目没有的**函数；
 * 可视化项目会落到积木选择器所以没事，Lua 项目却会把它原样写进 `main.lua`，
 * 运行时报 `attempt to index global 'Vision'`。这里在写入前统一拦下并说明原因。
 *
 * 白名单精确到函数名，而不是只判断 `Screen` / `Input` 这类命名空间。
 * 否则 `Screen.notImplemented()` 也会通过，方法库一旦误配就会给用户插入必然运行失败的代码。
 * `Log.info/warn/error` 是运行时提供的 `Log.write` 便捷封装；`Prompt.show` 是用户可见的运行提示，故与契约函数一并列出。
 */
internal object LuaSnippetGate {
    private val allowedCalls = GeneratedApiContracts.all.map { it.name }.toSet() +
        setOf("Log.info", "Log.warn", "Log.error")

    /** 返回 `null` 表示可以插入；否则是给用户看的拒绝原因。 */
    fun reject(snippet: String): String? {
        val offending = NAMESPACE_CALL.findAll(stripStringsAndComments(snippet))
            .map { it.groupValues[1] to it.groupValues[2] }
            .map { (namespace, function) -> "$namespace.$function" }
            .filter { it !in allowedCalls }
            .distinct()
            .toList()
        if (offending.isEmpty()) return null
        return "${offending.joinToString("、")} 不在当前脚本 API 契约里（尚未实现），已阻止插入"
    }

    /** 只看代码：字符串字面量和注释里的 `X.y(` 不算调用。 */
    private fun stripStringsAndComments(source: String): String = source
        .replace(BLOCK_COMMENT, " ")
        .lineSequence()
        .joinToString("\n") { line -> line.replace(STRING_LITERAL, "\"\"").substringBefore("--") }

    /** `Screen.findImage(`：大写开头的命名空间 + 点 + 函数名 + 左括号。 */
    private val NAMESPACE_CALL = Regex("""\b([A-Z][A-Za-z0-9]*)\.([A-Za-z_][A-Za-z0-9_]*)\s*\(""")
    private val STRING_LITERAL = Regex(""""(?:\\.|[^"\\])*"|'(?:\\.|[^'\\])*'""")
    private val BLOCK_COMMENT = Regex("""--\[\[[\s\S]*?]]""")
}
