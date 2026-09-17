package com.autoscript.studio

import java.io.File
import com.autoscript.project.store.ProjectSnapshot

internal enum class StudioProjectFileKind(val label: String, val symbol: String) {
    MANIFEST("项目配置", "⚙"),
    LUA("Lua 脚本", "{ }"),
    FLOW("积木流程", "▦"),
    IMAGE("图片", "▧"),
    GLYPH_DICTIONARY("字库", "字"),
}

internal data class StudioProjectFile(
    val path: String,
    val kind: StudioProjectFileKind,
    val sizeBytes: Long,
    val opensEditor: Boolean,
    /** 磁盘修改时间；文件缺失时为 null。 */
    val lastModified: Long? = null,
)

internal fun projectFileCatalog(snapshot: ProjectSnapshot): List<StudioProjectFile> = buildList {
    add(projectFile(snapshot.directory, "project.json", StudioProjectFileKind.MANIFEST, opensEditor = false))
    snapshot.manifest.entryPoint?.let { path ->
        add(projectFile(snapshot.directory, path, StudioProjectFileKind.LUA, opensEditor = true))
    }
    snapshot.manifest.flows.sortedBy { it.path }.forEach { flow ->
        add(projectFile(snapshot.directory, flow.path, StudioProjectFileKind.FLOW, opensEditor = true))
    }
    snapshot.manifest.resources.mapNotNull { declaration ->
        val path = declaration.get("path")?.takeIf { it.isJsonPrimitive }?.asString ?: return@mapNotNull null
        val kind = when (declaration.get("kind")?.asString) {
            "image" -> StudioProjectFileKind.IMAGE
            "glyphDictionary", "glyph_dictionary" -> StudioProjectFileKind.GLYPH_DICTIONARY
            else -> return@mapNotNull null
        }
        projectFile(snapshot.directory, path, kind, opensEditor = false)
    }.sortedBy { it.path }.forEach(::add)
}

private fun projectFile(
    root: File,
    relativePath: String,
    kind: StudioProjectFileKind,
    opensEditor: Boolean,
): StudioProjectFile {
    val canonicalRoot = root.canonicalFile
    val target = File(canonicalRoot, relativePath).canonicalFile
    require(target.toPath().startsWith(canonicalRoot.toPath())) { "项目文件越过项目目录：$relativePath" }
    val exists = target.isFile
    return StudioProjectFile(
        path = relativePath,
        kind = kind,
        sizeBytes = if (exists) target.length() else 0L,
        opensEditor = opensEditor,
        lastModified = if (exists) target.lastModified() else null,
    )
}
