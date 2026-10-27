package com.autoscript.studio

/** Stable file/group identities, independent of filtering, sorting and row positions. */
internal data class PluginFileSelection(val files: List<SourceFileEntry>, val groups: List<String>) {
    val singleFile: SourceFileEntry? get() = files.singleOrNull()?.takeIf { groups.isEmpty() }
    val singleGroup: String? get() = groups.singleOrNull()?.takeIf { files.isEmpty() }
    val canDelete: Boolean get() = (files.isNotEmpty() || groups.isNotEmpty()) && files.none { it.isEntry }
    val canGroup: Boolean get() = files.isNotEmpty() && groups.isEmpty()
    val groupedFlowIds: List<String> get() = files.filter { it.group != null }.map { it.flowId }
}

internal fun pluginFileSelection(tree: SourceFileTree, keys: List<String>): PluginFileSelection = PluginFileSelection(
    keys.distinct().mapNotNull { key -> key.takeIf { it.startsWith("f:") }?.removePrefix("f:")?.let(tree::entry) },
    keys.distinct().mapNotNull { key -> key.takeIf { it.startsWith("g:") }?.removePrefix("g:") }
        .filter { name -> tree.groups.any { it.name == name } },
)

internal fun pluginFileSelectedKeys(visible: List<String>, selected: List<String>, select: Boolean): List<String> {
    val visibleSet = visible.toSet()
    return if (select) (selected + visible).distinct() else selected.filterNot { it in visibleSet }
}

/** Mirrors ProjectStore's existing plugin/group name policy; Store remains the final authority. */
internal fun pluginFileNameError(value: String, existing: Collection<String>, original: String? = null, allowExisting: Boolean = false): String? {
    val name = value.trim()
    fun edge(char: Char) = char in 'a'..'z' || char in 'A'..'Z' || char in '0'..'9' || char == '_' || char == '-' || char in '一'..'鿿'
    return when {
        name.isEmpty() -> "名称不能为空"
        name.length > 64 -> "名称最多64个字符"
        !edge(name.first()) || !edge(name.last()) || name.any { !edge(it) && it != '.' && it != ' ' } ->
            "使用中文、字母、数字、下划线或短横线；空格和点不能在首尾"
        !allowExisting && name != original && name in existing -> "名称已存在"
        else -> null
    }
}

internal fun nextPluginFileName(tree: SourceFileTree, prefix: String): String {
    val names = tree.entries.map { it.name }.toSet() + tree.groups.map { it.name }
    val base = prefix.take(60)
    if (base !in names) return base
    var index = 1
    while ("$base$index" in names) index++
    return "$base$index"
}
