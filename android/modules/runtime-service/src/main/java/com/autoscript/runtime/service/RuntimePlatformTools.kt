package com.autoscript.runtime.service

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import com.google.gson.JsonObject

internal class RuntimePlatformTools(private val context: Context) {
    fun execute(operation: String, value: String): String = when(operation) {
        "clipboardGet" -> {
            val clipboard=context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip=clipboard.primaryClip ?: error("clipboard unavailable in current app state")
            require(clip.itemCount == 1)
            val item=clip.getItemAt(0)
            require(item.uri == null && item.intent == null)
            val text=requireNotNull(item.text).toString()
            require(text.toByteArray(Charsets.UTF_8).size<=4096)
            text
        }
        "clipboardSet" -> {
            require(value.toByteArray(Charsets.UTF_8).size<=4096)
            (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("智构脚本",value))
            ""
        }
        "appInfo" -> {
            require(validPackage(value))
            val info=try { context.packageManager.getPackageInfo(value,0) } catch (_: PackageManager.NameNotFoundException) { null }
            JsonObject().apply {
                addProperty("visible",info!=null)
                if(info!=null) {
                    val version=info.versionName.orEmpty()
                    require(version.toByteArray(Charsets.UTF_8).size<=256)
                    addProperty("versionName",version)
                    addProperty("versionCode",if(Build.VERSION.SDK_INT>=28) info.longVersionCode else info.versionCode.toLong())
                }
            }.toString()
        }
        "appOpen" -> {
            require(validPackage(value))
            val intent=context.packageManager.getLaunchIntentForPackage(value) ?: error("app has no visible launcher")
            intent.setPackage(value).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            ""
        }
        else -> error("unknown platform operation")
    }
    companion object {
        fun validPackage(value: String): Boolean = value.length<=255 && Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+").matches(value)
    }
}
