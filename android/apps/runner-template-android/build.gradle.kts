import groovy.json.JsonSlurper
import groovy.json.JsonOutput
import java.io.File

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

val embeddedReleaseDirectory = providers.gradleProperty("autoscript.releaseDir").orNull?.let {
    rootProject.file("../").resolve(it)
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

val packagedApplicationId = releaseString("applicationId", "com.zhigou.yunxingban")
val packagedVersionCode = releaseInt("versionCode", 1)
val packagedVersionName = releaseString("versionName", "0.1.0")
val packagedDisplayName = releaseString("displayName", "自动化Runner")

// Generated only for pristine templates; the descriptor is protected by the APK signature.
val componentAssets = layout.buildDirectory.dir("generated/component-assets")
val componentProfile = rootProject.extra["autoscript.visionProfile"] as String
val componentAbis = rootProject.extra["autoscript.abis"] as List<*>
val protocolSource = rootProject.file("modules/runtime-api/src/main/java/com/autoscript/runtime/api/RuntimeProtocol.kt")
val apiSnapshot = rootProject.file("../schema/api-schema/snapshot/contract.json")
val generateRunnerComponent = tasks.register("generateRunnerComponent") {
    inputs.files(protocolSource, apiSnapshot)
    inputs.property("visionProfile", componentProfile)
    inputs.property("abis", componentAbis)
    outputs.dir(componentAssets)
    doLast {
        require(embeddedReleaseDirectory == null) { "Component export requires a pristine template" }
        val protocol = Regex("const val VERSION: Int = ([0-9]+)").find(protocolSource.readText())!!.groupValues[1].toInt()
        val componentVersion = Regex("const val RUNNER_TEMPLATE_COMPONENT_VERSION: Int = ([0-9]+)").find(protocolSource.readText())!!.groupValues[1].toInt()
        val dependencies = linkedMapOf<String, Any>()
        if (componentProfile in setOf("opencv", "full")) dependencies["opencv"] = mapOf(
            "version" to "4.10.0", "license" to "assets/vision/OpenCV-LICENSE.txt")
        if (componentProfile in setOf("onnx", "full")) {
            dependencies["onnx"] = mapOf("version" to "1.19.2", "license" to "assets/vision/ONNXRuntime-LICENSE.txt")
            dependencies["model"] = mapOf("version" to "en_PP-OCRv4_rec_mobile", "license" to "assets/vision/PaddleOCR-LICENSE.txt",
                "sha256" to "e8770c967605983d1570cdf5352041dfb68fa0c21664f49f47b155abd3e0e318")
        }
        val descriptor = mapOf("formatVersion" to 1, "kind" to "runner-template", "componentVersion" to componentVersion,
            "runtimeProtocol" to protocol, "runtimeApi" to "1.8", "family" to componentProfile,
            "applicationId" to packagedApplicationId, "versionCode" to packagedVersionCode, "versionName" to packagedVersionName,
            "minSdk" to 24, "abis" to componentAbis, "dependencies" to dependencies)
        val output = componentAssets.get().file("packaging/runner-component.json").asFile
        output.parentFile.mkdirs()
        output.writeText(JsonOutput.toJson(descriptor))
    }
}

android {
    namespace = "com.autoscript.runner"
    compileSdk = 36
    ndkVersion = "30.0.14904198"

    defaultConfig {
        applicationId = packagedApplicationId
        minSdk = 24
        targetSdk = 36
        versionCode = packagedVersionCode
        versionName = packagedVersionName
        manifestPlaceholders["runnerLabel"] = packagedDisplayName
        @Suppress("UNCHECKED_CAST")
        ndk { abiFilters += rootProject.extra["autoscript.abis"] as List<String> }
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

if (embeddedReleaseDirectory == null) {
    android.sourceSets.getByName("main").assets.srcDir(componentAssets)
    tasks.named("preBuild").configure { dependsOn(generateRunnerComponent) }
}

// Explicit offline development export, separate from the unsigned release template used by tests.
val exportRunnerComponent = tasks.register<Exec>("exportRunnerComponent") {
    dependsOn("assembleRelease")
    val source = layout.buildDirectory.file("outputs/apk/release/runner-template-android-release-unsigned.apk")
    val target = layout.buildDirectory.file("outputs/components/runner-$componentProfile-${providers.gradleProperty("autoscript.abiProfile").getOrElse("development")}.apk")
    inputs.file(source)
    outputs.file(target)
    outputs.upToDateWhen { false }
    doFirst {
        require(embeddedReleaseDirectory == null) { "Component export cannot include a project" }
        val debug = android.signingConfigs.getByName("debug")
        val signer = (rootProject.extra["autoscript.buildToolsDirectory"] as File).resolve("lib/apksigner.jar")
        require(signer.isFile && debug.storeFile?.isFile == true) { "SDK signer and existing Studio development keystore required" }
        target.get().asFile.parentFile.mkdirs()
        // Password values are supplied via the child environment, never command-line arguments.
        environment("AUTOSCRIPT_COMPONENT_STORE_PASS", requireNotNull(debug.storePassword))
        environment("AUTOSCRIPT_COMPONENT_KEY_PASS", requireNotNull(debug.keyPassword))
        commandLine(File(System.getProperty("java.home"), "bin/java${if (System.getProperty("os.name").startsWith("Windows")) ".exe" else ""}"),
            "-jar", signer, "sign", "--ks", debug.storeFile, "--ks-key-alias", debug.keyAlias,
            "--ks-pass", "env:AUTOSCRIPT_COMPONENT_STORE_PASS", "--key-pass", "env:AUTOSCRIPT_COMPONENT_KEY_PASS",
            "--min-sdk-version", "24", "--v1-signing-enabled", "false", "--v2-signing-enabled", "true",
            "--v3-signing-enabled", "false", "--v4-signing-enabled", "false", "--out", target.get().asFile, source.get().asFile)
    }
}

if (embeddedReleaseDirectory != null) {
    val releasePackagerExecutable = rootProject.layout.projectDirectory.file(
        if (System.getProperty("os.name").startsWith("Windows", ignoreCase = true)) {
            "../rust/target/debug/release-packager.exe"
        } else {
            "../rust/target/debug/release-packager"
        },
    )
    val generatedReleaseAssets = layout.buildDirectory.dir("generated/release-assets")
    val buildReleasePackager by tasks.registering(Exec::class) {
        workingDir(rootProject.file("../rust"))
        commandLine("cargo", "build", "-p", "release-packager")
        outputs.file(releasePackagerExecutable)
        outputs.upToDateWhen { false }
    }
    val verifyEmbeddedRelease by tasks.registering(Exec::class) {
        dependsOn(buildReleasePackager)
        workingDir(rootProject.projectDir.parentFile)
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
