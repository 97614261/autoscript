import java.util.Properties

plugins { alias(libs.plugins.android.library) }

val compilerProperties = Properties().apply {
    rootProject.file("local.properties").inputStream().use(::load)
}
val compilerSdk = file(requireNotNull(compilerProperties.getProperty("sdk.dir")))
val compilerLlvm = compilerSdk.resolve("ndk/30.0.14904198/toolchains/llvm/prebuilt/windows-x86_64/bin")
val compilerLibraries = layout.buildDirectory.dir("generated/jniLibs")
val compilerTargets = listOf(
    Triple("aarch64-linux-android", "arm64-v8a", "aarch64-linux-android24-clang.cmd"),
    Triple("x86_64-linux-android", "x86_64", "x86_64-linux-android24-clang.cmd"),
)
val compilerSyncTasks = compilerTargets.mapIndexed { index, (target, abi, clang) ->
    val suffix = abi.replace("-", "_").replaceFirstChar(Char::uppercase)
    val library = rootProject.layout.projectDirectory.file("target/$target/debug/libstudio_compiler_jni.so")
    val compileTask = tasks.register<Exec>("buildCompiler$suffix") {
        if (index > 0) {
            val previous = compilerTargets[index - 1].second.replace("-", "_").replaceFirstChar(Char::uppercase)
            dependsOn("buildCompiler$previous")
        }
        workingDir(rootProject.projectDir)
        commandLine("cargo", "build", "--offline", "-p", "studio-compiler-jni", "--target", target)
        environment("CARGO_TARGET_${target.uppercase().replace('-', '_')}_LINKER", compilerLlvm.resolve(clang).absolutePath)
        environment("CC_${target.replace('-', '_')}", compilerLlvm.resolve(clang).absolutePath)
        environment("AR_${target.replace('-', '_')}", compilerLlvm.resolve("llvm-ar.exe").absolutePath)
        for (module in listOf("tools/studio-compiler-jni", "tools/flow-compiler", "engine/crates/flow-ir")) {
            inputs.files(rootProject.fileTree(module) { include("src/**/*.rs", "Cargo.toml") })
        }
        inputs.files(rootProject.file("Cargo.toml"), rootProject.file("Cargo.lock"))
        outputs.file(library)
    }
    tasks.register<Copy>("syncCompiler$suffix") {
        dependsOn(compileTask)
        from(library)
        into(compilerLibraries.map { it.dir(abi) })
    }
}
android {
    namespace = "com.autoscript.studio.compiler"
    compileSdk = 36
    defaultConfig { minSdk = 24 }
    sourceSets.getByName("main").jniLibs.srcDir(compilerLibraries)
}
tasks.named("preBuild").configure { dependsOn(compilerSyncTasks) }
