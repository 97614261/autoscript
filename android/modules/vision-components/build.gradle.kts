plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.autoscript.runtime.vision.components"
    compileSdk = 36
    defaultConfig { minSdk = 24 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    testOptions.unitTests.all {
        it.dependsOn(":apps:vision-opencv-component:exportVisionComponent", ":apps:vision-onnx-component:exportVisionComponent")
        it.systemProperty("vision.components.opencv", project(":apps:vision-opencv-component").layout.buildDirectory.get()
            .file("outputs/components/vision-opencv-${providers.gradleProperty("autoscript.abiProfile").getOrElse("development")}.apk").asFile.absolutePath)
        it.systemProperty("vision.components.onnx", project(":apps:vision-onnx-component").layout.buildDirectory.get()
            .file("outputs/components/vision-onnx-${providers.gradleProperty("autoscript.abiProfile").getOrElse("development")}.apk").asFile.absolutePath)
        it.systemProperty("vision.publisher.keystore", File(System.getProperty("user.home"), ".android/debug.keystore").absolutePath)
        it.systemProperty("vision.apksignerJar", (rootProject.extra["autoscript.buildToolsDirectory"] as File)
            .resolve("lib/apksigner.jar").absolutePath)
        it.inputs.files(
            project(":apps:vision-opencv-component").layout.buildDirectory.file("outputs/components/vision-opencv-${providers.gradleProperty("autoscript.abiProfile").getOrElse("development")}.apk"),
            project(":apps:vision-onnx-component").layout.buildDirectory.file("outputs/components/vision-onnx-${providers.gradleProperty("autoscript.abiProfile").getOrElse("development")}.apk"),
            File(System.getProperty("user.home"), ".android/debug.keystore"),
            (rootProject.extra["autoscript.buildToolsDirectory"] as File).resolve("lib/apksigner.jar"),
        )
    }
}

dependencies {
    implementation(project(":android:vision-spi"))
    implementation(libs.gson)
    testImplementation(libs.junit4)
}
