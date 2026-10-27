package com.autoscript.studio

/**
 * Native windows of the same overlay type are ordered by first attachment, not Compose zIndex.
 * Keep already attached backdrops, but defer new ordinary windows while a prompt owns the front.
 * Nothing is dismissed/recreated to change order, so parameter text and remember state survive.
 */
internal class StudioDialogOrder<T : Any> {
    private class Entry<T>(val window: T, val prompt: Boolean, var presented: Boolean = false)
    private val entries = mutableListOf<Entry<T>>()
    val windows: List<T> get() = entries.map { it.window }
    val hasPrompt: Boolean get() = entries.any { it.prompt }
    val size: Int get() = entries.size

    fun contains(window: T): Boolean = entries.any { it.window === window }
    fun register(window: T, prompt: Boolean = false) {
        if (!contains(window)) entries += Entry(window, prompt)
    }
    fun remove(window: T) { entries.removeAll { it.window === window } }
    fun clear() { entries.clear() }
    fun canPresent(window: T): Boolean {
        val entry = entries.firstOrNull { it.window === window } ?: return false
        return entry.presented || entry.prompt || !hasPrompt
    }
    fun presented(window: T) { entries.firstOrNull { it.window === window }?.presented = true }
}
