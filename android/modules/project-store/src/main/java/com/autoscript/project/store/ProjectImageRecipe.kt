package com.autoscript.project.store

import com.google.gson.JsonArray
import com.google.gson.JsonObject

data class ProjectImageRegion(val left:Int,val top:Int,val right:Int,val bottom:Int) {
    fun validate(width:Int,height:Int) {require(left>=0 && top>=0 && right>left && bottom>top && right<=width && bottom<=height)}
    fun json()=JsonObject().apply {addProperty("left",left);addProperty("top",top);addProperty("right",right);addProperty("bottom",bottom)}
    companion object {
        fun fromJson(value:JsonObject):ProjectImageRegion {imageKeys(value,setOf("left","top","right","bottom"));return ProjectImageRegion(imageInt(value,"left"),imageInt(value,"top"),imageInt(value,"right"),imageInt(value,"bottom"))}
    }
}

/** No frame handles, output variables, test proof or pixels may enter a portable step. */
data class ProjectImageStep(val mode:String,val width:Int=0,val height:Int=0,val region:ProjectImageRegion?=null,
    val threshold:Int=128,val filter:ProjectImageFilter?=null,val mask:ProjectImageMask?=null,val cleanup:ProjectImageCleanup?=null) {
    fun dimensions(w:Int,h:Int):Pair<Int,Int> {
        require(w in 1..4096 && h in 1..4096 && w.toLong()*h<=4_194_304 && threshold in 0..255)
        require(mode=="crop" || region==null);require(mode=="resize" || width==0 && height==0)
        require((mode=="filter")== (filter!=null));filter?.validatePixels(w.toLong()*h)
        require((mode=="mask")== (mask!=null));mask?.let {it.validate(w,h);it.encode()}
        require((mode in imageCleanupModes)==(cleanup!=null));cleanup?.validate(mode,w,h)
        val size=when(mode) {
            "rotate90","rotate270" -> h to w
            "resize" -> width to height
            "crop" -> requireNotNull(region).let {it.validate(w,h);it.right-it.left to it.bottom-it.top}
            "gray","threshold","filter","mask","rotate180","flipX","flipY","despeckle","erode","dilate","open","close" -> w to h
            else -> error("不支持的图像处理步骤")
        }
        require(size.first in 1..4096 && size.second in 1..4096 && size.first.toLong()*size.second<=4_194_304)
        return size
    }
    fun json()=JsonObject().apply {
        addProperty("mode",mode);addProperty("width",width);addProperty("height",height);addProperty("threshold",threshold)
        region?.let {add("region",it.json())};filter?.let {add("filter",it.json())};mask?.let {add("mask",it.json())};cleanup?.let {add("cleanup",it.json())}
    }
    companion object {
        fun fromJson(value:JsonObject):ProjectImageStep {
            val base=setOf("mode","width","height","threshold")
            require(value.keySet().containsAll(base) && value.keySet().all {it in base+setOf("region","filter","mask","cleanup")})
            return ProjectImageStep(imageText(value,"mode"),imageInt(value,"width"),imageInt(value,"height"),
                value["region"]?.asJsonObject?.let(ProjectImageRegion::fromJson),imageInt(value,"threshold"),
                value["filter"]?.asJsonObject?.let(ProjectImageFilter::fromJson),value["mask"]?.asJsonObject?.let(ProjectImageMask::fromJson),
                value["cleanup"]?.asJsonObject?.let(ProjectImageCleanup::fromJson))
        }
    }
}

/** Immutable canonical recognition data, deliberately without execution/output identities. */
data class ProjectImageRecipeTail(val kind:String,val settingsJson:String) {
    init {validate()}
    fun settings()=strictFilterJson(settingsJson,4096).asJsonObject
    private fun validate() {
        val values=settings()
        val keys=when(kind) {
            "vision.findimage" -> setOf("template","tolerance","similarityPermille","direction")
            "vision.findgray", "vision.findfeatures" -> setOf("template","similarityPermille")
            "vision.findscaled" -> setOf("template","tolerance","similarityPermille","direction","minScalePermille","maxScalePermille","stepPermille")
            "ocr.glyph" -> setOf("dictionary","foregroundRgb","tolerance","similarityPermille","spaceGapColumns")
            "ocr.alphanumeric", "ocr.alphanumericv5" -> setOf("similarityPermille")
            else -> error("不支持的识别方案类型")
        }
        val optionalKeys = (if(kind=="ocr.glyph" && values.has("additionalColors")) setOf("additionalColors") else emptySet()) +
            (if(kind=="ocr.alphanumeric" && values.has("includeSymbols")) setOf("includeSymbols") else emptySet())
        imageKeys(values,keys + optionalKeys)
        if ("includeSymbols" in optionalKeys) require(values["includeSymbols"].isJsonPrimitive && values["includeSymbols"].asJsonPrimitive.isBoolean)
        if(kind=="ocr.glyph") ProjectGlyphColor.decodeAdditional(values["additionalColors"]?.let { imageText(values,"additionalColors") }.orEmpty(),imageInt(values,"foregroundRgb"))
        require(imageInt(values,"similarityPermille") in 0..1000)
        if("tolerance" in keys) require(imageInt(values,"tolerance") in 0..255)
        if("direction" in keys) require(imageInt(values,"direction") in 0..4)
        if("foregroundRgb" in keys) require(imageInt(values,"foregroundRgb") in 0..0xFFFFFF)
        if("spaceGapColumns" in keys) require(imageInt(values,"spaceGapColumns") in 0..4096)
        if("template" in keys) require(imageText(values,"template").startsWith("assets/images/"))
        if("dictionary" in keys) require(imageText(values,"dictionary").startsWith("dictionaries/"))
        if(kind=="vision.findscaled") {
            val min=imageInt(values,"minScalePermille");val max=imageInt(values,"maxScalePermille");val step=imageInt(values,"stepPermille")
            require(min in 250..4000 && max in min..4000 && step in 1..1000 && (max-min)%step==0 && (max-min)/step+1<=16)
        }
    }
    fun validateResources(resources:List<JsonObject>) {
        val values=settings()
        if(values.has("template")) require(resources.any {it["kind"]?.asString=="image" && it["path"]?.asString==values["template"].asString}) {"方案引用的模板不存在"}
        if(values.has("dictionary")) require(resources.any {it["kind"]?.asString in setOf("glyphDictionary","glyph_dictionary") && it["path"]?.asString==values["dictionary"].asString}) {"方案引用的字库不存在"}
    }
    fun references(path:String)=settings().entrySet().any {it.key in setOf("template","dictionary") && it.value.asString==path}
    fun json()=JsonObject().apply {addProperty("kind",kind);add("settings",settings())}
    companion object {
        fun fromJson(value:JsonObject):ProjectImageRecipeTail {imageKeys(value,setOf("kind","settings"));return ProjectImageRecipeTail(imageText(value,"kind"),value["settings"].asJsonObject.toString())}
    }
}

data class ProjectImageRecipe(val name:String,val sourceWidth:Int,val sourceHeight:Int,val steps:List<ProjectImageStep>,
    val region:ProjectImageRegion?=null,val tail:ProjectImageRecipeTail?=null) {
    init {
        require(name.isNotBlank() && name==name.trim() && name.codePointCount(0,name.length)<=32 && name.none(Char::isISOControl)
            && name.toByteArray(Charsets.UTF_8).toString(Charsets.UTF_8)==name)
        require(steps.size<=64)
        val size=dimensions();region?.validate(size.first,size.second)
        require(tail==null || region!=null) {"识别方案必须明确识别范围"}
        require(json().toString().toByteArray(Charsets.UTF_8).size<=32*1024) {"单个图像方案最多32KiB"}
    }
    fun dimensions():Pair<Int,Int> {
        require(sourceWidth in 1..4096 && sourceHeight in 1..4096 && sourceWidth.toLong()*sourceHeight<=4_194_304)
        var size=sourceWidth to sourceHeight
        steps.forEach {size=it.dimensions(size.first,size.second)}
        return size
    }
    fun validateResources(resources:List<JsonObject>) {tail?.validateResources(resources)}
    fun json()=JsonObject().apply {
        addProperty("name",name);addProperty("sourceWidth",sourceWidth);addProperty("sourceHeight",sourceHeight)
        add("steps",JsonArray().apply {steps.forEach {add(it.json())}});region?.let {add("region",it.json())};tail?.let {add("tail",it.json())}
    }
    companion object {
        fun fromJson(value:JsonObject):ProjectImageRecipe {
            val required=setOf("name","sourceWidth","sourceHeight","steps")
            require(value.keySet().containsAll(required) && value.keySet().all {it in required+setOf("region","tail")})
            require(value["steps"].isJsonArray && value["steps"].asJsonArray.size()<=64)
            return ProjectImageRecipe(imageText(value,"name"),imageInt(value,"sourceWidth"),imageInt(value,"sourceHeight"),
                value["steps"].asJsonArray.map {ProjectImageStep.fromJson(it.asJsonObject)},value["region"]?.asJsonObject?.let(ProjectImageRegion::fromJson),
                value["tail"]?.asJsonObject?.let(ProjectImageRecipeTail::fromJson))
        }
    }
}

const val MAX_IMAGE_RECIPE_BYTES=1024*1024
internal fun decodeImageRecipes(text:String):List<ProjectImageRecipe> {
    val root=strictFilterJson(text,MAX_IMAGE_RECIPE_BYTES,1024,12).asJsonObject;imageKeys(root,setOf("version","recipes"));require(imageInt(root,"version")==1)
    require(root["recipes"].isJsonArray && root["recipes"].asJsonArray.size()<=32)
    val recipes=root["recipes"].asJsonArray.map {ProjectImageRecipe.fromJson(it.asJsonObject)}
    require(recipes.map {it.name}.distinct().size==recipes.size) {"图像方案名称重复"}
    return recipes
}
internal fun encodeImageRecipes(recipes:List<ProjectImageRecipe>):ByteArray {
    val text=JsonObject().apply {addProperty("version",1);add("recipes",JsonArray().apply {recipes.forEach {add(it.json())}})}.toString()
    decodeImageRecipes(text);return text.toByteArray(Charsets.UTF_8)
}
