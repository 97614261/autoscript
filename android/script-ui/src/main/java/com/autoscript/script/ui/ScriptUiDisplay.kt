package com.autoscript.script.ui

import android.content.Context
import android.os.Build
import android.util.DisplayMetrics
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager

/** Read-only screen bounds. Never derive screen size from the small window's own measured size. */
object ScriptUiDisplay {
    fun bounds(context: Context, view: View? = null, includeKeyboard: Boolean = true): UiScreenBounds {
        val manager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val margin = (8 * context.resources.displayMetrics.density).toInt()
        if (Build.VERSION.SDK_INT >= 30) {
            val metrics = manager.currentWindowMetrics
            val rect = metrics.bounds
            val types = WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout()
            val bars = metrics.windowInsets.getInsetsIgnoringVisibility(types)
            val ime = if (includeKeyboard) view?.rootWindowInsets?.getInsets(WindowInsets.Type.ime())?.bottom ?: 0 else 0
            val bottom = maxOf(bars.bottom, ime)
            return UiScreenBounds(rect.left + bars.left + margin, rect.top + bars.top + margin,
                (rect.width() - bars.left - bars.right - margin * 2).coerceAtLeast(1),
                (rect.height() - bars.top - bottom - margin * 2).coerceAtLeast(1))
        }
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION") manager.defaultDisplay.getRealMetrics(metrics)
        fun systemDimension(name: String): Int {
            val id = context.resources.getIdentifier(name, "dimen", "android")
            return if (id == 0) 0 else context.resources.getDimensionPixelSize(id)
        }
        val status = systemDimension("status_bar_height")
        val nav = systemDimension("navigation_bar_height")
        val landscape = metrics.widthPixels > metrics.heightPixels
        @Suppress("DEPRECATION") val insets = view?.rootWindowInsets
        @Suppress("DEPRECATION") val bottom = maxOf(if (landscape) 0 else nav, if (includeKeyboard) insets?.systemWindowInsetBottom ?: 0 else 0)
        @Suppress("DEPRECATION") val left = insets?.systemWindowInsetLeft ?: 0
        @Suppress("DEPRECATION") val right = maxOf(if (landscape) nav else 0, insets?.systemWindowInsetRight ?: 0)
        return UiScreenBounds(left + margin, status + margin,
            (metrics.widthPixels - left - right - margin * 2).coerceAtLeast(1),
            (metrics.heightPixels - status - bottom - margin * 2).coerceAtLeast(1))
    }
}
