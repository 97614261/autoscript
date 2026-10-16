package com.autoscript.studio

/** Locations are relative to assets/images; the store remains the authoritative boundary. */
internal fun imageChildFolders(folders: List<String>, current: String): List<String> =
    folders.filter { it.isNotEmpty() && it.substringBeforeLast('/', "") == current }.distinct().sorted()

internal fun newImageFolderPath(parent: String, name: String, existing: List<String>): String {
    val segment = name.trim()
    require(segment.length in 1..40 && segment != "." && segment != ".." &&
        segment.all { it.isLetterOrDigit() || it in "._-" }) {
        "文件夹名称限40字，可用文字、数字、点、横线和下划线"
    }
    val path = listOf(parent, segment).filter(String::isNotEmpty).joinToString("/")
    val segments = path.split('/')
    require(path.length <= 120 && segments.size <= 4 && segments.all {
        it.length in 1..40 && it != "." && it != ".." && it.all { c -> c.isLetterOrDigit() || c in "._-" }
    }) { "图片目录路径限120字、最多四层" }
    require(path !in existing) { "当前目录已有同名文件夹" }
    return path
}

internal fun imageSaveStem(name: String): String = name.trim().let {
    if (it.endsWith(".png", ignoreCase = true)) it.dropLast(4) else it
}

internal fun imageSaveFileName(name: String): String {
    val stem = imageSaveStem(name)
    require(stem.length in 1..80 && stem == stem.trim('.', '_', '-') &&
        stem.all { it.isLetterOrDigit() || it in "._-" }) {
        "图片名称限80字，可用文字、数字、点、横线和下划线；首尾需为文字或数字"
    }
    return "$stem.png"
}

internal fun imageSaveNameExists(folder: String, name: String, imagePaths: List<String>): Boolean =
    "assets/images/${if (folder.isEmpty()) "" else "$folder/"}$name" in imagePaths
