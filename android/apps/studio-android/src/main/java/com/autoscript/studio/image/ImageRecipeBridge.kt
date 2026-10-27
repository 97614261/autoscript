package com.autoscript.studio

import com.autoscript.project.store.*
import com.autoscript.runtime.api.RecognitionPreviewReply as Reply
import com.google.gson.JsonObject

internal fun ImagePixelOperation.recipeStep()=ProjectImageStep(mode,width,height,roi?.let {ProjectImageRegion(it.left,it.top,it.right,it.bottom)},threshold,filter,mask,cleanup)
internal fun ProjectImageStep.pixelOperation()=ImagePixelOperation(mode,width,height,region?.let {ImageToolCodeGen.Roi(it.left,it.top,it.right,it.bottom)},threshold,filter,mask,cleanup)
internal fun ImageToolCodeGen.Roi.recipeRegion()=ProjectImageRegion(left,top,right,bottom)
internal fun ProjectImageRegion.toolRegion()=ImageToolCodeGen.Roi(left,top,right,bottom)

internal fun matchRecipeTail(settings:RecognitionPreviewConfiguration)=ProjectImageRecipeTail(
    when(settings.operation) {Reply.GRAY_TEMPLATE->"vision.findgray";Reply.FEATURE_TEMPLATE->"vision.findfeatures";Reply.SCALED_TEMPLATE->"vision.findscaled";else->"vision.findimage"},JsonObject().apply {
        addProperty("template",settings.template);addProperty("similarityPermille",settings.similarityPermille)
        if(settings.operation !in setOf(Reply.GRAY_TEMPLATE,Reply.FEATURE_TEMPLATE)) {addProperty("tolerance",settings.tolerance);addProperty("direction",settings.direction)}
        if(settings.operation==Reply.SCALED_TEMPLATE) {addProperty("minScalePermille",settings.minScalePermille);addProperty("maxScalePermille",settings.maxScalePermille);addProperty("stepPermille",settings.stepPermille)}
    }.toString())

internal fun ocrRecipeTail(settings:ImageToolOcrConfig)=ProjectImageRecipeTail(settings.kind,JsonObject().apply {
    addProperty("similarityPermille",settings.threshold)
    if (settings.operation == Reply.LATIN_TEXT) addProperty("includeSymbols", true)
    if(settings.operation==Reply.GLYPH) {
        addProperty("dictionary",settings.dictionaryPath);addProperty("foregroundRgb",settings.foregroundRgb)
        addProperty("tolerance",settings.tolerance);addProperty("spaceGapColumns",settings.spaceGapColumns)
        if(settings.additionalColors.isNotEmpty()) addProperty("additionalColors",settings.additionalColors)
    }
}.toString())

internal fun ProjectImageRecipe.matchSettings():RecognitionPreviewConfiguration? {
    val selected=tail?.takeIf {it.kind in setOf("vision.findimage","vision.findgray","vision.findscaled","vision.findfeatures")} ?: return null
    val values=selected.settings();val size=dimensions();val area=requireNotNull(region).toolRegion()
    return RecognitionPreviewConfiguration(when(selected.kind) {"vision.findgray"->Reply.GRAY_TEMPLATE;"vision.findfeatures"->Reply.FEATURE_TEMPLATE;"vision.findscaled"->Reply.SCALED_TEMPLATE;else->Reply.TEMPLATE},
        values["template"].asString,values["tolerance"]?.asInt ?: 0,values["similarityPermille"].asInt,
        size.first,size.second,area,values["direction"]?.asInt ?: 0,values["minScalePermille"]?.asInt ?: 1000,values["maxScalePermille"]?.asInt ?: 1000,values["stepPermille"]?.asInt ?: 100)
}
internal fun ProjectImageRecipe.ocrSettings():ImageToolOcrConfig? {
    val selected=tail?.takeIf {it.kind in setOf("ocr.glyph","ocr.alphanumeric","ocr.alphanumericv5")} ?: return null
    val values=selected.settings()
    return ImageToolOcrConfig(when(selected.kind) {"ocr.glyph"->Reply.GLYPH;"ocr.alphanumericv5"->Reply.ALPHANUMERIC_V5;else->if(values["includeSymbols"]?.asBoolean == true) Reply.LATIN_TEXT else Reply.ALPHANUMERIC},
        values["dictionary"]?.asString.orEmpty(),values["foregroundRgb"]?.asInt ?: 0xFFFFFF,
        values["tolerance"]?.asInt ?: 0,values["similarityPermille"].asInt,values["spaceGapColumns"]?.asInt ?: 0,values["additionalColors"]?.asString.orEmpty())
}
