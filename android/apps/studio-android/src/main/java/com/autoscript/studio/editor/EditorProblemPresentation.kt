package com.autoscript.studio

import com.autoscript.runtime.client.VisualCompileDiagnostic

internal data class EditorProblemAdvice(val summary: String, val suggestion: String)
internal data class EditorProblemTarget(val flowId: String, val flowName: String, val nodeId: String,
    val source: String, val line: Int, val title: String)

private val editorKnownProblems = linkedMapOf(
        "ROOT_STOP_CLEANUP_FAILED" to EditorProblemAdvice("停止后未确认触点已松开，已暂停后续输入", "先重新连接Root，再确认运行环境恢复；不要反复点运行补发触控。"),
        "CAPTURE_BACKEND_FAILED" to EditorProblemAdvice("截图后端没有采到画面", "打开“我的→权限与运行环境”查看截图诊断；Root已授权也可能采集失败，不要仅反复授权。"),
        "SCREEN_CAPTURE_FAILED" to EditorProblemAdvice("本次截图失败", "先检查运行环境的Root连接和截图诊断；不要把上一次图片当作本次截图。"),
        "ROOT_UNAVAILABLE" to EditorProblemAdvice("Root连接尚不可用", "在运行环境重新连接并等待Root就绪，再重试。"),
        "MISSING_CAPABILITY" to EditorProblemAdvice("这行需要的脚本能力没有启用", "打开项目设置，启用原始详情指出的能力后再校验。"),
        "UNKNOWN_TARGET_FLOW" to EditorProblemAdvice("调用的插件不存在", "打开这行参数，重新选择项目中实际存在的目标插件。"),
        "INVALID_NODE_ARGUMENTS" to EditorProblemAdvice("这行有参数不符合要求", "打开参数检查输入范围、变量类型和所选图片；具体原因见原始详情。"),
        "MISSING_ARGUMENT" to EditorProblemAdvice("这行缺少必填参数", "打开参数，补全原始详情指出的项目。"),
        "ARGUMENT_TYPE_MISMATCH" to EditorProblemAdvice("传入参数的类型不匹配", "检查目标插件的参数声明，数字、文字、真假值需要对应。"),
        "UNKNOWN_ARGUMENT" to EditorProblemAdvice("传入了目标插件没有声明的参数", "重新选择目标插件，并检查调用参数。"),
        "RECURSIVE_FLOW_CALL" to EditorProblemAdvice("插件之间形成了循环调用", "检查调用链：A调用B、B又调用A。需要重复执行时使用循环积木。"),
        "UNSUPPORTED_NODE_KIND" to EditorProblemAdvice("当前版本不支持这种积木", "保留备份并检查版本，不要直接删除整段流程。"),
        "HOST_TIMEOUT" to EditorProblemAdvice("这次操作等待超时", "先查看是哪项操作超时及运行环境状态，不要自动重放点击或输入。"),
        "UNSUPPORTED_SOURCE_MODE" to EditorProblemAdvice("这个项目不是当前可编辑的可视化格式", "保留原项目或导出备份，不要把生成Lua直接当作积木源码导入。"),
        "RUNTIME_API_REQUIREMENT" to EditorProblemAdvice("运行引擎版本不满足项目要求", "检查App与运行服务版本，更新匹配版本后重新校验，不要手动降低项目版本字段。"),
        "MISSING_FLOW" to EditorProblemAdvice("项目缺少一个插件文件", "核对插件列表与备份，恢复缺失文件；不要用空插件替代后继续运行。"),
        "UNEXPECTED_FLOW" to EditorProblemAdvice("待校验内容包含项目之外的插件", "关闭并重新打开项目，按当前插件列表重新提交。"),
        "DUPLICATE_FLOW_SOURCE" to EditorProblemAdvice("同一个插件被重复提交", "重新打开项目后再校验，原草稿先保留或备份。"),
        "SOURCE_BYTES_MISMATCH" to EditorProblemAdvice("插件内容与已记录的摘要不一致", "重新读取并校验实际插件，不继续使用旧编译结果。"),
        "FLOW_NOT_COMPILABLE" to EditorProblemAdvice("有插件结构无法编译", "查看原始详情定位插件；修复结构后再运行，不跳过有问题的插件。"),
        "PROJECT_STRUCTURE" to EditorProblemAdvice("项目清单或插件引用不完整", "核对主插件、插件引用、变量和资源；必要时从完整备份恢复。"),
        "SERIALIZATION" to EditorProblemAdvice("无法生成完整编译结果", "保留草稿后重新打开项目再校验；不要把不完整结果当作成功。"),
        "LUA_SYNTAX" to EditorProblemAdvice("生成的脚本未通过语法检查", "先保留项目与原始详情，核对最近修改的积木或受限源码，不直接修改生成Lua。"),
        "ResourceLimitExceeded" to EditorProblemAdvice("插件超过允许的资源预算", "缩小单插件内容或拆分插件，不截断源码后继续运行。"),
        "InvalidUtf8" to EditorProblemAdvice("插件文字编码无法读取", "从完整备份恢复UTF-8插件文件，不直接忽略坏字节。"),
        "BlankLine" to EditorProblemAdvice("插件数据中存在不允许的空行", "在插件源码中修复对应位置，保留原始备份。"),
        "InvalidJson" to EditorProblemAdvice("这行积木数据格式不完整", "检查原始详情；从受限源码编辑入口修复，或撤销最近修改。"),
        "UnsupportedFlowSchema" to EditorProblemAdvice("当前版本不支持此插件格式", "检查导入来源与App版本，先保留完整备份。"),
        "FlowSchemaVersionMismatch" to EditorProblemAdvice("插件格式版本与清单不匹配", "重新导入完整备份或更新匹配版本，不只改版本数字。"),
        "UnknownNodeKind" to EditorProblemAdvice("这行函数类型无法识别", "核对来源与版本，不自动替换成其他函数。"),
        "UnsupportedNodeVersion" to EditorProblemAdvice("这行积木版本暂不支持", "更新匹配的App版本，原积木与子节点保持不动。"),
        "DuplicateNodeId" to EditorProblemAdvice("插件里有重复的积木身份", "撤销手动复制的源码，改用编辑窗复制；不要只改显示行号。"),
        "InvalidOrderKey" to EditorProblemAdvice("积木排序信息不合法", "撤销最近的源码修改或恢复备份，不随意重排子区。"),
        "DuplicateOrderKey" to EditorProblemAdvice("同级积木的排序信息重复", "使用可视化移动或粘贴操作，不直接复制内部排序字段。"),
        "BlockOwnershipConflict" to EditorProblemAdvice("一个子区被多个积木同时占用", "核对容器层级或恢复备份，不共用内部子区身份。"),
        "MissingBlockOwner" to EditorProblemAdvice("子积木找不到所属容器", "恢复完整父子结构，不只复制某几行内部数据。"),
        "RootNodeHasParent" to EditorProblemAdvice("顶层积木错误地指向父节点", "核对插件源码层级或撤销最近结构修改。"),
        "ParentBlockMismatch" to EditorProblemAdvice("积木的父节点和所属子区对不上", "撤销结构修改，改用编辑窗的缩进、移动和粘贴操作。"),
        "DanglingParent" to EditorProblemAdvice("积木引用的父节点已经不存在", "恢复完整子树或撤销删除，不用不存在的ID补位。"),
        "StructuralCycle" to EditorProblemAdvice("积木层级形成了环", "父积木不能放进自己的子树；撤销最近结构修改。"),
        "DepthMismatch" to EditorProblemAdvice("记录的缩进与真实层级不一致", "使用可视化层级操作；不要只修改显示缩进数字。"),
        "NonCanonicalLineOrder" to EditorProblemAdvice("积木数据顺序与真实层级不一致", "撤销直接调换源码行的修改，改用可视化移动操作。"),
)
private val editorProblemTokens = editorKnownProblems.keys.associateWith {
    Regex("(?<![A-Za-z0-9_])${Regex.escape(it)}(?![A-Za-z0-9_])")
}

internal fun editorProblemAdvice(message: String, code: String? = null): EditorProblemAdvice? {
    if (code != null) editorKnownProblems[code]?.let { return it }
    return editorKnownProblems.entries.firstOrNull { (key, _) -> editorProblemTokens.getValue(key).containsMatchIn(message) }?.value
}

/** Resolve exact identities, or an explicit plugin's source line; never guess another plugin. */
internal fun editorProblemTarget(diagnostic: VisualCompileDiagnostic, flows: List<EditorSearchFlow>): EditorProblemTarget? {
    val candidates = flows.filter { diagnostic.flowId == null || it.id == diagnostic.flowId }.mapNotNull { flow ->
        val rows = parseVisualNodes(flow.source, flow.root)
        val row = if (diagnostic.nodeId != null) rows.singleOrNull { it.nodeId == diagnostic.nodeId }
            else if (diagnostic.flowId != null) rows.singleOrNull { it.sourceLine == diagnostic.line } else null
        row?.let { EditorProblemTarget(flow.id, flow.name, it.nodeId, flow.source, it.sourceLine, BlockCatalog.find(it.kind)?.title ?: it.kind) }
    }
    return candidates.singleOrNull()
}
