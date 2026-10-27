package com.autoscript.project.store

data class ResourceReplacement(val path: String, val previousSha256: String, val incomingSha256: String)

sealed class ResourceImportMode {
    object Numbered : ResourceImportMode()
    object Ask : ResourceImportMode()
    data class Replace(val expected: ResourceReplacement) : ResourceImportMode()
}

class ResourceImportConflictException(val replacement: ResourceReplacement) :
    IllegalArgumentException("同名文件已存在，请确认覆盖或编号另存")

fun importedResourcePath(updated: ProjectSnapshot, previousPaths: Set<String>, mode: ResourceImportMode): String =
    (mode as? ResourceImportMode.Replace)?.expected?.path
        ?: (updated.manifest.resources.map { it.get("path").asString }.toSet() - previousPaths).single()
