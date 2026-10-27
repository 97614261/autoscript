package com.autoscript.runtime.vision.components

import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import java.io.File
import org.xmlpull.v1.XmlPullParser

/** Read by PackageManager from the signed APK, never from its descriptor. */
data class VisionComponentManifest(
    val applicationId: String,
    val versionCode: Int,
    val versionName: String?,
    val minSdk: Int,
    val hasCode: Boolean,
    val componentCount: Int,
    val permissionCount: Int,
) {
    fun check(descriptor: VisionComponentDescriptor) {
        require(applicationId == descriptor.kind.applicationId && versionCode == descriptor.componentVersion && versionName == descriptor.kind.sdkVersion) { "VISION_COMPONENT_IDENTITY_MISMATCH" }
        require(minSdk == 24) { "VISION_COMPONENT_MIN_SDK_MISMATCH" }
        require(hasCode) { "VISION_COMPONENT_CODE_MISSING" }
        require(componentCount == 0) { "VISION_COMPONENT_UNEXPECTED_ENTRYPOINT" }
        require(permissionCount == 0) { "VISION_COMPONENT_UNEXPECTED_PERMISSION" }
    }

    companion object {
        @Suppress("DEPRECATION")
        fun read(manager: PackageManager, apk: File): VisionComponentManifest {
            val flags = PackageManager.GET_ACTIVITIES or PackageManager.GET_RECEIVERS or PackageManager.GET_SERVICES or
                PackageManager.GET_PROVIDERS or PackageManager.GET_INSTRUMENTATION or PackageManager.GET_PERMISSIONS
            val info = requireNotNull(manager.getPackageArchiveInfo(apk.path, flags))
            val app = requireNotNull(info.applicationInfo)
            // PackageManager may synthesize compatibility permissions (including on some emulators).
            // Count declarations from the SAME signed archive, not that augmented metadata and never
            // from the self-reported component descriptor. Keep all PM identity checks as well.
            app.sourceDir = apk.absolutePath
            app.publicSourceDir = apk.absolutePath
            var declaredPackage: String? = null
            var declaredVersion: Int? = null
            var declaredVersionName: String? = null
            var declaredMinSdk: Int? = null
            var declaredHasCode = true
            var components = 0
            var permissions = 0
            var events = 0
            manager.getResourcesForApplication(app).assets.openXmlResourceParser("AndroidManifest.xml").use { xml ->
                while (xml.next() != XmlPullParser.END_DOCUMENT) {
                    require(++events <= 4096 && xml.depth <= 32 && xml.attributeCount <= 64) { "VISION_COMPONENT_MANIFEST_BUDGET" }
                    if (xml.eventType != XmlPullParser.START_TAG) continue
                    val namespace = "http://schemas.android.com/apk/res/android"
                    when (xml.name) {
                        "manifest" -> {
                            require(declaredPackage == null && xml.depth == 1)
                            declaredPackage = xml.getAttributeValue(null, "package")
                            declaredVersion = xml.getAttributeIntValue(namespace, "versionCode", -1)
                            declaredVersionName = xml.getAttributeValue(namespace, "versionName")
                        }
                        "uses-sdk" -> { require(declaredMinSdk == null); declaredMinSdk = xml.getAttributeIntValue(namespace, "minSdkVersion", -1) }
                        "application" -> declaredHasCode = xml.getAttributeBooleanValue(namespace, "hasCode", true)
                        "activity", "activity-alias", "receiver", "service", "provider", "instrumentation" -> components++
                        else -> if (xml.name.startsWith("permission") || xml.name.startsWith("uses-permission")) permissions++
                    }
                }
            }
            require(declaredPackage == info.packageName && declaredVersion == info.versionCode && declaredVersionName == info.versionName &&
                declaredMinSdk == app.minSdkVersion && declaredHasCode == (app.flags and ApplicationInfo.FLAG_HAS_CODE != 0)) {
                "VISION_COMPONENT_MANIFEST_IDENTITY_MISMATCH"
            }
            return VisionComponentManifest(info.packageName, info.versionCode, info.versionName, app.minSdkVersion,
                app.flags and ApplicationInfo.FLAG_HAS_CODE != 0,
                components, permissions)
        }
    }
}
