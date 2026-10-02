import groovy.json.JsonSlurper

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

val embeddedReleaseDirectory = providers.gradleProperty("autoscript.releaseDir").orNull?.let {
    rootProject.file(it)
}
val embeddedReleaseManifest = embeddedReleaseDirectory?.resolve("release.json")?.let { manifest ->
    require(manifest.isFile) { "autoscript.releaseDir must contain release.json: $manifest" }
    @Suppress("UNCHECKED_CAST")
    (JsonSlurper().parse(manifest) as? Map<String, Any?>)
        ?: error("release.json must be an object")
}
fun releaseString(name: String, fallback: String): String =
    embeddedReleaseManifest?.get(name)?.let {
        it as? String ?: error("release.json.$name must be a string")
    } ?: fallback
fun releaseInt(name: String, fallback: Int): Int =
    embeddedReleaseManifest?.get(name)?.let {
        (it as? Number)?.toInt() ?: error("release.json.$name must be an integer")
    } ?: fallback

val packagedApplicationId = releaseString("applicationId", "com.autoscript.runner")
val packagedVersionCode = releaseInt("versionCode", 1)
val packagedVersionName = releaseString("versionName", "0.1.0")
val packagedDisplayName = releaseString("displayName", "自动化Runner")

android {
    namespace = "com.autoscript.runner"
    compileSdk = 36

    defaultConfig {
        applicationId = packagedApplicationId
        minSdk = 24
        targetSdk = 36
        versionCode = packagedVersionCode
        versionName = packagedVersionName
        manifestPlaceholders["runnerLabel"] = packagedDisplayName
        ndk { abiFilters += listOf("arm64-v8a", "x86_64") }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }
    packaging { jniLibs.useLegacyPackaging = false }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

if (embeddedReleaseDirectory != null) {
    val releasePackagerExecutable = rootProject.layout.projectDirectory.file(
        if (System.getProperty("os.name").startsWith("Windows", ignoreCase = true)) {
            "target/debug/release-packager.exe"
        } else {
            "target/debug/release-packager"
        },
    )
    val generatedReleaseAssets = layout.buildDirectory.dir("generated/release-assets")
    val buildReleasePackager by tasks.registering(Exec::class) {
        workingDir(rootProject.projectDir)
        commandLine("cargo", "build", "-p", "release-packager")
        outputs.file(releasePackagerExecutable)
        outputs.upToDateWhen { false }
    }
    val verifyEmbeddedRelease by tasks.registering(Exec::class) {
        dependsOn(buildReleasePackager)
        workingDir(rootProject.projectDir)
        inputs.dir(embeddedReleaseDirectory)
        commandLine(
            releasePackagerExecutable.asFile.absolutePath,
            "verify",
            embeddedReleaseDirectory.absolutePath,
        )
    }
    val prepareEmbeddedRelease by tasks.registering(Sync::class) {
        dependsOn(verifyEmbeddedRelease)
        from(embeddedReleaseDirectory)
        into(generatedReleaseAssets.map { it.dir("autoscript-release") })
    }
    android.sourceSets.getByName("main").assets.srcDir(generatedReleaseAssets)
    tasks.named("preBuild").configure { dependsOn(prepareEmbeddedRelease) }
}

dependencies {
    implementation(project(":android:script-ui"))
    implementation(project(":android:core-model"))
    implementation(project(":android:core-designsystem"))
    implementation(project(":android:runtime-api"))
    implementation(project(":android:runtime-client"))
    implementation(project(":android:runtime-service"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.kotlinx.coroutines.android)
    debugImplementation(libs.androidx.compose.ui.tooling)
    testImplementation(libs.junit4)
}
