package com.autoscript.runtime.service

import dalvik.system.PathClassLoader
import com.autoscript.runtime.vision.components.PreparedVisionComponent

/**
 * Loads only a verified, host-owned provider snapshot. The descriptor chooses
 * the class; scripts never provide a dex, native-library, or model path.
 * The class loader and frozen files are retained for process lifetime because Android
 * does not guarantee safe unloading of native code.
 */
internal class FixedVisionProviderLoader {
    /** Recreated service sessions reuse retained code without consuming snapshot slots. */
    fun loadExisting(kind: com.autoscript.runtime.vision.components.VisionComponentKind, id: String,
        processAbi: String, checkpoint: () -> Unit): LoadedVisionProvider? {
        val code = processPins.existing(kind.id, "$id:$processAbi") ?: return null
        return createSession(code, kind, checkpoint)
    }
    fun inspect(prepared: PreparedVisionComponent?, kind: String, processAbi: String): NativeVisionAvailability {
        if (prepared == null) return NativeVisionAvailability(kind, NativeVisionAvailabilityState.MISSING)
        return try {
            if (prepared.processAbi != processAbi || processAbi !in prepared.verified.descriptor.abis) {
                return NativeVisionAvailability(kind, NativeVisionAvailabilityState.DEVICE_UNSUPPORTED)
            }
            require(prepared.verified.descriptor.kind.id == kind)
            prepared.verify { }
            NativeVisionAvailability(kind, NativeVisionAvailabilityState.AVAILABLE)
        } catch (_: com.autoscript.runtime.vision.components.VisionComponentDeviceUnsupported) {
            NativeVisionAvailability(kind, NativeVisionAvailabilityState.DEVICE_UNSUPPORTED)
        } catch (_: com.autoscript.runtime.vision.components.VisionComponentCompatibilityException) {
            NativeVisionAvailability(kind, NativeVisionAvailabilityState.INCOMPATIBLE)
        } catch (error: Throwable) {
            if (error is java.util.concurrent.CancellationException || error is InterruptedException) throw error
            NativeVisionAvailability(kind, NativeVisionAvailabilityState.CORRUPT)
        }
    }

    /** Consumes a prepared owner. SDK sessions close independently of pinned native code. */
    fun load(prepared: PreparedVisionComponent, processAbi: String, checkpoint: () -> Unit = {}): LoadedVisionProvider {
        var backend: NativeVisionBackend? = null
        try {
            prepared.verify(checkpoint)
            val descriptor = prepared.verified.descriptor
            require(prepared.processAbi == processAbi && processAbi in descriptor.abis) {
                "VISION_COMPONENT_DEVICE_UNSUPPORTED"
            }
            checkpoint()
            val code = processPins.pin(descriptor.kind.id, "${prepared.verified.sha256}:$processAbi") {
                val lease = prepared.retain()
                try {
                    val loader = PathClassLoader(prepared.apk.absolutePath, prepared.nativeDirectory.absolutePath,
                        NativeVisionProvider::class.java.classLoader)
                    val source = prepared.model?.let { model -> NativeVisionModelSource { model.inputStream() } }
                    PinnedCode(loader, source, lease, prepared::verifyRetained)
                } catch (error: Throwable) {
                    // No provider class initialization has run at this point.
                    runCatching { lease.close() }.onFailure { error.addSuppressed(it) }
                    throw error
                }
            }
            // Pin before initializing any provider/SDK code. Failed initialization
            // may already have loaded native libraries, so it cannot unpin the files.
            prepared.close()
            val created = createSession(code, descriptor.kind, checkpoint)
            backend = created
            checkpoint()
            return created
        } catch (error: Throwable) {
            runCatching { backend?.close() }.onFailure { error.addSuppressed(it) }
            runCatching { prepared.close() }.onFailure { error.addSuppressed(it) }
            throw error
        }
    }

    private fun createSession(code: PinnedCode, kind: com.autoscript.runtime.vision.components.VisionComponentKind,
        checkpoint: () -> Unit): LoadedVisionProvider {
        code.verify(checkpoint)
        val provider = Class.forName(kind.providerClass, true, code.classLoader).getDeclaredConstructor().newInstance()
        require(provider is NativeVisionProvider && provider.spiVersion == NativeVisionSpi.VERSION && provider.kind == kind.id) {
            "VISION_PROVIDER_SPI_MISMATCH"
        }
        checkpoint()
        val backend = provider.create(code.model)
        try { checkpoint(); return LoadedVisionProvider(backend) }
        catch (error: Throwable) { runCatching { backend.close() }.onFailure { error.addSuppressed(it) }; throw error }
    }

    private data class PinnedCode(
        val classLoader: ClassLoader,
        val model: NativeVisionModelSource?,
        @Suppress("unused") val snapshotLease: AutoCloseable,
        val verify: (() -> Unit) -> Unit,
    )

    companion object {
        private val processPins = VisionProviderProcessPins<PinnedCode>()
    }
}
