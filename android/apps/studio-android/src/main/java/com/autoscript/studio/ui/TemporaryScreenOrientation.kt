package com.autoscript.studio

import android.content.pm.ActivityInfo

/** Unspecified can inherit the last forced orientation; USER respects the device rotation setting. */
internal fun restoredScreenOrientation(previous: Int): Int =
    if (previous == ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED) ActivityInfo.SCREEN_ORIENTATION_USER else previous

/** Release before navigation, with disposal as a fallback; never overwrite a newer owner's request. */
internal class TemporaryScreenOrientation(
    private val read: () -> Int,
    private val write: (Int) -> Unit,
    private val requested: Int,
) {
    private var previous: Int? = null

    fun acquire() {
        if (previous != null) return
        previous = read()
        write(requested)
    }

    fun release() {
        val original = previous ?: return
        previous = null
        if (read() == requested) write(restoredScreenOrientation(original))
    }
}
