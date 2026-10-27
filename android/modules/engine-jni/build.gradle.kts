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
val rustUnstripped = layout.buildDirectory.dir("generated/unstripped")
val nativeProfile = rootProject.extra["autoscript.nativeProfile"] as String
val nativeOutputProfile = rootProject.extra["autoscript.nativeOutputProfile"] as String

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

val selectedRustTargets = rustTargets.filter { it.abi in (rootProject.extra["autoscript.abis"] as List<*>) }
val syncRustLibraries = selectedRustTargets.mapIndexed { index, target ->
    val taskSuffix = target.abi.replace("-", "_").replaceFirstChar(Char::uppercase)
    val cargoOutput = rootProject.layout.projectDirectory.file(
        "../rust/target/${target.rustTarget}/$nativeOutputProfile/libengine_jni.so",
    )
    val buildTask = tasks.register<Exec>("buildRust$taskSuffix") {
        if (index > 0) {
            val previous = selectedRustTargets[index - 1].abi
                .replace("-", "_")
                .replaceFirstChar(Char::uppercase)
            dependsOn("buildRust$previous")
        }
        workingDir(rootProject.file("../rust"))
        commandLine(
            "cargo", "build", "--offline", "--profile", nativeProfile, "-p", "engine-jni", "--target", target.rustTarget,
        )
        environment(target.ccEnvironment, llvmBin.resolve(target.clang).absolutePath)
        environment(target.arEnvironment, llvmBin.resolve("llvm-ar.exe").absolutePath)
        environment(target.linkerEnvironment, llvmBin.resolve(target.clang).absolutePath)
        inputs.files(rootProject.fileTree("../rust/crates") { include("**/*.rs", "**/Cargo.toml") })
        inputs.files(rootProject.file("../rust/Cargo.toml"), rootProject.file("../rust/Cargo.lock"))
        inputs.property("nativeProfile", nativeProfile)
        outputs.file(cargoOutput)
    }
    val copyTask = tasks.register<Copy>("syncRust$taskSuffix") {
        dependsOn(buildTask)
        from(cargoOutput)
        into(rustUnstripped.map { it.dir(target.abi) })
    }
    tasks.register<Exec>("stripRust$taskSuffix") {
        dependsOn(copyTask)
        val input = rustUnstripped.get().file("${target.abi}/libengine_jni.so").asFile
        val output = rustJniRoot.get().file("${target.abi}/libengine_jni.so").asFile
        inputs.file(input); inputs.file(llvmBin.resolve("llvm-strip.exe"))
        outputs.file(output)
        doFirst { output.parentFile.mkdirs() }
        commandLine(llvmBin.resolve("llvm-strip.exe").absolutePath, "--strip-debug", "-o", output.absolutePath, input.absolutePath)
    }
}

android {
    namespace = "com.autoscript.engine.jni"
    compileSdk = 36
    ndkVersion = "30.0.14904198"
    defaultConfig {
        minSdk = 24
        consumerProguardFiles("consumer-rules.pro")
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    sourceSets.getByName("main").jniLibs.srcDir(rustJniRoot)
}

tasks.named("preBuild").configure { dependsOn(syncRustLibraries) }
