package com.autoscript.studio

import android.content.Context
import android.net.Uri
import com.autoscript.core.model.RuntimeConnectionState
import com.autoscript.runtime.vision.components.AndroidVisionComponents
import com.autoscript.runtime.vision.components.StoredVisionComponent
import com.autoscript.runtime.vision.components.VisionComponentKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.util.concurrent.CancellationException

internal data class VisionProviderChoice(val id: String?, val corrupt: Boolean = false)
internal data class VisionProviderInventory(val entries: List<StoredVisionComponent>, val choices: Map<VisionComponentKind, VisionProviderChoice>)

/** Shared verifier/store, but no SDK or runtime implementation dependency in Studio. */
internal class VisionProviderRepository(context: Context, private val runtimeState: () -> RuntimeConnectionState,
    private val canMutate: () -> Boolean) {
    private val app = context.applicationContext
    suspend fun inventory(): VisionProviderInventory = operation(false) { checkpoint ->
        val store = AndroidVisionComponents.store(app)
        val entries = store.inventory(checkpoint)
        val choices = VisionComponentKind.entries.associateWith { kind ->
            try { VisionProviderChoice(store.selectionId(kind, checkpoint)) }
            catch (error: Exception) {
                if (error is CancellationException || error is InterruptedException) throw error
                checkpoint(); VisionProviderChoice(null, corrupt = true)
            }
        }
        VisionProviderInventory(entries, choices)
    }
    suspend fun import(uri: Uri): com.autoscript.runtime.vision.components.VerifiedVisionComponent {
        val guard = mutationGuard()
        return importOfflineComponent(app, uri) { open, checkpoint -> AndroidVisionComponents.store(app).import(open) { checkpoint(); guard() } }
    }
    suspend fun select(kind: VisionComponentKind, id: String) = operation(true) { checkpoint -> AndroidVisionComponents.store(app).select(kind, id, checkpoint) }
    suspend fun clear(kind: VisionComponentKind) = operation(true) { checkpoint -> AndroidVisionComponents.store(app).clearSelection(kind, checkpoint) }
    suspend fun remove(id: String) = operation(true) { checkpoint -> AndroidVisionComponents.store(app).remove(id, checkpoint) }

    private fun mutationGuard(): () -> Unit {
        val frozen = VisionProviderMutationContext(runtimeState())
        return { check(frozen.matches(runtimeState()) && canMutate()) { "VISION_COMPONENT_RUNTIME_CHANGED" } }
    }
    private suspend fun <T> operation(mutation: Boolean, action: (() -> Unit) -> T): T = withContext(Dispatchers.IO) {
        val coroutine = currentCoroutineContext(); val start = System.nanoTime()
        val guard = if (mutation) mutationGuard() else null
        action {
            coroutine.ensureActive()
            check(System.nanoTime() - start < 120_000_000_000L) { "VISION_COMPONENT_OPERATION_TIMEOUT" }
            guard?.invoke()
        }
    }
}
