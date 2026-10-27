import java.io.File

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}
apply(from = rootProject.file("vision-component.gradle.kts"))

android {
    namespace = "com.autoscript.components.onnx"
    compileSdk = 36
    ndkVersion = "30.0.14904198"
    defaultConfig {
        applicationId = "com.zhigou.component.onnx"
        minSdk = 24; targetSdk = 36
        versionCode = 3; versionName = "1.19.2"
        ndk { abiFilters += (rootProject.extra["autoscript.abis"] as List<*>).map { it.toString() } }
    }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    packaging { jniLibs.useLegacyPackaging = false }
    sourceSets.getByName("main") {
        java.srcDir(rootProject.file("modules/runtime-service/src/onnx/java"))
        assets.srcDir(rootProject.file("modules/runtime-service/src/onnx/assets"))
        assets.srcDir(rootProject.file("modules/runtime-service/src/onnxCandidate/assets"))
        assets.srcDir(layout.buildDirectory.dir("generated/component-assets"))
    }
}

tasks.register<Exec>("exportVisionComponent") {
    dependsOn("assembleRelease")
    val source = layout.buildDirectory.file("outputs/apk/release/${project.name}-release-unsigned.apk")
    val target = layout.buildDirectory.file("outputs/components/vision-onnx-${providers.gradleProperty("autoscript.abiProfile").getOrElse("development")}.apk")
    inputs.file(source); outputs.file(target); outputs.upToDateWhen { false }
    doFirst {
        val debug = android.signingConfigs.getByName("debug")
        val signer = (rootProject.extra["autoscript.buildToolsDirectory"] as File).resolve("lib/apksigner.jar")
        require(signer.isFile && debug.storeFile?.isFile == true) { "Existing Studio publisher development identity required" }
        target.get().asFile.parentFile.mkdirs()
        environment("AUTOSCRIPT_VISION_STORE_PASS", requireNotNull(debug.storePassword))
        environment("AUTOSCRIPT_VISION_KEY_PASS", requireNotNull(debug.keyPassword))
        val javaExecutable = if (System.getProperty("os.name").startsWith("Windows")) "java.exe" else "java"
        commandLine(File(System.getProperty("java.home"), "bin/$javaExecutable"), "-jar", signer, "sign", "--ks", debug.storeFile,
            "--ks-key-alias", debug.keyAlias, "--ks-pass", "env:AUTOSCRIPT_VISION_STORE_PASS", "--key-pass", "env:AUTOSCRIPT_VISION_KEY_PASS",
            "--min-sdk-version", "24", "--v1-signing-enabled", "false", "--v2-signing-enabled", "true",
            "--v3-signing-enabled", "false", "--v4-signing-enabled", "false", "--out", target.get().asFile, source.get().asFile)
    }
}
