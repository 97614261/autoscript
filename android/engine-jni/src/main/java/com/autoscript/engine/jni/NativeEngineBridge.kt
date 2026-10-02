package com.autoscript.engine.jni

class NativeEngineBridge private constructor() {
    companion object {
        init {
            System.loadLibrary("engine_jni")
        }

        private val visualCompilerLoaded: Boolean by lazy {
            try { System.loadLibrary("studio_compiler_jni"); true }
            catch (_: UnsatisfiedLinkError) { false }
            catch (_: SecurityException) { false }
        }

        @JvmStatic fun ensureVisualCompilerLoaded(): Boolean = visualCompilerLoaded

        @JvmStatic external fun nativeCreate(width: Int, height: Int): Long
        @JvmStatic external fun nativeSetWakeListener(handle: Long, listener: Any): Int
        @JvmStatic external fun nativeSetVisionListener(handle: Long, listener: Any): Int
        @JvmStatic external fun nativeRegisterTemplate(
            handle: Long,
            name: String,
            width: Int,
            height: Int,
            rgbaPixels: ByteArray,
        ): Int
        @JvmStatic external fun nativeRegisterDictionary(
            handle: Long,
            path: String,
            bytes: ByteArray,
        ): Int
        @JvmStatic external fun nativeValidateLua(source: ByteArray, chunkName: String): String?
        @JvmStatic external fun nativeCompileVisualProject(projectDirectory: String): String?
        @JvmStatic external fun nativeValidateVisualDraft(
            projectDirectory: String,
            flowId: String,
            draft: ByteArray,
        ): String?
        @JvmStatic external fun nativeReset(handle: Long, width: Int, height: Int): Int
        @JvmStatic external fun nativeStart(
            handle: Long,
            source: ByteArray,
            capabilities: Array<String>,
        ): Int
        @JvmStatic external fun nativePump(handle: Long, bootNanos: Long): Int
        @JvmStatic external fun nativeConfigureUiValues(handle: Long, valuesJson: String): Int
        @JvmStatic external fun nativeUpdateDisplay(
            handle: Long,
            snapshotId: Long,
            width: Int,
            height: Int,
        ): Int
        @JvmStatic external fun nativeConfigureProject(
            handle: Long,
            snapshotId: Long,
            designWidth: Int,
            designHeight: Int,
            scaleMode: Int,
        ): Int
        @JvmStatic external fun nativeStop(handle: Long, bootNanos: Long): Int
        @JvmStatic external fun nativePause(handle: Long, bootNanos: Long): Int
        @JvmStatic external fun nativeResume(handle: Long, bootNanos: Long): Int
        @JvmStatic external fun nativeStep(handle: Long, bootNanos: Long): Int
        @JvmStatic external fun nativeDebugSnapshot(handle: Long): String?
        @JvmStatic external fun nativePushUiEvent(handle: Long, id: String, event: String, value: String, dispatch: Boolean): Int
        @JvmStatic external fun nativeInputFeatures(handle: Long): Int
        @JvmStatic external fun nativeAttachRoot(
            handle: Long,
            socketPath: String,
            key: ByteArray,
            timeoutMillis: Int,
        ): Int
        @JvmStatic external fun nativeDetachRoot(handle: Long): Int
        @JvmStatic external fun nativeState(handle: Long): Int
        @JvmStatic external fun nativeLastDiagnostic(handle: Long): String?
        @JvmStatic external fun nativeCapturePreview(handle: Long): ByteArray?
        @JvmStatic external fun nativeTestTemplate(
            frameWidth: Int, frameHeight: Int, frameRgba: ByteArray,
            templateWidth: Int, templateHeight: Int, templateRgba: ByteArray,
            tolerance: Int, similarityPermille: Int,
        ): IntArray?
        @JvmStatic external fun nativeDrainScriptLogs(handle: Long): Array<String>
        @JvmStatic external fun nativeDrainScriptPrompts(handle: Long): Array<String>
        @JvmStatic external fun nativeNextWakeNanos(handle: Long): Long
        @JvmStatic external fun nativeDestroy(handle: Long): Int
    }
}
