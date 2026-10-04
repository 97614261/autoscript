import java.security.MessageDigest

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

val blockCatalog = rootProject.file("schema/block-catalog/blocks")
val generatorExecutable = rootProject.layout.projectDirectory.file(
    if (System.getProperty("os.name").startsWith("Windows", ignoreCase = true)) {
        "target/debug/api-codegen.exe"
    } else {
        "target/debug/api-codegen"
    },
)
val generatedBlockRoot = layout.buildDirectory.dir("generated/source/block-catalog/kotlin")
val generatedBlockFile = generatedBlockRoot.map {
    it.file("com/autoscript/studio/generated/GeneratedBlockCatalog.kt")
}

val generateBlockCatalog by tasks.registering(Exec::class) {
    dependsOn(":android:runtime-api:buildApiCodegen")
    workingDir(rootProject.projectDir)
    inputs.dir(blockCatalog)
    inputs.file(generatorExecutable)
    outputs.file(generatedBlockFile)
    doFirst { generatedBlockFile.get().asFile.parentFile.mkdirs() }
    commandLine(
        generatorExecutable.asFile.absolutePath,
        "blocks-kotlin",
        rootProject.projectDir.absolutePath,
        generatedBlockFile.get().asFile.absolutePath,
    )
}

val generatedFunctionFile = generatedBlockRoot.map {
    it.file("com/autoscript/studio/generated/GeneratedFunctionDocumentation.kt")
}
val generateFunctionDocumentation by tasks.registering(Exec::class) {
    dependsOn(":android:runtime-api:buildApiCodegen")
    workingDir(rootProject.projectDir)
    inputs.dir(rootProject.file("schema/api-schema/functions"))
    inputs.file(generatorExecutable)
    outputs.file(generatedFunctionFile)
    doFirst { generatedFunctionFile.get().asFile.parentFile.mkdirs() }
    commandLine(
        generatorExecutable.asFile.absolutePath,
        "studio-functions-kotlin",
        rootProject.projectDir.absolutePath,
        generatedFunctionFile.get().asFile.absolutePath,
    )
}

android {
    namespace = "com.autoscript.studio"
    compileSdk = 36
    ndkVersion = "30.0.14904198"

    defaultConfig {
        applicationId = "com.autoscript.studio"
        minSdk = 24
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
        @Suppress("UNCHECKED_CAST")
        ndk { abiFilters += rootProject.extra["autoscript.abis"] as List<String> }
    }

    buildFeatures { compose = true }
    packaging { jniLibs.useLegacyPackaging = false }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    sourceSets.getByName("main").java.srcDir(generatedBlockRoot)
}

tasks.named("preBuild").configure { dependsOn(generateBlockCatalog, generateFunctionDocumentation) }

// Runner is a separate project dependency graph; only its compiled APK is embedded as a template.
val deviceRunnerAssets = layout.buildDirectory.dir("generated/device-runner-assets")
val runnerTemplateFile = project(":apps:runner-template-android").layout.buildDirectory.file("outputs/apk/release/runner-template-android-release-unsigned.apk")
val prepareDeviceRunnerTemplate by tasks.registering(Copy::class) {
    dependsOn(":apps:runner-template-android:assembleRelease")
    val template = runnerTemplateFile.get().asFile
    from(template)
    into(deviceRunnerAssets.map { it.dir("packaging") })
    rename { "runner-template.apk" }
    doFirst {
        require(!providers.gradleProperty("autoscript.releaseDir").isPresent) { "Studio device packaging requires a pristine Runner template" }
    }
    doLast {
        val digest = MessageDigest.getInstance("SHA-256")
        template.inputStream().use { input -> val bytes = ByteArray(65536); while (true) {
            val count = input.read(bytes); if (count < 0) break; digest.update(bytes, 0, count)
        } }
        val hash = digest.digest().joinToString("") { (it.toInt() and 255).toString(16).padStart(2, '0') }
        deviceRunnerAssets.get().file("packaging/runner-template.json").asFile.writeText(
            """{"formatVersion":1,"applicationId":"com.autoscript.runner","versionCode":1,"versionName":"0.1.0","size":${template.length()},"sha256":"$hash"}""",
        )
    }
}
android.sourceSets.getByName("main").assets.srcDir(deviceRunnerAssets)
tasks.named("preBuild").configure { dependsOn(prepareDeviceRunnerTemplate) }

// Host-only verification tools, not runtime dependencies or APK assets.
val buildDevicePackagingVerifiers by tasks.registering(Exec::class) {
    workingDir(rootProject.projectDir)
    commandLine("cargo", "build", "--offline", "-p", "release-packager", "-p", "package-probe")
    inputs.files(rootProject.fileTree("tools/release-packager"), rootProject.fileTree("tools/package-probe"))
    val executableSuffix = if (System.getProperty("os.name").startsWith("Windows")) ".exe" else ""
    outputs.files(rootProject.file("target/debug/release-packager$executableSuffix"), rootProject.file("target/debug/package-probe$executableSuffix"))
    outputs.upToDateWhen { false }
}
val packageTestApkSigner = android.sdkDirectory.resolve("build-tools").listFiles().orEmpty()
    .map { it.resolve("lib/apksigner.jar") }.filter { it.isFile }.minByOrNull { it.parentFile.parentFile.name }
android.testOptions.unitTests.all {
    it.dependsOn(buildDevicePackagingVerifiers)
    it.systemProperty("autoscript.projectRoot", rootProject.projectDir.absolutePath)
    it.systemProperty("autoscript.runnerTemplate", runnerTemplateFile.get().asFile.absolutePath)
    it.systemProperty("autoscript.expectedAbis", (rootProject.extra["autoscript.abis"] as List<*>).joinToString(","))
    it.systemProperty("autoscript.artifactRoot", rootProject.layout.buildDirectory.get().asFile.resolve(
        if (providers.gradleProperty("autoscript.abiProfile").getOrElse("development") == "arm64") "profiles/arm64/native-example" else "native-example",
    ).absolutePath)
    it.systemProperty("autoscript.apksignerJar", packageTestApkSigner?.absolutePath.orEmpty())
}

dependencies {
    implementation(project(":android:script-ui"))
    implementation(project(":android:core-model"))
    implementation(project(":android:core-designsystem"))
    implementation(project(":android:auth-api"))
    implementation(project(":android:auth-local"))
    implementation(project(":android:runtime-client"))
    implementation(project(":android:runtime-api"))
    implementation(project(":android:runtime-service"))
    implementation(project(":android:studio-compiler"))
    implementation(project(":android:project-store"))

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
