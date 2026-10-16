package com.autoscript.runtime.service

import com.google.gson.JsonObject
import org.junit.Assert.*
import org.junit.Test

class FloatingCanvasSpecTest {
    private fun json()=JsonObject().apply {
        addProperty("name","panel"); addProperty("x",-1); addProperty("y",0); addProperty("width",320); addProperty("height",120)
        addProperty("text","中文"); addProperty("fontPx",20); addProperty("textColor","#FFFFFFFF"); addProperty("background","#C0202938")
        addProperty("imagePath",""); addProperty("shapesJson","[{\"kind\":\"line\",\"x1\":0,\"y1\":1,\"x2\":2,\"y2\":3,\"strokePx\":2,\"color\":\"#FF66CCFF\"}]")
    }
    @Test fun originalPixelsAlphaAndFixedShapesArePreserved() {
        val spec=FloatingCanvasSpec.parse(json().toString())
        assertEquals(-1,spec.x); assertEquals(0,spec.y); assertEquals(320,spec.width)
        assertEquals(0xc0202938.toInt(),spec.background)
        assertEquals("中文",spec.text); assertArrayEquals(intArrayOf(0,1,2,3),spec.shapes.single().coordinates)
    }
    @Test fun malformedShapesTypesAndNamesCannotReachWindowManager() {
        val invalid=listOf(json().apply {addProperty("x",1.5)},json().apply {addProperty("name","../panel")},json().apply {addProperty("fontPx",97)},json().apply {addProperty("imagePath","/sdcard/a.png")},json().apply {addProperty("shapesJson","[{\"kind\":\"shell\"}]")},json().apply {addProperty("shapesJson","[".repeat(100)+"0"+"]".repeat(100))})
        invalid.forEach {assertThrows(Exception::class.java) {FloatingCanvasSpec.parse(it.toString())}}
    }
}
