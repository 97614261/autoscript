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

android {
    namespace = "com.autoscript.studio"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.autoscript.studio"
        minSdk = 24
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
        ndk { abiFilters += listOf("arm64-v8a", "x86_64") }
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

tasks.named("preBuild").configure { dependsOn(generateBlockCatalog) }

dependencies {
    implementation(project(":android:core-model"))
    implementation(project(":android:core-designsystem"))
    implementation(project(":android:auth-api"))
    implementation(project(":android:auth-local"))
    implementation(project(":android:runtime-client"))
    implementation(project(":android:runtime-api"))
    implementation(project(":android:runtime-service"))
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
