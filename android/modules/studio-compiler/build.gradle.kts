import java.util.Properties

plugins { alias(libs.plugins.android.library); alias(libs.plugins.kotlin.android) }

val compilerProperties = Properties().apply {
    rootProject.file("local.properties").inputStream().use(::load)
}
val compilerSdk = file(requireNotNull(compilerProperties.getProperty("sdk.dir")))
val compilerLlvm = compilerSdk.resolve("ndk/30.0.14904198/toolchains/llvm/prebuilt/windows-x86_64/bin")
val compilerLibraries = layout.buildDirectory.dir("generated/jniLibs")
val compilerUnstripped = layout.buildDirectory.dir("generated/unstripped")
val compilerTargets = listOf(
    Triple("aarch64-linux-android", "arm64-v8a", "aarch64-linux-android24-clang.cmd"),
    Triple("x86_64-linux-android", "x86_64", "x86_64-linux-android24-clang.cmd"),
)
val selectedCompilerTargets = compilerTargets.filter { it.second in (rootProject.extra["autoscript.abis"] as List<*>) }
val compilerSyncTasks = selectedCompilerTargets.mapIndexed { index, (target, abi, clang) ->
    val suffix = abi.replace("-", "_").replaceFirstChar(Char::uppercase)
    val library = rootProject.layout.projectDirectory.file("../rust/target/$target/debug/libstudio_compiler_jni.so")
    val compileTask = tasks.register<Exec>("buildCompiler$suffix") {
        if (index > 0) {
            val previous = selectedCompilerTargets[index - 1].second.replace("-", "_").replaceFirstChar(Char::uppercase)
            dependsOn("buildCompiler$previous")
        }
        workingDir(rootProject.file("../rust"))
        commandLine("cargo", "build", "--offline", "-p", "studio-compiler-jni", "--target", target)
        environment("CARGO_TARGET_${target.uppercase().replace('-', '_')}_LINKER", compilerLlvm.resolve(clang).absolutePath)
        environment("CC_${target.replace('-', '_')}", compilerLlvm.resolve(clang).absolutePath)
        environment("AR_${target.replace('-', '_')}", compilerLlvm.resolve("llvm-ar.exe").absolutePath)
        for (module in listOf(
            "../rust/tools/studio-compiler-jni", "../rust/tools/release-packager", "../rust/tools/flow-compiler",
            "../rust/crates/flow-ir", "../rust/crates/glyph-ocr", "../rust/crates/lua-runtime",
            "../rust/crates/pixel-vision", "../rust/crates/runtime-scheduler",
        )) {
            inputs.files(rootProject.fileTree(module) { include("src/**/*.rs", "Cargo.toml") })
        }
        inputs.files(rootProject.file("../rust/Cargo.toml"), rootProject.file("../rust/Cargo.lock"))
        outputs.file(library)
    }
    val copyTask = tasks.register<Copy>("syncCompiler$suffix") {
        dependsOn(compileTask)
        from(library)
        into(compilerUnstripped.map { it.dir(abi) })
    }
    tasks.register<Exec>("stripCompiler$suffix") {
        dependsOn(copyTask)
        val input = compilerUnstripped.get().file("$abi/libstudio_compiler_jni.so").asFile
        val output = compilerLibraries.get().file("$abi/libstudio_compiler_jni.so").asFile
        inputs.file(input); inputs.file(compilerLlvm.resolve("llvm-strip.exe"))
        outputs.file(output)
        doFirst { output.parentFile.mkdirs() }
        commandLine(compilerLlvm.resolve("llvm-strip.exe").absolutePath, "--strip-debug", "-o", output.absolutePath, input.absolutePath)
    }
}
android {
    namespace = "com.autoscript.studio.compiler"
    compileSdk = 36
    ndkVersion = "30.0.14904198"
    defaultConfig { minSdk = 24 }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
    sourceSets.getByName("main").jniLibs.srcDir(compilerLibraries)
}
tasks.named("preBuild").configure { dependsOn(compilerSyncTasks) }

dependencies {
    implementation(libs.gson)
}
