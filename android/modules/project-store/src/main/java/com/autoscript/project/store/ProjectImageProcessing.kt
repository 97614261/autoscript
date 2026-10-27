package com.autoscript.project.store

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import kotlin.math.abs
import kotlin.math.max

data class ProjectMaskPoint(val x:Int,val y:Int) {
    init {require(x in 0..4096 && y in 0..4096)}
}
data class ProjectMaskStroke(val kind:String,val keep:Boolean,val radius:Int,val points:List<ProjectMaskPoint>) {
    init {require(points.size in 1..1024 && when(kind) {
        "rect" -> radius==0 && points.size==2
        "brush" -> radius in 1..64
        "polygon" -> radius==0 && points.size in 3..128 && points.distinct().size==points.size &&
            points.indices.sumOf { i -> val a=points[i]; val b=points[(i+1)%points.size];a.x.toLong()*b.y-b.x.toLong()*a.y }!=0L
        else -> false
    })}
    fun json()=JsonObject().apply {
        addProperty("kind",kind);addProperty("keep",keep);addProperty("radius",radius)
        add("points",JsonArray().apply {points.forEach { point -> add(JsonArray().apply {add(point.x);add(point.y)})}})
    }
}
data class ProjectImageMask(val width:Int,val height:Int,val strokes:List<ProjectMaskStroke>) {
    init {validate(width,height)}
    fun validate(w:Int,h:Int) {
        require(w==width && h==height && w in 1..4096 && h in 1..4096 && w.toLong()*h<=4_194_304)
        require(strokes.size in 1..64 && strokes.sumOf {it.points.size}<=1024)
        var work=0L
        strokes.forEach { stroke ->
            if(stroke.kind=="rect") {
                val a=stroke.points[0];val b=stroke.points[1]
                require(a.x<b.x && a.y<b.y && b.x<=w && b.y<=h)
                work+=(b.x-a.x).toLong()*(b.y-a.y)
            } else if(stroke.kind=="polygon") {
                require(stroke.points.all {it.x<=w && it.y<=h})
                val rows=stroke.points.maxOf {it.y}-stroke.points.minOf {it.y}
                work+=rows.toLong()*(w+stroke.points.size*stroke.points.size)
            } else {
                require(stroke.points.all {it.x<w && it.y<h})
                val steps=1L+stroke.points.zipWithNext().sumOf { (a,b) -> max(abs(a.x-b.x),abs(a.y-b.y)).toLong()+1 }
                work+=steps*(stroke.radius*2+1)*(stroke.radius*2+1)
            }
            require(work<=64_000_000) {"蒙版笔画超过6400万计算预算，请减少笔画或半径"}
        }
    }
    fun json()=JsonObject().apply {
        addProperty("version",1);addProperty("width",width);addProperty("height",height)
        add("strokes",JsonArray().apply {strokes.forEach {add(it.json())}})
    }
    fun encode()=json().toString().also {require(it.toByteArray(Charsets.UTF_8).size<=15_000)}
    companion object {
        fun decode(text:String):ProjectImageMask=fromJson(strictFilterJson(text,15_000,1024).asJsonObject)
        internal fun fromJson(value:JsonObject):ProjectImageMask {
            imageKeys(value,setOf("version","width","height","strokes"));require(imageInt(value,"version")==1)
            require(value["strokes"].isJsonArray)
            return ProjectImageMask(imageInt(value,"width"),imageInt(value,"height"),value["strokes"].asJsonArray.map { element ->
                val stroke=element.asJsonObject;imageKeys(stroke,setOf("kind","keep","radius","points"))
                require(stroke["points"].isJsonArray)
                ProjectMaskStroke(imageText(stroke,"kind"),imageBool(stroke,"keep"),imageInt(stroke,"radius"),stroke["points"].asJsonArray.map { raw ->
                    val point=raw.asJsonArray;require(point.size()==2)
                    val objectPoint=JsonObject().apply {add("x",point[0]);add("y",point[1])}
                    ProjectMaskPoint(imageInt(objectPoint,"x"),imageInt(objectPoint,"y"))
                })
            })
        }
    }
}

/** Even-odd pixel-centre fill. Rational intersections avoid preview/runtime rounding drift. */
fun polygonMaskSpans(points:List<ProjectMaskPoint>,y:Int,width:Int):List<IntRange> {
    data class Crossing(val n:Long,val d:Long)
    val crossings=points.indices.mapNotNull {i ->
        val first=points[i];val second=points[(i+1)%points.size]
        val a=if(first.y<second.y) first else second;val b=if(first.y<second.y) second else first
        val cy=2L*y+1
        if(cy<2L*a.y || cy>=2L*b.y) null else {
            val dy=(b.y-a.y).toLong()
            Crossing(2L*a.x*dy+(cy-2L*a.y)*(b.x-a.x)-dy,2L*dy)
        }
    }.sortedWith {a,b -> (a.n*b.d).compareTo(b.n*a.d)}
    fun ceil(v:Crossing)=(-Math.floorDiv(-v.n,v.d)).toInt().coerceIn(0,width)
    return crossings.chunked(2).mapNotNull {pair ->
        if(pair.size!=2) null else {val left=ceil(pair[0]);val right=ceil(pair[1]);if(left<right) left until right else null}
    }
}

val imageCleanupModes=setOf("despeckle","erode","dilate","open","close")
data class ProjectImageCleanup(val foreground:String="light",val threshold:Int=128,val radius:Int=1,val minArea:Int=2,val connectivity:Int=4) {
    init {require(foreground in setOf("light","dark") && threshold in 0..255 && radius in 1..2 && minArea in 1..4096 && connectivity in setOf(4,8))}
    fun validate(mode:String,width:Int,height:Int) {
        require(mode in imageCleanupModes && width in 1..4096 && height in 1..4096 && width.toLong()*height<=4_194_304)
        val work=if(mode=="despeckle") 8L else (radius*2+1).toLong()*(radius*2+1)*(if(mode in setOf("open","close")) 2 else 1)
        require(width.toLong()*height*work<=64_000_000) {"清理超过6400万邻域计算预算，请减小范围或半径"}
    }
    fun json()=JsonObject().apply {
        addProperty("version",1);addProperty("foreground",foreground);addProperty("threshold",threshold)
        addProperty("radius",radius);addProperty("minArea",minArea);addProperty("connectivity",connectivity)
    }
    fun encode()=json().toString()
    companion object {
        fun decode(text:String):ProjectImageCleanup=fromJson(strictFilterJson(text,1024).asJsonObject)
        internal fun fromJson(value:JsonObject):ProjectImageCleanup {
            imageKeys(value,setOf("version","foreground","threshold","radius","minArea","connectivity"));require(imageInt(value,"version")==1)
            return ProjectImageCleanup(imageText(value,"foreground"),imageInt(value,"threshold"),imageInt(value,"radius"),imageInt(value,"minArea"),imageInt(value,"connectivity"))
        }
    }
}
internal fun imageKeys(value:JsonObject,keys:Set<String>) {require(value.keySet()==keys) {"图像配置字段缺失或未知"}}
internal fun imageInt(value:JsonObject,key:String):Int {
    val item=value[key];require(item.isJsonPrimitive && item.asJsonPrimitive.isNumber && item.toString().matches(Regex("-?(0|[1-9][0-9]*)")))
    return item.toString().toInt()
}
internal fun imageText(value:JsonObject,key:String):String {val item=value[key];require(item.isJsonPrimitive && item.asJsonPrimitive.isString);return item.asString}
internal fun imageBool(value:JsonObject,key:String):Boolean {val item=value[key];require(item.isJsonPrimitive && item.asJsonPrimitive.isBoolean);return item.asBoolean}
