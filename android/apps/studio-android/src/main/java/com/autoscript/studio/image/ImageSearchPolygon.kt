package com.autoscript.studio

import com.autoscript.project.store.ProjectMaskPoint
import com.autoscript.project.store.polygonMaskSpans
import com.google.gson.JsonArray
import com.google.gson.JsonParser

internal object ImageSearchPolygon {
    val kinds = setOf("vision.findcolor", "vision.countcolor", "vision.findallcolor", "vision.findmulticolor", "vision.findimage", "vision.findallimages",
        "vision.findgray", "vision.findscaled", "vision.findfeatures")

    fun decode(text: String): List<ProjectMaskPoint> {
        if (text.isEmpty()) return emptyList()
        require(text.toByteArray(Charsets.UTF_8).size <= 8192) { "多边形配置过长" }
        val array = JsonParser.parseString(text).asJsonArray
        require(array.size() in 3..128) { "多边形需要3–128个顶点" }
        return array.map { raw ->
            val pair = raw.asJsonArray
            require(pair.size() == 2 && pair.all { it.isJsonPrimitive && it.asJsonPrimitive.isNumber && it.toString().matches(Regex("0|[1-9][0-9]*")) }) { "顶点需要整数坐标" }
            val values = pair.map { it.asString.toIntOrNull()?.takeIf { n -> n in 0..4096 }
                ?: throw IllegalArgumentException("顶点坐标应为0–4096") }
            ProjectMaskPoint(values[0], values[1])
        }.also(::validate)
    }

    fun encode(points: List<ProjectMaskPoint>): String {
        if (points.isEmpty()) return ""
        validate(points)
        return JsonArray().apply { points.forEach { p -> add(JsonArray().apply { add(p.x); add(p.y) }) } }.toString()
    }

    fun bounds(points: List<ProjectMaskPoint>) = ImageToolCodeGen.Roi(
        points.minOf { it.x }, points.minOf { it.y }, points.maxOf { it.x }, points.maxOf { it.y })

    fun validate(points: List<ProjectMaskPoint>) {
        require(points.size in 3..128 && points.distinct().size == points.size) { "需要3–128个不同顶点" }
        fun cross(a: ProjectMaskPoint, b: ProjectMaskPoint, c: ProjectMaskPoint): Long =
            (b.x - a.x).toLong() * (c.y - a.y) - (b.y - a.y).toLong() * (c.x - a.x)
        fun on(a: ProjectMaskPoint, b: ProjectMaskPoint, p: ProjectMaskPoint) = cross(a, b, p) == 0L &&
            p.x in minOf(a.x, b.x)..maxOf(a.x, b.x) && p.y in minOf(a.y, b.y)..maxOf(a.y, b.y)
        var area = 0L
        points.indices.forEach { i ->
            val a = points[i]; val b = points[(i + 1) % points.size]; val c = points[(i + 2) % points.size]
            require(cross(a, b, c) != 0L || !on(a, b, c) && !on(b, c, a)) { "相邻边不能重叠" }
            area += a.x.toLong() * b.y - b.x.toLong() * a.y
            for (j in i + 1 until points.size) {
                if (j == i + 1 || i == 0 && j == points.lastIndex) continue
                val d = points[j]; val e = points[(j + 1) % points.size]
                val crossed = cross(a, b, d) * cross(a, b, e) < 0 && cross(d, e, a) * cross(d, e, b) < 0
                require(!crossed && !on(a, b, d) && !on(a, b, e) && !on(d, e, a) && !on(d, e, b)) { "边不能交叉或相互接触" }
            }
        }
        require(area != 0L) { "多边形没有面积" }
        val rect = bounds(points)
        require((rect.top until rect.bottom).any { polygonMaskSpans(points, it, rect.right).isNotEmpty() }) { "多边形内没有有效像素" }
    }
}
