plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
}

val abiProfile = providers.gradleProperty("autoscript.abiProfile").getOrElse("development")
require(abiProfile in setOf("development", "arm64")) { "Unknown autoscript.abiProfile: $abiProfile" }
extra["autoscript.abis"] = if (abiProfile == "arm64") listOf("arm64-v8a") else listOf("arm64-v8a", "x86_64")
if (abiProfile == "arm64") {
    require(gradle.startParameter.projectCacheDir?.canonicalFile == rootProject.file("build/profiles/arm64/.gradle").canonicalFile) {
        "arm64 requires --project-cache-dir build/profiles/arm64/.gradle; use build-mobile.cmd"
    }
    subprojects {
        layout.buildDirectory.set(rootProject.layout.projectDirectory.dir("build/profiles/arm64/${path.drop(1).replace(':', '/')}"))
    }
}
