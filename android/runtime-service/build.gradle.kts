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
val rootDaemonLibraries = layout.buildDirectory.dir("generated/rootDaemon/jniLibs")

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

val syncRootDaemons = rootDaemonTargets.mapIndexed { index, target ->
    val taskSuffix = target.abi.replace("-", "_").replaceFirstChar(Char::uppercase)
    val cargoOutput = rootProject.layout.projectDirectory.file(
        "target/${target.rustTarget}/debug/root-daemon",
    )
    val buildTask = tasks.register<Exec>("buildRootDaemon$taskSuffix") {
        if (index > 0) {
            val previous = rootDaemonTargets[index - 1].abi
                .replace("-", "_")
                .replaceFirstChar(Char::uppercase)
            dependsOn("buildRootDaemon$previous")
        }
        workingDir(rootProject.projectDir)
        commandLine(
            "cargo", "build", "--offline", "-p", "root-daemon", "--target", target.rustTarget,
        )
        environment(target.ccEnvironment, llvmBin.resolve(target.clang).absolutePath)
        environment(target.arEnvironment, llvmBin.resolve("llvm-ar.exe").absolutePath)
        environment(target.linkerEnvironment, llvmBin.resolve(target.clang).absolutePath)
        inputs.files(rootProject.fileTree("engine/crates") { include("**/*.rs", "**/Cargo.toml") })
        inputs.files(rootProject.file("Cargo.toml"), rootProject.file("Cargo.lock"))
        outputs.file(cargoOutput)
    }
    tasks.register<Copy>("syncRootDaemon$taskSuffix") {
        dependsOn(buildTask)
        from(cargoOutput)
        into(rootDaemonLibraries.map { it.dir(target.abi) })
        rename { "libautoscript_root_daemon.so" }
    }
}

android {
    namespace = "com.autoscript.runtime.service"
    compileSdk = 36
    defaultConfig {
        minSdk = 24
        consumerProguardFiles("consumer-rules.pro")
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    sourceSets.getByName("main").jniLibs.srcDir(rootDaemonLibraries)
    packaging.jniLibs.keepDebugSymbols += "**/libautoscript_root_daemon.so"
}

tasks.named("preBuild").configure { dependsOn(syncRootDaemons) }

dependencies {
    implementation(project(":android:script-ui"))
    implementation(project(":android:runtime-api"))
    implementation(project(":android:engine-jni"))
    implementation("org.opencv:opencv:4.10.0")
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.19.2")
    testImplementation(libs.junit4)
}
