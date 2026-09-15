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
)

internal fun projectFileCatalog(snapshot: ProjectSnapshot): List<StudioProjectFile> = buildList {
    add(
        StudioProjectFile(
            path = "project.json",
            kind = StudioProjectFileKind.MANIFEST,
            sizeBytes = projectFileSize(snapshot.directory, "project.json"),
            opensEditor = false,
        ),
    )
    snapshot.manifest.entryPoint?.let { path ->
        add(
            StudioProjectFile(
                path = path,
                kind = StudioProjectFileKind.LUA,
                sizeBytes = projectFileSize(snapshot.directory, path),
                opensEditor = true,
            ),
        )
    }
    snapshot.manifest.flows.sortedBy { it.path }.forEach { flow ->
        add(
            StudioProjectFile(
                path = flow.path,
                kind = StudioProjectFileKind.FLOW,
                sizeBytes = projectFileSize(snapshot.directory, flow.path),
                opensEditor = true,
            ),
        )
    }
    snapshot.manifest.resources.mapNotNull { declaration ->
        val path = declaration.get("path")?.takeIf { it.isJsonPrimitive }?.asString ?: return@mapNotNull null
        val kind = when (declaration.get("kind")?.asString) {
            "image" -> StudioProjectFileKind.IMAGE
            "glyph_dictionary" -> StudioProjectFileKind.GLYPH_DICTIONARY
            else -> return@mapNotNull null
        }
        StudioProjectFile(
            path = path,
            kind = kind,
            sizeBytes = projectFileSize(snapshot.directory, path),
            opensEditor = false,
        )
    }.sortedBy { it.path }.forEach(::add)
}

private fun projectFileSize(root: File, relativePath: String): Long {
    val canonicalRoot = root.canonicalFile
    val target = File(canonicalRoot, relativePath).canonicalFile
    require(target.toPath().startsWith(canonicalRoot.toPath())) { "项目文件越过项目目录：$relativePath" }
    return target.takeIf(File::isFile)?.length() ?: 0L
}
