package com.autoscript.studio

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import com.autoscript.runtime.vision.components.ApkV2Verifier
import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipFile

internal data class StudioUpdateApkIdentity(val packageName: String, val versionCode: Long,
    val versionName: String, val minSdk: Int, val abis: Set<String>, val certificate: String)

internal fun validateStudioUpdateApk(actual: StudioUpdateApkIdentity, expected: StudioUpdateRelease,
    installedCertificate: String, supportedAbis: List<String>, sdk: Int, installedCode: Int) {
    require(actual.packageName == BuildConfig.APPLICATION_ID)
    require(actual.certificate == installedCertificate && Regex("[a-f0-9]{64}").matches(installedCertificate))
    require(actual.versionCode == expected.versionCode.toLong() && actual.versionCode > installedCode)
    require(actual.versionName == expected.versionName && actual.minSdk <= sdk && actual.minSdk >= 24)
    require(actual.abis == expected.abis.toSet() && actual.abis.any { it in supportedAbis })
}

@Suppress("DEPRECATION")
internal fun verifyStudioUpdateApk(context: Context, file: File, expected: StudioUpdateRelease, checkpoint: () -> Unit) {
    val manager = context.packageManager
    val installed = manager.getPackageInfo(context.packageName, PackageManager.GET_SIGNATURES)
    val certificate = installed.signatures.orEmpty().single().toByteArray().let {
        MessageDigest.getInstance("SHA-256").digest(it).hexUpdate()
    }
    val signature = ApkV2Verifier.verify(file, checkpoint)
    checkpoint()
    val archive = requireNotNull(manager.getPackageArchiveInfo(file.path, 0))
    val abis = mutableSetOf<String>()
    ZipFile(file).use { zip ->
        val entries = zip.entries()
        var count = 0
        while (entries.hasMoreElements()) {
            checkpoint(); require(++count < 65535)
            val name = entries.nextElement().name
            Regex("lib/([^/]+)/libengine_jni\\.so").matchEntire(name)?.let { abis.add(it.groupValues[1]) }
        }
    }
    val version = if (Build.VERSION.SDK_INT >= 28) archive.longVersionCode else archive.versionCode.toLong()
    validateStudioUpdateApk(StudioUpdateApkIdentity(archive.packageName, version,
        archive.versionName.orEmpty(), requireNotNull(archive.applicationInfo).minSdkVersion, abis,
        signature.certificateSha256), expected, certificate, Build.SUPPORTED_ABIS.toList(),
        Build.VERSION.SDK_INT, BuildConfig.VERSION_CODE)
}
