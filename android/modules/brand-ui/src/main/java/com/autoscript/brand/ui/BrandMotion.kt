package com.autoscript.brand.ui

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

enum class BrandState { IDLE, EXECUTING, RUNNING, SUCCESS, PAUSED, FAILED }
enum class BrandSurface { TRANSPARENT, LIGHT, DARK, FLOATING }

/** Visual-only state; never infers script success from a generic STOPPED notification. */
object BrandMotion {
    fun durationMs(state: BrandState): Long = when (state) {
        // Idle breathes slowly; the start state uses a shorter ignition loop so
        // a slow Root handshake never leaves the ball visually frozen.
        BrandState.IDLE -> 2_800L
        BrandState.EXECUTING -> 1_200L
        BrandState.RUNNING -> 2_200L
        BrandState.SUCCESS -> 1_400L
        BrandState.PAUSED, BrandState.FAILED -> 0L
    }
    fun repeats(state: BrandState) = state in setOf(BrandState.IDLE, BrandState.EXECUTING, BrandState.RUNNING)
    fun pulse(progress: Float): Float = ((1.0 - cos(progress.coerceIn(0f, 1f) * 2.0 * PI)) * .5).toFloat()
    fun sweep(progress: Float): Float = -.35f + 1.7f * progress.coerceIn(0f, 1f)
    fun ringProgress(progress: Float): Float = progress.coerceIn(0f, 1f)
    /** Smooth variable speed with the same position and velocity at the loop seam. */
    fun orbitDegrees(progress: Float): Float {
        val p = progress.coerceIn(0f, 1f)
        return p * 360f + 22f * sin(p * 2.0 * PI).toFloat() - 90f
    }
    fun counterOrbitDegrees(progress: Float): Float {
        val p = progress.coerceIn(0f, 1f)
        return -p * 360f + 14f * sin(p * 4.0 * PI).toFloat() + 75f
    }
    fun tailSweep(progress: Float): Float = 46f + 24f * pulse(progress)
    fun ignition(progress: Float): Float = progress.coerceIn(0f, 1f)
    // A linear RGB lift changes only color, never the source alpha/contour.
    const val FLOATING_COLOR_SCALE = .24f
    const val FLOATING_RED_BIAS = 193.8f
    const val FLOATING_GREEN_BIAS = 185.44f
    const val FLOATING_BLUE_BIAS = 193.8f
}
