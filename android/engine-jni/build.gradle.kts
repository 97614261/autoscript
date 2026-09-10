import java.util.Properties

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

val localProperties = Properties().apply {
    rootProject.file("local.properties").inputStream().use(::load)
}
val sdkDirectory = file(requireNotNull(localProperties.getProperty("sdk.dir")))
val ndkDirectory = sdkDirectory.resolve("ndk/30.0.14904198")
val llvmBin = ndkDirectory.resolve("toolchains/llvm/prebuilt/windows-x86_64/bin")
val rustJniRoot = layout.buildDirectory.dir("generated/jniLibs")

data class RustAndroidTarget(
    val rustTarget: String,
    val abi: String,
    val clang: String,
    val ccEnvironment: String,
    val arEnvironment: String,
    val linkerEnvironment: String,
)

val rustTargets = listOf(
    RustAndroidTarget(
        rustTarget = "aarch64-linux-android",
        abi = "arm64-v8a",
        clang = "aarch64-linux-android24-clang.cmd",
        ccEnvironment = "CC_aarch64_linux_android",
        arEnvironment = "AR_aarch64_linux_android",
        linkerEnvironment = "CARGO_TARGET_AARCH64_LINUX_ANDROID_LINKER",
    ),
    RustAndroidTarget(
        rustTarget = "x86_64-linux-android",
        abi = "x86_64",
        clang = "x86_64-linux-android24-clang.cmd",
        ccEnvironment = "CC_x86_64_linux_android",
        arEnvironment = "AR_x86_64_linux_android",
        linkerEnvironment = "CARGO_TARGET_X86_64_LINUX_ANDROID_LINKER",
    ),
)

val syncRustLibraries = rustTargets.mapIndexed { index, target ->
    val taskSuffix = target.abi.replace("-", "_").replaceFirstChar(Char::uppercase)
    val cargoOutput = rootProject.layout.projectDirectory.file(
        "target/${target.rustTarget}/debug/libengine_jni.so",
    )
    val buildTask = tasks.register<Exec>("buildRust$taskSuffix") {
        if (index > 0) {
            val previous = rustTargets[index - 1].abi
                .replace("-", "_")
                .replaceFirstChar(Char::uppercase)
            dependsOn("buildRust$previous")
        }
        workingDir(rootProject.projectDir)
        commandLine(
            "cargo", "build", "--offline", "-p", "engine-jni", "--target", target.rustTarget,
        )
        environment(target.ccEnvironment, llvmBin.resolve(target.clang).absolutePath)
        environment(target.arEnvironment, llvmBin.resolve("llvm-ar.exe").absolutePath)
        environment(target.linkerEnvironment, llvmBin.resolve(target.clang).absolutePath)
        inputs.files(rootProject.fileTree("engine/crates") { include("**/*.rs", "**/Cargo.toml") })
        inputs.files(
            rootProject.fileTree("tools/flow-compiler") {
                include("src/**/*.rs", "Cargo.toml")
            },
        )
        inputs.files(rootProject.file("Cargo.toml"), rootProject.file("Cargo.lock"))
        outputs.file(cargoOutput)
    }
    tasks.register<Copy>("syncRust$taskSuffix") {
        dependsOn(buildTask)
        from(cargoOutput)
        into(rustJniRoot.map { it.dir(target.abi) })
    }
}

android {
    namespace = "com.autoscript.engine.jni"
    compileSdk = 36
    ndkVersion = "30.0.14904198"
    defaultConfig { minSdk = 24 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    sourceSets.getByName("main").jniLibs.srcDir(rustJniRoot)
}

tasks.named("preBuild").configure { dependsOn(syncRustLibraries) }
