package com.autoscript.script.ui

import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.LayerDrawable
import java.io.File

/** Only declared resource resolution is supplied; never accepts an arbitrary filesystem path. */
internal class ScriptUiBackgrounds(private val resolve: (String)->File?) {
    private val paths=mutableMapOf<String,String>()
    private val cache=mutableMapOf<String,Bitmap>()
    fun read(id: String)=paths[id].orEmpty()
    fun reset() {paths.clear(); cache.clear()}
    fun apply(id: String, path: String) {
        require(path.isEmpty() || (path.length<=256 && path.startsWith("assets/images/") &&
            path.split('/').none {it.isEmpty() || it in setOf(".","..")} && '\\' !in path && path.none(Char::isISOControl))) {"请选择项目中声明的图片"}
        if(path.isEmpty()) {paths.remove(id); prune(); return}
        val bitmap=cache[path] ?: decode(requireNotNull(resolve(path)) {"图片资源未声明"})
        val desired=(paths.filterKeys {it!=id}.values+path).toSet()
        val pixels=desired.sumOf {name->(if(name==path) bitmap else requireNotNull(cache[name])).let {it.width.toLong()*it.height}}
        require(pixels<=2_000_000) {"背景图片超过界面资源预算"}
        paths[id]=path; cache[path]=bitmap; prune()
    }
    private fun prune() {cache.keys.retainAll(paths.values.toSet())}
    fun drawable(id: String, resources: Resources, base: Drawable): Drawable =
        paths[id]?.let {cache[it]}?.let {bitmap->LayerDrawable(arrayOf(base,BitmapDrawable(resources,bitmap)))} ?: base
    private fun decode(file: File): Bitmap {
        require(file.isFile && file.length() in 1..16L*1024*1024) {"背景图片文件超过预算或不存在"}
        val bounds=BitmapFactory.Options().apply {inJustDecodeBounds=true}
        BitmapFactory.decodeFile(file.path,bounds)
        require(bounds.outWidth in 1..4096 && bounds.outHeight in 1..4096) {"背景图片尺寸无效"}
        var sample=1
        while((bounds.outWidth+sample-1)/sample>512 || (bounds.outHeight+sample-1)/sample>512) sample*=2
        return requireNotNull(BitmapFactory.decodeFile(file.path,BitmapFactory.Options().apply {inSampleSize=sample})) {"背景图片无法解码"}
    }
}
