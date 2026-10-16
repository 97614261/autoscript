import java.util.Properties
import java.security.MessageDigest

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

val visionProfile = rootProject.extra["autoscript.visionProfile"] as String
val withOpenCv = visionProfile in setOf("opencv", "full")
val withOnnx = visionProfile in setOf("onnx", "full")
val acceptanceOcrCandidate = providers.gradleProperty("autoscript.acceptanceOcrCandidate").map {
    require(it in setOf("true", "false")) { "autoscript.acceptanceOcrCandidate must be true or false" }
    it.toBoolean()
}.getOrElse(false)
require(!acceptanceOcrCandidate || withOnnx) { "OCR candidate evaluation requires the ONNX SDK" }
val candidateAssets = rootProject.file("../build/ocr-evaluation/assets")
val validateOcrCandidate by tasks.registering {
    val candidate = candidateAssets.resolve("candidate/en_PP-OCRv5_rec_mobile.onnx")
    inputs.file(candidate)
    doLast {
        require(candidate.length() == 7_872_351L) { "Fixed OCR candidate size differs" }
        val digest = MessageDigest.getInstance("SHA-256").digest(candidate.readBytes())
            .joinToString("") { "%02x".format(it.toInt() and 255) }
        require(digest == "c3461add59bb4323ecba96a492ab75e06dda42467c9e3d0c18db5d1d21924be8") { "Fixed OCR candidate hash differs" }
        require(candidateAssets.walkTopDown().filter { it.isFile }.toList() == listOf(candidate)) { "Unexpected OCR evaluation assets" }
    }
}
val acceptanceProviders = providers.gradleProperty("autoscript.acceptanceProviders").map {
    require(it in setOf("true", "false")) { "autoscript.acceptanceProviders must be true or false" }
    it.toBoolean()
}.getOrElse(false)
require(!acceptanceProviders || visionProfile == "base") { "External provider acceptance must use base, without bundled SDKs" }
val acceptanceAssets = layout.buildDirectory.dir("generated/acceptance-provider-assets")
val prepareAcceptanceProviders by tasks.registering(Sync::class) {
    dependsOn(":apps:vision-opencv-component:exportVisionComponent", ":apps:vision-onnx-component:exportVisionComponent")
    into(acceptanceAssets.map { it.dir("fixed-providers") })
    for (kind in listOf("opencv", "onnx")) {
        from(project(":apps:vision-$kind-component").layout.buildDirectory.file(
            "outputs/components/vision-$kind-${providers.gradleProperty("autoscript.abiProfile").getOrElse("development")}.apk")) {
            rename { "$kind.apk" }
        }
    }
}
val generatedVisionSources = layout.buildDirectory.dir("generated/source/vision-components/kotlin")
val generateVisionBackends = tasks.register("generateVisionBackends") {
    inputs.property("visionProfile", visionProfile)
    outputs.dir(generatedVisionSources)
    doLast {
        val file = generatedVisionSources.get().file("com/autoscript/runtime/service/GeneratedVisionBackends.kt").asFile
        file.parentFile.mkdirs()
        file.writeText("""
            package com.autoscript.runtime.service
            import android.content.res.AssetManager
            internal fun createGrayBackend(): NativeVisionBackend? = ${if (withOpenCv) "NativeGrayMatcher()" else "null"}
            internal fun createOnnxBackend(assets: AssetManager): NativeVisionBackend? = ${if (withOnnx) "NativeOnnxRecognizer(assets)" else "null"}
            internal fun bundledVisionKinds(): Set<String> = setOf(${listOfNotNull("\"opencv\"".takeIf { withOpenCv }, "\"onnx\"".takeIf { withOnnx }).joinToString(",")})
        """.trimIndent())
    }
}

val localProperties = Properties().apply {
    rootProject.file("local.properties").inputStream().use(::load)
}
val sdkDirectory = file(requireNotNull(localProperties.getProperty("sdk.dir")))
val ndkDirectory = sdkDirectory.resolve("ndk/30.0.14904198")
val llvmBin = ndkDirectory.resolve("toolchains/llvm/prebuilt/windows-x86_64/bin")
val rootDaemonLibraries = layout.buildDirectory.dir("generated/rootDaemon/jniLibs")
val daemonUnstripped = layout.buildDirectory.dir("generated/rootDaemon/unstripped")

data class RootDaemonTarget(
    val rustTarget: String,
    val abi: String,
    val clang: String,
    val ccEnvironment: String,
    val arEnvironment: String,
    val linkerEnvironment: String,
)

val rootDaemonTargets = listOf(
    RootDaemonTarget(
        rustTarget = "aarch64-linux-android",
        abi = "arm64-v8a",
        clang = "aarch64-linux-android24-clang.cmd",
        ccEnvironment = "CC_aarch64_linux_android",
        arEnvironment = "AR_aarch64_linux_android",
        linkerEnvironment = "CARGO_TARGET_AARCH64_LINUX_ANDROID_LINKER",
    ),
    RootDaemonTarget(
        rustTarget = "x86_64-linux-android",
        abi = "x86_64",
        clang = "x86_64-linux-android24-clang.cmd",
        ccEnvironment = "CC_x86_64_linux_android",
        arEnvironment = "AR_x86_64_linux_android",
        linkerEnvironment = "CARGO_TARGET_X86_64_LINUX_ANDROID_LINKER",
    ),
)

val selectedDaemonTargets = rootDaemonTargets.filter { it.abi in (rootProject.extra["autoscript.abis"] as List<*>) }
val syncRootDaemons = selectedDaemonTargets.mapIndexed { index, target ->
    val taskSuffix = target.abi.replace("-", "_").replaceFirstChar(Char::uppercase)
    val cargoOutput = rootProject.layout.projectDirectory.file(
        "../rust/target/${target.rustTarget}/debug/root-daemon",
    )
    val buildTask = tasks.register<Exec>("buildRootDaemon$taskSuffix") {
        if (index > 0) {
            val previous = selectedDaemonTargets[index - 1].abi
                .replace("-", "_")
                .replaceFirstChar(Char::uppercase)
            dependsOn("buildRootDaemon$previous")
        }
        workingDir(rootProject.file("../rust"))
        commandLine(
            "cargo", "build", "--offline", "-p", "root-daemon", "--target", target.rustTarget,
        )
        environment(target.ccEnvironment, llvmBin.resolve(target.clang).absolutePath)
        environment(target.arEnvironment, llvmBin.resolve("llvm-ar.exe").absolutePath)
        environment(target.linkerEnvironment, llvmBin.resolve(target.clang).absolutePath)
        inputs.files(rootProject.fileTree("../rust/crates") { include("**/*.rs", "**/Cargo.toml") })
        inputs.files(rootProject.file("../rust/Cargo.toml"), rootProject.file("../rust/Cargo.lock"))
        outputs.file(cargoOutput)
    }
    val copyTask = tasks.register<Copy>("syncRootDaemon$taskSuffix") {
        dependsOn(buildTask)
        from(cargoOutput)
        into(daemonUnstripped.map { it.dir(target.abi) })
        rename { "libautoscript_root_daemon.so" }
    }
    tasks.register<Exec>("stripRootDaemon$taskSuffix") {
        dependsOn(copyTask)
        val input = daemonUnstripped.get().file("${target.abi}/libautoscript_root_daemon.so").asFile
        val output = rootDaemonLibraries.get().file("${target.abi}/libautoscript_root_daemon.so").asFile
        inputs.file(input); inputs.file(llvmBin.resolve("llvm-strip.exe"))
        outputs.file(output)
        doFirst { output.parentFile.mkdirs() }
        commandLine(llvmBin.resolve("llvm-strip.exe").absolutePath, "--strip-debug", "-o", output.absolutePath, input.absolutePath)
    }
}

android {
    namespace = "com.autoscript.runtime.service"
    compileSdk = 36
    ndkVersion = "30.0.14904198"
    defaultConfig {
        minSdk = 24
        testApplicationId = "com.autoscript.vision.acceptance"
        testInstrumentationRunner = "com.autoscript.runtime.service.VisionAcceptanceInstrumentation"
        consumerProguardFiles("consumer-rules.pro")
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    sourceSets.getByName("main").jniLibs.srcDir(rootDaemonLibraries)
    sourceSets.getByName("main").java.srcDir(generatedVisionSources)
    if (acceptanceProviders) sourceSets.getByName("androidTest").assets.srcDir(acceptanceAssets)
    if (withOpenCv) {
        sourceSets.getByName("main").java.srcDir("src/opencv/java")
        sourceSets.getByName("main").assets.srcDir("src/opencv/assets")
        sourceSets.getByName("androidTest").java.srcDir("src/opencvAndroidTest/java")
    }
    if (withOnnx) {
        sourceSets.getByName("main").java.srcDir("src/onnx/java")
        sourceSets.getByName("main").assets.srcDir("src/onnx/assets")
        sourceSets.getByName("androidTest").java.srcDir("src/onnxAndroidTest/java")
    }
    if (acceptanceOcrCandidate) sourceSets.getByName("androidTest").assets.srcDir(candidateAssets)
    packaging.jniLibs.keepDebugSymbols += "**/libautoscript_root_daemon.so"
}

tasks.named("preBuild").configure { dependsOn(syncRootDaemons, generateVisionBackends) }
if (acceptanceOcrCandidate) tasks.matching { it.name == "mergeDebugAndroidTestAssets" }.configureEach { dependsOn(validateOcrCandidate) }
if (acceptanceProviders) tasks.matching { it.name == "mergeDebugAndroidTestAssets" }.configureEach { dependsOn(prepareAcceptanceProviders) }

dependencies {
    implementation(project(":android:brand-ui"))
    implementation(project(":android:vision-spi"))
    implementation(project(":android:vision-components"))
    implementation(project(":android:script-ui"))
    implementation(project(":android:runtime-api"))
    implementation(project(":android:engine-jni"))
    if (withOpenCv) implementation("org.opencv:opencv:4.10.0")
    if (withOnnx) implementation("com.microsoft.onnxruntime:onnxruntime-android:1.19.2")
    testImplementation(libs.junit4)
}
