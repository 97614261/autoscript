package com.autoscript.studio

import com.google.gson.JsonArray
import com.google.gson.JsonObject

/** Screenshot selections are raw frame pixels, matching the visual vision-node contracts. */
internal fun visualImageToolDraft(selection: ImageToolCodeGen.VisualSelection): Pair<String, JsonObject>? {
    if (selection.frameWidth <= 0 || selection.frameHeight <= 0) return null
    val region = selection.roi ?: ImageToolCodeGen.Roi(0, 0, selection.frameWidth, selection.frameHeight)
    val regionJson = JsonObject().apply {
        addProperty("left", region.left)
        addProperty("top", region.top)
        addProperty("right", region.right)
        addProperty("bottom", region.bottom)
    }
    return when (selection.mode) {
        ImageToolMode.COLOR -> selection.points.firstOrNull()?.let { point ->
            "vision.findcolor" to JsonObject().apply {
                addProperty("rgb", point.rgb and 0xFFFFFF)
                addProperty("tolerance", selection.tolerance)
                add("region", regionJson)
            }
        }
        ImageToolMode.MULTI_COLOR -> {
            val anchor = selection.points.firstOrNull() ?: return null
            val samples = selection.points.drop(1)
            if (samples.isEmpty()) return null
            "vision.findmulticolor" to JsonObject().apply {
                addProperty("anchorRgb", anchor.rgb and 0xFFFFFF)
                addProperty("anchorTolerance", selection.tolerance)
                add("samples", JsonArray().apply {
                    samples.forEach { point ->
                        add(JsonObject().apply {
                            addProperty("x", point.x - anchor.x)
                            addProperty("y", point.y - anchor.y)
                            addProperty("rgb", point.rgb and 0xFFFFFF)
                            addProperty("tolerance", selection.tolerance)
                        })
                    }
                })
                add("region", regionJson)
            }
        }
        else -> null
    }
}

internal fun JsonObject.withImageToolValues(overrides: JsonObject): JsonObject = deepCopy().apply {
    overrides.entrySet().forEach { (name, value) -> add(name, value.deepCopy()) }
}
