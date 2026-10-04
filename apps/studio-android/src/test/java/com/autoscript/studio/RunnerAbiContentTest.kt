package com.autoscript.studio

import org.junit.Assert.*
import org.junit.Test

class RunnerAbiContentTest {
    private val base = setOf("AndroidManifest.xml", "resources.arsc", "classes.dex")
    private fun libraries(abi: String) = setOf("libengine_jni.so", "libautoscript_root_daemon.so",
        "libopencv_java4.so", "libonnxruntime.so", "libonnxruntime4j_jni.so", "libc++_shared.so").map { "lib/$abi/$it" }.toSet()

    @Test fun completePhoneAndDevelopmentRuntimesAreAccepted() {
        assertTrue(RunnerApkArchive.hasRuntimeContent(base + libraries("arm64-v8a")))
        assertTrue(RunnerApkArchive.hasRuntimeContent(base + libraries("arm64-v8a") + libraries("x86_64")))
    }

    @Test fun partialSecondAbiOrMissingRuntimeDependencyIsRejected() {
        val phone = base + libraries("arm64-v8a")
        assertFalse(RunnerApkArchive.hasRuntimeContent(phone + "lib/x86_64/libengine_jni.so"))
        for (library in libraries("arm64-v8a")) assertFalse(RunnerApkArchive.hasRuntimeContent(phone - library))
        assertFalse(RunnerApkArchive.hasRuntimeContent(base + libraries("x86_64")))
        assertFalse(RunnerApkArchive.hasRuntimeContent(phone + libraries("armeabi-v7a")))
    }
}
