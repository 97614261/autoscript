package com.autoscript.studio.compiler

/** Studio toolchain only. Runner never links this library or compiles projects. */
object ReleasePreparation {
    fun prepare(requestId: Long, projectDirectory: String, outputDirectory: String): String =
        requireNotNull(NativeReleaseBridge.nativePrepareRelease(requestId, projectDirectory, outputDirectory)) { "发布冻结引擎没有返回结果" }
    fun cancel(requestId: Long) { NativeReleaseBridge.nativeCancelRelease(requestId) }
}

internal object NativeReleaseBridge {
    init { System.loadLibrary("studio_compiler_jni") }
    @JvmStatic external fun nativePrepareRelease(requestId: Long, projectDirectory: String, outputDirectory: String): String?
    @JvmStatic external fun nativeCancelRelease(requestId: Long)
}
