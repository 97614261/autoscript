package com.autoscript.studio

import org.junit.Assert.*
import org.junit.Test

class RunnerAbiContentTest {
    private val base = setOf("AndroidManifest.xml", "resources.arsc", "classes.dex")
    private fun libraries(abi: String) = setOf("libengine_jni.so", "libautoscript_root_daemon.so",
        "libopencv_java4.so", "libonnxruntime.so", "libonnxruntime4j_jni.so", "libc++_shared.so").map { "lib/$abi/$it" }.toSet()

    @Test fun completePhoneAndDevelopmentRuntimesAreAccepted() {
        assertTrue(RunnerApkArchive.hasRuntimeContent(base + libraries("arm64-v8a")))
        assertTrue(RunnerApkArchive.hasRuntimeContent(base + libraries("x86_64")))
        assertTrue(RunnerApkArchive.hasRuntimeContent(base + libraries("arm64-v8a") + libraries("x86_64")))
    }

    @Test fun partialSecondAbiOrMissingRuntimeDependencyIsRejected() {
        val phone = base + libraries("arm64-v8a")
        assertFalse(RunnerApkArchive.hasRuntimeContent(phone + "lib/x86_64/libengine_jni.so"))
        for (library in libraries("arm64-v8a")) assertFalse(RunnerApkArchive.hasRuntimeContent(phone - library))
        assertFalse(RunnerApkArchive.hasRuntimeContent(base))
        for (library in libraries("x86_64")) assertFalse(RunnerApkArchive.hasRuntimeContent(base + libraries("x86_64") - library))
        assertFalse(RunnerApkArchive.hasRuntimeContent(phone + libraries("armeabi-v7a")))
    }

    @Test fun selectiveFamiliesRequireTheirExactNativeDependencies() {
        val core = setOf("libengine_jni.so", "libautoscript_root_daemon.so").map { "lib/arm64-v8a/$it" }.toSet()
        val shared = "lib/arm64-v8a/libc++_shared.so"
        val cv = "lib/arm64-v8a/libopencv_java4.so"
        val onnx = setOf("libonnxruntime.so", "libonnxruntime4j_jni.so").map { "lib/arm64-v8a/$it" }.toSet()
        assertTrue(RunnerApkArchive.hasRuntimeContent(base + core, RunnerTemplateFamily.BASE))
        assertTrue(RunnerApkArchive.hasRuntimeContent(base + core + shared + cv, RunnerTemplateFamily.OPENCV))
        assertTrue(RunnerApkArchive.hasRuntimeContent(base + core + onnx, RunnerTemplateFamily.ONNX))
        assertFalse(RunnerApkArchive.hasRuntimeContent(base + core + shared + onnx, RunnerTemplateFamily.ONNX))
        assertFalse(RunnerApkArchive.hasRuntimeContent(base + core + shared + cv, RunnerTemplateFamily.BASE))
        assertFalse(RunnerApkArchive.hasRuntimeContent(base + core + shared + cv + onnx, RunnerTemplateFamily.ONNX))
        assertFalse(RunnerApkArchive.hasRuntimeContent(base + core + onnx - onnx.first(), RunnerTemplateFamily.ONNX))
        assertFalse(RunnerApkArchive.hasRuntimeContent(base + core + "lib/arm64-v8a/libunknown.so", RunnerTemplateFamily.BASE))
    }
}
