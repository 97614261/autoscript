package com.autoscript.studio

import com.google.gson.JsonObject

internal fun projectImageLibraryPaths(resources: List<JsonObject>): List<String> = resources.mapNotNull {
    it.get("path")?.asString?.takeIf { path ->
        it.get("kind")?.asString == "image" && path.startsWith("assets/images/")
    }
}.distinct()

/** null is all folders; the empty string is specifically the image root. */
internal fun imageLibraryPaths(paths: List<String>, folder: String?, search: String): List<String> = paths
    .filter { path ->
        val relative = path.removePrefix("assets/images/")
        (folder == null || relative.substringBeforeLast('/', "") == folder) &&
            relative.contains(search.trim(), ignoreCase = true)
    }
    .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.removePrefix("assets/images/") })
