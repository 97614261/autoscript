package com.autoscript.runtime.service

/**
 * Intentionally no release API: native code may outlive an SDK backend session.
 * A pinned value owns both the loader and its snapshot lease until process exit.
 */
internal class VisionProviderProcessPins<T : Any> {
    private data class Entry<T>(val identity: String, val value: T)
    private val entries = mutableMapOf<String, Entry<T>>()

    @Synchronized fun existing(kind: String, identity: String): T? {
        require(kind in setOf("opencv", "onnx"))
        val entry = entries[kind] ?: return null
        check(entry.identity == identity) { "VISION_COMPONENT_PROCESS_RESTART_REQUIRED" }
        return entry.value
    }

    @Synchronized fun pin(kind: String, identity: String, create: () -> T): T {
        require(kind in setOf("opencv", "onnx"))
        entries[kind]?.let { existing ->
            check(existing.identity == identity) { "VISION_COMPONENT_PROCESS_RESTART_REQUIRED" }
            return existing.value
        }
        return create().also { entries[kind] = Entry(identity, it) }
    }
}
