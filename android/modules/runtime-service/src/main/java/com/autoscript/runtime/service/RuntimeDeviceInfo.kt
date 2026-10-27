package com.autoscript.runtime.service

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.Build
import android.os.StatFs
import android.provider.Settings
import android.view.WindowManager
import com.google.gson.JsonObject

/** Read-only bounded snapshots; no identifiers, network addresses or permission mutation. */
internal object RuntimeDeviceInfo {
    fun read(context:Context,section:String,rootReady:Boolean,inputFeatures:Int):String = JsonObject().apply {
        when(section) {
            "device" -> {
                addProperty("manufacturer",Build.MANUFACTURER.take(128));addProperty("model",Build.MODEL.take(128))
                addProperty("androidApi",Build.VERSION.SDK_INT);addProperty("androidRelease",Build.VERSION.RELEASE.take(64))
            }
            "display" -> {
                @Suppress("DEPRECATION") val display=context.getSystemService(WindowManager::class.java).defaultDisplay
                val metrics=android.util.DisplayMetrics()
                @Suppress("DEPRECATION") display.getRealMetrics(metrics)
                addProperty("widthPx",metrics.widthPixels);addProperty("heightPx",metrics.heightPixels)
                addProperty("densityDpi",metrics.densityDpi);addProperty("rotation",display.rotation)
            }
            "battery" -> {
                val battery=context.registerReceiver(null,IntentFilter(Intent.ACTION_BATTERY_CHANGED))
                val level=battery?.getIntExtra(BatteryManager.EXTRA_LEVEL,-1) ?: -1
                val scale=battery?.getIntExtra(BatteryManager.EXTRA_SCALE,-1) ?: -1
                addProperty("available",level>=0 && scale>0)
                if(level>=0 && scale>0) addProperty("percent",level.toLong()*100/scale)
                addProperty("plugged",battery?.getIntExtra(BatteryManager.EXTRA_PLUGGED,0) ?: 0)
            }
            "network" -> {
                val manager=context.getSystemService(ConnectivityManager::class.java)
                val network=manager.activeNetwork
                val capabilities=network?.let(manager::getNetworkCapabilities)
                addProperty("connected",capabilities!=null)
                addProperty("validated",capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)==true)
                addProperty("wifi",capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)==true)
                addProperty("cellular",capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)==true)
                addProperty("vpn",capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_VPN)==true)
            }
            "storage" -> {
                val volume=StatFs(context.filesDir.absolutePath)
                addProperty("availableBytes",volume.availableBytes);addProperty("totalBytes",volume.totalBytes)
            }
            "permissions" -> {
                addProperty("overlayGranted",Settings.canDrawOverlays(context))
                addProperty("notificationsGranted",context.getSystemService(android.app.NotificationManager::class.java).areNotificationsEnabled())
                addProperty("rootReady",rootReady);addProperty("inputFeatureBits",if(rootReady) inputFeatures else 0)
                addProperty("captureTested",false)
            }
            else -> error("DEVICE_INFO_SECTION_INVALID")
        }
    }.toString().also {require(it.toByteArray(Charsets.UTF_8).size<=4096)}
}
