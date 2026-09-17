package com.autoscript.studio

/**
 * Lua 编辑器插入片段的守门：只允许调用 R0 契约里真实存在的脚本 API。
 *
 * 悬浮面板的图像/工具/循环/常用等弹窗是按参考产品版式复刻的，它们生成的片段里曾包含
 * `Vision.*`、`Capture.*`、`Runtime.*`、`Log.*`、`Net.*` 这类**本项目没有的**函数；
 * 可视化项目会落到积木选择器所以没事，Lua 项目却会把它原样写进 `main.lua`，
 * 运行时报 `attempt to index global 'Vision'`。这里在写入前统一拦下并说明原因。
 *
 * 允许的命名空间直接从 [LegacyFunctionCatalog.luaGroups] 的真实片段推导，
 * 契约增删 API 时不需要再改这里。
 */
internal object LuaSnippetGate {
    private val allowedNamespaces: Set<String> by lazy {
        LegacyFunctionCatalog.luaGroups()
            .flatMap { group -> group.entries }
            .mapNotNull { entry -> NAMESPACE_CALL.find(entry.snippet)?.groupValues?.get(1) }
            .toSet()
    }

    /** 返回 `null` 表示可以插入；否则是给用户看的拒绝原因。 */
    fun reject(snippet: String): String? {
        val offending = NAMESPACE_CALL.findAll(stripStringsAndComments(snippet))
            .map { it.groupValues[1] to it.groupValues[2] }
            .filter { (namespace, _) -> namespace !in allowedNamespaces }
            .map { (namespace, function) -> "$namespace.$function" }
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
