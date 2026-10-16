package com.autoscript.studio

internal const val EDITOR_PROGRAM_ROW_MIN_HEIGHT_DP = 28
internal const val EDITOR_PROGRAM_ROW_PADDING_DP = 3

internal data class CompactProgramRowText(
    val title: String,
    val details: String,
    val branch: String?,
    val disabled: Boolean,
    val inheritedDisabled: Boolean = false,
)

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
    branch: String?, disabled: Boolean, inheritedDisabled: Boolean): CompactProgramRowText = CompactProgramRowText(
    title = settings.alias.takeIf(String::isNotBlank) ?: defaultLabel.substringBefore(" · "),
    details = if (settings.alias.isNotBlank() && !settings.showParameters) "" else defaultLabel.substringAfter(" · ", ""),
    branch = branch,
    disabled = disabled || inheritedDisabled,
    inheritedDisabled = !disabled && inheritedDisabled,
)
