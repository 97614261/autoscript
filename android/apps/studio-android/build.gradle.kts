import java.security.MessageDigest

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

val embedRunnerTemplate = providers.gradleProperty("autoscript.embedRunnerTemplate").map {
    require(it in setOf("true", "false")) { "autoscript.embedRunnerTemplate must be true or false" }
    it.toBoolean()
}.getOrElse(false)

val blockCatalog = rootProject.file("../schema/block-catalog/blocks")
val generatorExecutable = rootProject.layout.projectDirectory.file(
    if (System.getProperty("os.name").startsWith("Windows", ignoreCase = true)) {
        "../rust/target/debug/api-codegen.exe"
    } else {
        "../rust/target/debug/api-codegen"
    },
)
val generatedBlockRoot = layout.buildDirectory.dir("generated/source/block-catalog/kotlin")
val generatedBlockFile = generatedBlockRoot.map {
    it.file("com/autoscript/studio/generated/GeneratedBlockCatalog.kt")
}

val generateBlockCatalog by tasks.registering(Exec::class) {
    dependsOn(":android:runtime-api:buildApiCodegen")
    workingDir(rootProject.projectDir.parentFile)
    inputs.dir(blockCatalog)
    inputs.file(generatorExecutable)
    outputs.file(generatedBlockFile)
    doFirst { generatedBlockFile.get().asFile.parentFile.mkdirs() }
    commandLine(
        generatorExecutable.asFile.absolutePath,
        "blocks-kotlin",
        rootProject.projectDir.parentFile.absolutePath,
        generatedBlockFile.get().asFile.absolutePath,
    )
}

val generatedFunctionFile = generatedBlockRoot.map {
    it.file("com/autoscript/studio/generated/GeneratedFunctionDocumentation.kt")
}
val generateFunctionDocumentation by tasks.registering(Exec::class) {
    dependsOn(":android:runtime-api:buildApiCodegen")
    workingDir(rootProject.projectDir.parentFile)
    inputs.dir(rootProject.file("../schema/api-schema/functions"))
    inputs.file(generatorExecutable)
    outputs.file(generatedFunctionFile)
    doFirst { generatedFunctionFile.get().asFile.parentFile.mkdirs() }
    commandLine(
        generatorExecutable.asFile.absolutePath,
        "studio-functions-kotlin",
        rootProject.projectDir.parentFile.absolutePath,
        generatedFunctionFile.get().asFile.absolutePath,
    )
}

android {
    namespace = "com.autoscript.studio"
    compileSdk = 36
    ndkVersion = "30.0.14904198"

    defaultConfig {
        applicationId = "com.zhigou.kaifaban"
        minSdk = 24
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
        testApplicationId = "com.zhigou.kaifaban.acceptance"
        testInstrumentationRunner = "com.autoscript.studio.StudioAcceptanceInstrumentation"
        buildConfigField("boolean", "EMBEDDED_RUNNER_TEMPLATE", embedRunnerTemplate.toString())
        @Suppress("UNCHECKED_CAST")
        ndk { abiFilters += rootProject.extra["autoscript.abis"] as List<String> }
    }

    buildFeatures { compose = true; buildConfig = true }
    packaging { jniLibs.useLegacyPackaging = false }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    sourceSets.getByName("main").java.srcDir(generatedBlockRoot)
}

tasks.named("preBuild").configure { dependsOn(generateBlockCatalog, generateFunctionDocumentation) }

// Runner is independent; full offline embedding is an explicit developer option, never the default.
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
            """{"formatVersion":1,"applicationId":"com.zhigou.yunxingban","versionCode":1,"versionName":"0.1.0","size":${template.length()},"sha256":"$hash"}""",
        )
    }
}
if (embedRunnerTemplate) {
    android.sourceSets.getByName("main").assets.srcDir(deviceRunnerAssets)
    tasks.named("preBuild").configure { dependsOn(prepareDeviceRunnerTemplate) }
} else {
    // Explicit exclusion also protects against stale generated assets from the earlier full build.
    android.sourceSets.getByName("main").assets.exclude("packaging/runner-template.apk", "packaging/runner-template.json")
}

// Host-only verification tools, not runtime dependencies or APK assets.
val buildDevicePackagingVerifiers by tasks.registering(Exec::class) {
    workingDir(rootProject.file("../rust"))
    commandLine("cargo", "build", "--offline", "-p", "release-packager", "-p", "package-probe")
    inputs.files(rootProject.fileTree("../rust/tools/release-packager"), rootProject.fileTree("../rust/tools/package-probe"))
    val executableSuffix = if (System.getProperty("os.name").startsWith("Windows")) ".exe" else ""
    outputs.files(rootProject.file("../rust/target/debug/release-packager$executableSuffix"), rootProject.file("../rust/target/debug/package-probe$executableSuffix"))
    outputs.upToDateWhen { false }
}
val packageTestApkSigner = android.sdkDirectory.resolve("build-tools").listFiles().orEmpty()
    .map { it.resolve("lib/apksigner.jar") }.filter { it.isFile }.minByOrNull { it.parentFile.parentFile.name }
android.testOptions.unitTests.all {
    // Packaging tests use a real standalone template, without embedding it in Studio.
    it.dependsOn(buildDevicePackagingVerifiers, ":apps:runner-template-android:assembleRelease")
    it.systemProperty("autoscript.projectRoot", rootProject.projectDir.parentFile.absolutePath)
    it.systemProperty("autoscript.runnerTemplate", runnerTemplateFile.get().asFile.absolutePath)
    it.systemProperty("autoscript.componentMatrix", providers.gradleProperty("autoscript.verifyComponentMatrix").getOrElse("false"))
    it.systemProperty("autoscript.expectedAbis", (rootProject.extra["autoscript.abis"] as List<*>).joinToString(","))
    it.systemProperty("autoscript.artifactRoot", rootProject.layout.buildDirectory.get().asFile.resolve(
        if (providers.gradleProperty("autoscript.abiProfile").getOrElse("development") == "arm64") "profiles/arm64/native-example" else "native-example",
    ).absolutePath)
    it.systemProperty("autoscript.apksignerJar", packageTestApkSigner?.absolutePath.orEmpty())
}

dependencies {
    implementation(project(":android:brand-ui"))
    implementation(project(":android:vision-components"))
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
