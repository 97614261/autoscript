package com.autoscript.runtime.vision.components

import android.content.Context
import android.content.pm.PackageManager
import java.io.File
import java.security.MessageDigest

/** Must be created on IO. Never trusts the imported descriptor's certificate or manifest. */
object AndroidVisionComponents {
    @Suppress("DEPRECATION")
    fun store(context: Context): VisionComponentStore {
        val app = context.applicationContext
        val manager = app.packageManager
        val signatures = manager.getPackageInfo(app.packageName, PackageManager.GET_SIGNATURES).signatures.orEmpty()
        require(signatures.size == 1) { "VISION_PUBLISHER_IDENTITY_UNAVAILABLE" }
        val trust = setOf(MessageDigest.getInstance("SHA-256").digest(signatures.single().toByteArray()).hex())
        return VisionComponentStore(File(app.filesDir.canonicalFile, "vision-providers"), trust,
            publishSelection = { source, target -> android.system.Os.rename(source.absolutePath, target.absolutePath) },
            sealDirectory = { directory -> android.system.Os.chmod(directory.absolutePath, 320) }, // 0500
            unsealDirectory = { directory -> android.system.Os.chmod(directory.absolutePath, 448) }) { apk -> // 0700
            VisionComponentManifest.read(manager, apk)
        }
    }
}
