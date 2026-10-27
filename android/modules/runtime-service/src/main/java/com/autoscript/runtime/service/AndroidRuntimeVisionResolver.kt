package com.autoscript.runtime.service

import android.content.Context
import android.os.Build
import android.os.Process
import com.autoscript.runtime.vision.components.*

/** Wiring only; store creation and all executable-byte validation happen on the resolver's IO lane. */
internal fun androidRuntimeVisionResolver(context: Context): RuntimeVisionResolver {
    val app = context.applicationContext
    val bundled = bundledVisionKinds()
    val store by lazy { AndroidVisionComponents.store(app) }
    val loader = FixedVisionProviderLoader()
    val abi = visionProcessAbi(Process.is64Bit(), System.getProperty("os.arch").orEmpty(), Build.SUPPORTED_ABIS.toList())
    fun kind(id: String) = VisionComponentKind.entries.single { it.id == id }
    fun selected(id: String, checkpoint: () -> Unit): VerifiedVisionComponent? = try {
        val verified = store.selected(kind(id), checkpoint)
        if (verified != null) {
            if (abi == null) throw VisionComponentDeviceUnsupported()
            VisionComponentStore.checkProcessAbi(verified.descriptor, abi)
        }
        verified
    } catch (error: Throwable) { throw visionStoreFailure(error) }
    return RuntimeVisionResolver(bundled, selectedIdentity = { id -> store.selectionId(kind(id)) {} }, load = { id, checkpoint ->
        if (id in bundled) VisionResolvedBackend("bundled", requireNotNull(if (id == "opencv") createGrayBackend() else createOnnxBackend(app.assets)))
        else {
            val verified = selected(id, checkpoint) ?: throw VisionResolutionFailure(NativeVisionAvailabilityState.MISSING)
            val processAbi = requireNotNull(abi)
            try {
                val existing = loader.loadExisting(kind(id), verified.sha256, processAbi, checkpoint)
                val backend = existing ?: run {
                    val prepared = try { store.prepare(verified.sha256, kind(id), processAbi, checkpoint) }
                        catch (error: Throwable) { throw visionStoreFailure(error) }
                    loader.load(prepared, processAbi, checkpoint)
                }
                VisionResolvedBackend(verified.sha256, backend, backend.spiVersion)
            } catch (error: Throwable) { throw visionStoreFailure(error, loading = true) }
        }
    }, inspect = { id, checkpoint ->
        if (id in bundled) VisionRuntimeInspection("bundled", NativeVisionAvailabilityState.AVAILABLE, bundled = true)
        else selected(id, checkpoint)?.let { VisionRuntimeInspection(it.sha256, NativeVisionAvailabilityState.INSTALLED) }
            ?: VisionRuntimeInspection(null, NativeVisionAvailabilityState.MISSING)
    })
}

internal fun visionProcessAbi(is64Bit: Boolean, architecture: String, supported: List<String>): String? {
    if (!is64Bit) return null
    val abi = when (architecture.lowercase()) { "aarch64", "arm64", "arm64-v8a" -> "arm64-v8a"; "x86_64", "amd64" -> "x86_64"; else -> return null }
    return abi.takeIf { it in supported }
}

private fun visionStoreFailure(error: Throwable, loading: Boolean = false): Throwable {
    if (error is java.util.concurrent.CancellationException || error is InterruptedException || error is VisionResolutionFailure) return error
    val state = when {
        error is VisionComponentSelectionMissing -> NativeVisionAvailabilityState.MISSING
        error is VisionComponentDeviceUnsupported -> NativeVisionAvailabilityState.DEVICE_UNSUPPORTED
        error is VisionComponentCompatibilityException -> NativeVisionAvailabilityState.INCOMPATIBLE
        error.message == "VISION_COMPONENT_PROCESS_RESTART_REQUIRED" -> NativeVisionAvailabilityState.RESTART_REQUIRED
        error.message == "VISION_COMPONENT_BUSY" -> NativeVisionAvailabilityState.BUSY
        loading -> NativeVisionAvailabilityState.LOAD_FAILED
        else -> NativeVisionAvailabilityState.CORRUPT
    }
    return VisionResolutionFailure(state)
}
