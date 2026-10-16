plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

val apiFunctions = rootProject.file("../schema/api-schema/functions")
val generatorExecutable = rootProject.layout.projectDirectory.file(
    if (System.getProperty("os.name").startsWith("Windows", ignoreCase = true)) {
        "../rust/target/debug/api-codegen.exe"
    } else {
        "../rust/target/debug/api-codegen"
    },
)
val generatedApiRoot = layout.buildDirectory.dir("generated/source/api-contract/kotlin")
val generatedApiFile = generatedApiRoot.map {
    it.file("com/autoscript/runtime/api/generated/GeneratedApiContracts.kt")
}

val buildApiCodegen by tasks.registering(Exec::class) {
    workingDir(rootProject.file("../rust"))
    commandLine("cargo", "build", "-p", "api-codegen")
    inputs.files(rootProject.fileTree("../rust/tools/api-codegen/src"))
    inputs.files(rootProject.file("../rust/tools/api-codegen/Cargo.toml"), rootProject.file("../rust/Cargo.toml"), rootProject.file("../rust/Cargo.lock"), rootProject.file("../rust/rust-toolchain.toml"))
    outputs.file(generatorExecutable)
}

val generateApiContracts by tasks.registering(Exec::class) {
    dependsOn(buildApiCodegen)
    workingDir(rootProject.projectDir.parentFile)
    inputs.dir(apiFunctions)
    inputs.file(generatorExecutable)
    outputs.file(generatedApiFile)
    doFirst { generatedApiFile.get().asFile.parentFile.mkdirs() }
    commandLine(
        generatorExecutable.asFile.absolutePath,
        "kotlin",
        rootProject.projectDir.parentFile.absolutePath,
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

dependencies { implementation(libs.gson) }
