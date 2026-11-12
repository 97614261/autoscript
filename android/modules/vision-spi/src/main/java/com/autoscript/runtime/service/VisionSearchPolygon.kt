package com.autoscript.runtime.service

/** Optional providers must opt in so an old component cannot silently ignore the range. */
interface NativePolygonSearchBackend {
    fun supportsPolygonSearch(): Boolean = true
}

class NativePolygonSearchUnsupported : IllegalStateException("POLYGON_REGION_UNSUPPORTED")

/** Pixel-centre scanlines; contains geometry only, never captured pixels. */
class VisionSearchPolygon private constructor(
    val left: Int, val top: Int, val right: Int, val bottom: Int,
    private val rows: List<List<IntRange>>,
) {
    fun spans(y: Int): List<IntRange> = rows.getOrNull(y - top).orEmpty()

    fun containsRect(x: Int, y: Int, width: Int, height: Int): Boolean =
        width > 0 && height > 0 && x >= left && y >= top &&
            x.toLong() + width <= right && y.toLong() + height <= bottom &&
            (y until y + height).all { row -> spans(row).any { x >= it.first && x + width - 1 <= it.last } }

    /** Erode by the template footprint once, instead of walking its height for every probe. */
    fun templateOrigins(width: Int, height: Int, check: () -> Unit): VisionSearchPolygon {
        require(width > 0 && height > 0)
        if (width > right - left || height > bottom - top) return VisionSearchPolygon(left, top, right, bottom, emptyList())
        var budget = 64_000_000L
        fun charge(n: Int) { budget -= n; require(budget >= 0) { "POLYGON_GEOMETRY_BUDGET" }; check() }
        val shrunk = rows.map { row ->
            charge(row.size + 1)
            row.mapNotNull { if (it.last - it.first + 1 >= width) it.first..it.last - width + 1 else null }
        }
        val origins = (0..rows.size - height).map { start ->
            charge(shrunk[start].size + 1)
            var spans = shrunk[start]
            for (j in start + 1 until start + height) {
                if (spans.isEmpty()) break
                val next = shrunk[j]
                charge(spans.size + next.size + 1)
                val intersection = ArrayList<IntRange>()
                var a = 0; var b = 0
                while (a < spans.size && b < next.size) {
                    val l = maxOf(spans[a].first, next[b].first); val r = minOf(spans[a].last, next[b].last)
                    if (l <= r) intersection.add(l..r)
                    if (spans[a].last < next[b].last) a++ else b++
                }
                spans = intersection
            }
            spans
        }
        return VisionSearchPolygon(left, top, right - width + 1, bottom - height + 1, origins)
    }

    companion object {
        const val OPTIONS_MARKER = -203

        fun fromOptions(options: IntArray, check: () -> Unit = {}): VisionSearchPolygon? {
            if (options.size == 5) return null
            require(options.size >= 13 && options[5] == OPTIONS_MARKER)
            val count = options[6]
            require(count in 3..128 && options.size == 7 + count * 2)
            val points = (0 until count).map { options[7 + it * 2] to options[8 + it * 2] }
            require(points.distinct().size == count && points.all { it.first in 0..4096 && it.second in 0..4096 })
            fun cross(a: Pair<Int,Int>, b: Pair<Int,Int>, c: Pair<Int,Int>) =
                (b.first - a.first).toLong() * (c.second - a.second) - (b.second - a.second).toLong() * (c.first - a.first)
            fun on(a: Pair<Int,Int>, b: Pair<Int,Int>, p: Pair<Int,Int>) = cross(a,b,p) == 0L &&
                p.first in minOf(a.first,b.first)..maxOf(a.first,b.first) && p.second in minOf(a.second,b.second)..maxOf(a.second,b.second)
            var area = 0L
            for (i in points.indices) {
                check()
                val a = points[i]; val b = points[(i + 1) % count]; val c = points[(i + 2) % count]
                require(cross(a,b,c) != 0L || !on(a,b,c) && !on(b,c,a))
                area += a.first.toLong() * b.second - b.first.toLong() * a.second
                for (j in i + 1 until count) {
                    if (j == i + 1 || i == 0 && j == count - 1) continue
                    val d = points[j]; val e = points[(j + 1) % count]
                    require(!(cross(a,b,d) * cross(a,b,e) < 0 && cross(d,e,a) * cross(d,e,b) < 0) &&
                        !on(a,b,d) && !on(a,b,e) && !on(d,e,a) && !on(d,e,b))
                }
            }
            require(area != 0L)
            val l = points.minOf { it.first }; val t = points.minOf { it.second }
            val r = points.maxOf { it.first }; val b = points.maxOf { it.second }
            require(l >= options[0] && t >= options[1] && r <= options[2] && b <= options[3])
            val rows = (t until b).map { y ->
                check()
                val crossings = points.indices.mapNotNull { i ->
                    var a = points[i]; var z = points[(i + 1) % count]
                    if (a.second > z.second) { val swap = a; a = z; z = swap }
                    val cy = 2L * y + 1
                    if (cy < 2L * a.second || cy >= 2L * z.second) null else {
                        val dy = (z.second - a.second).toLong()
                        (2L * a.first * dy + (cy - 2L * a.second) * (z.first - a.first) - dy) to (2L * dy)
                    }
                }.sortedWith { a, z -> (a.first * z.second).compareTo(z.first * a.second) }
                fun ceil(v: Pair<Long,Long>) = (-Math.floorDiv(-v.first, v.second)).toInt()
                crossings.chunked(2).mapNotNull { pair ->
                    val start = ceil(pair[0]); val end = ceil(pair[1])
                    if (start < end) start until end else null
                }
            }
            require(rows.any { it.isNotEmpty() })
            return VisionSearchPolygon(l,t,r,b,rows)
        }
    }
}
