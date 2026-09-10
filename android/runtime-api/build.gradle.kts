plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

val apiFunctions = rootProject.file("schema/api-schema/functions")
val generatorExecutable = rootProject.layout.projectDirectory.file(
    if (System.getProperty("os.name").startsWith("Windows", ignoreCase = true)) {
        "target/debug/api-codegen.exe"
    } else {
        "target/debug/api-codegen"
    },
)
val generatedApiRoot = layout.buildDirectory.dir("generated/source/api-contract/kotlin")
val generatedApiFile = generatedApiRoot.map {
    it.file("com/autoscript/runtime/api/generated/GeneratedApiContracts.kt")
}

val buildApiCodegen by tasks.registering(Exec::class) {
    workingDir(rootProject.projectDir)
    commandLine("cargo", "build", "-p", "api-codegen")
    inputs.files(rootProject.fileTree("tools/api-codegen/src"))
    inputs.file(rootProject.file("tools/api-codegen/Cargo.toml"))
    outputs.file(generatorExecutable)
}

val generateApiContracts by tasks.registering(Exec::class) {
    dependsOn(buildApiCodegen)
    workingDir(rootProject.projectDir)
    inputs.dir(apiFunctions)
    inputs.file(generatorExecutable)
    outputs.file(generatedApiFile)
    doFirst { generatedApiFile.get().asFile.parentFile.mkdirs() }
    commandLine(
        generatorExecutable.asFile.absolutePath,
        "kotlin",
        rootProject.projectDir.absolutePath,
        generatedApiFile.get().asFile.absolutePath,
    )
}

android {
    namespace = "com.autoscript.runtime.api"
    compileSdk = 36
    defaultConfig { minSdk = 24 }
    buildFeatures { aidl = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    sourceSets.getByName("main").java.srcDir(generatedApiRoot)
}

tasks.named("preBuild").configure { dependsOn(generateApiContracts) }
