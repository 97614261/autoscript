import java.io.File
import java.util.Properties

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
}

val abiProfile = providers.gradleProperty("autoscript.abiProfile").getOrElse("development")
require(abiProfile in setOf("development", "arm64")) { "Unknown autoscript.abiProfile: $abiProfile" }
extra["autoscript.abis"] = if (abiProfile == "arm64") listOf("arm64-v8a") else listOf("arm64-v8a", "x86_64")
val visionProfileProperty = providers.gradleProperty("autoscript.visionProfile")
val visionProfile = visionProfileProperty.getOrElse("full")
require(visionProfile in setOf("base", "opencv", "onnx", "full")) { "Unknown autoscript.visionProfile: $visionProfile" }
extra["autoscript.visionProfile"] = visionProfile

// Component exports and verification use the same installed signer. Resolve
// the newest complete Build Tools directory instead of assuming one patch
// version exists on every developer machine or CI image.
val localProperties = Properties().apply {
    rootProject.file("local.properties").takeIf { it.isFile }?.inputStream()?.use(::load)
}
val sdkDirectory = localProperties.getProperty("sdk.dir")?.let { File(it.replace("\\:", ":").replace("\\\\", "\\")) }
    ?: error("android/local.properties must define sdk.dir")
fun compareBuildTools(left: String, right: String): Int {
    val a = left.split('.').map { it.toIntOrNull() ?: -1 }
    val b = right.split('.').map { it.toIntOrNull() ?: -1 }
    for (index in 0 until maxOf(a.size, b.size)) {
        val result = (a.getOrElse(index) { -1 }).compareTo(b.getOrElse(index) { -1 })
        if (result != 0) return result
    }
    return 0
}
val buildToolsDirectory = sdkDirectory.resolve("build-tools").listFiles().orEmpty()
    .filter { File(it, "lib/apksigner.jar").isFile && File(it, "aapt2.exe").isFile }
    .maxWithOrNull(Comparator { left, right -> compareBuildTools(left.name, right.name) })
    ?: error("No complete Android Build Tools installation was found under ${sdkDirectory.resolve("build-tools")}")
extra["autoscript.buildToolsDirectory"] = buildToolsDirectory
if (visionProfileProperty.isPresent) {
    val profileRoot = rootProject.file("../build/profiles/components/$abiProfile/$visionProfile")
    require(gradle.startParameter.projectCacheDir?.canonicalFile == profileRoot.resolve(".gradle").canonicalFile) {
        "Explicit vision profiles require --project-cache-dir ${profileRoot.resolve(".gradle").absolutePath}"
    }
    subprojects {
        layout.buildDirectory.set(profileRoot.resolve(path.drop(1).replace(':', '/')))
    }
} else if (abiProfile == "arm64") {
    require(gradle.startParameter.projectCacheDir?.canonicalFile == rootProject.file("../build/profiles/arm64/.gradle").canonicalFile) {
        "arm64 requires --project-cache-dir build/profiles/arm64/.gradle; use build-mobile.cmd"
    }
    subprojects {
        layout.buildDirectory.set(rootProject.layout.projectDirectory.dir("../build/profiles/arm64/${path.drop(1).replace(':', '/')}"))
    }
}
