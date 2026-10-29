package com.autoscript.studio

internal const val EDITOR_PROGRAM_ROW_MIN_HEIGHT_DP = 28
internal const val EDITOR_PROGRAM_ROW_PADDING_DP = 3

internal data class CompactProgramRowText(
    val title: String,
    val details: String,
    val branch: String?,
    val disabled: Boolean,
    val inheritedDisabled: Boolean = false,
    val branchHasHeading: Boolean = false,
) {
    val disabledPrefix: String get() = if (disabled && !inheritedDisabled) "已注释  " else ""
}

/** Standalone headings and body indentation already identify these slots. */
internal fun compactProgramBranchLabel(branch: String?, hasHeading: Boolean = false): String? = if (hasHeading) null else when (branch) {
    null, "循环体" -> null
    "成功分支" -> if (hasHeading) null else "成功"
    "失败分支" -> if (hasHeading) null else "失败"
    else -> branch.replace("否则如果 ", "否则若")
}

/** Presentation only; never parse these labels back into executable arguments. */
internal fun compactProgramRowText(label: String): CompactProgramRowText {
    val inheritedDisabled = label.startsWith("[随父级禁用] ")
    val disabled = inheritedDisabled || label.startsWith("[已禁用] ")
    var content = label.removePrefix("[已禁用] ").removePrefix("[随父级禁用] ")
    val branch = if (content.startsWith('[') && "] " in content) content.substringBefore("] ").removePrefix("[") else null
    if (branch != null) content = content.substringAfter("] ")
    return CompactProgramRowText(
        title = content.substringBefore(" · "),
        details = if (" · " in content) content.substringAfter(" · ") else "",
        branch = branch,
        disabled = disabled,
        inheritedDisabled = inheritedDisabled,
    )
}

internal fun displayProgramRowText(defaultLabel: String, settings: com.autoscript.studio.compiler.NodeDisplaySettings,
    branch: String?, disabled: Boolean, inheritedDisabled: Boolean,
    branchHasHeading: Boolean = false): CompactProgramRowText = CompactProgramRowText(
    title = settings.alias.takeIf(String::isNotBlank) ?: defaultLabel.substringBefore(" · "),
    details = if (settings.alias.isNotBlank() && !settings.showParameters) "" else defaultLabel.substringAfter(" · ", ""),
    branch = branch,
    disabled = disabled || inheritedDisabled,
    inheritedDisabled = !disabled && inheritedDisabled,
    branchHasHeading = branchHasHeading,
)
