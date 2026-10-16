package com.autoscript.script.ui

import android.graphics.Color
import android.graphics.drawable.ClipDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.graphics.drawable.StateListDrawable
import android.view.Gravity
import android.widget.ProgressBar
import android.widget.SeekBar
import android.widget.Switch

private val compactAccent = Color.rgb(56, 106, 255)
private val compactLine = Color.rgb(226, 231, 240)

private fun roundedControl(width: Int, height: Int, color: Int) = GradientDrawable().apply {
    setSize(width, height)
    cornerRadius = height / 2f
    setColor(color)
}

internal fun Switch.useCompactSwitch(scale: Float) {
    val width = ScriptUiControlMetrics.switchWidth(scale)
    val height = ScriptUiControlMetrics.pixels(ScriptUiControlMetrics.SWITCH_HEIGHT, scale)
    val thumb = ScriptUiControlMetrics.pixels(ScriptUiControlMetrics.SWITCH_THUMB, scale)
    showText = false
    splitTrack = false
    switchMinWidth = width
    switchPadding = ScriptUiControlMetrics.pixels(ScriptUiControlMetrics.INSET, scale)
    trackTintList = null
    thumbTintList = null
    trackDrawable = StateListDrawable().apply {
        addState(intArrayOf(android.R.attr.state_checked), roundedControl(width, height, compactAccent))
        addState(intArrayOf(), roundedControl(width, height, compactLine))
    }
    thumbDrawable = roundedControl(thumb, thumb, Color.WHITE)
}

internal fun ProgressBar.useCompactTrack(scale: Float) {
    val height = ScriptUiControlMetrics.pixels(ScriptUiControlMetrics.TRACK_HEIGHT, scale)
    progressTintList = null
    progressBackgroundTintList = null
    secondaryProgressTintList = null
    val layers = LayerDrawable(arrayOf(
        roundedControl(1, height, compactLine),
        ClipDrawable(roundedControl(1, height, compactLine), Gravity.START, ClipDrawable.HORIZONTAL),
        ClipDrawable(roundedControl(1, height, compactAccent), Gravity.START, ClipDrawable.HORIZONTAL),
    ))
    val ids = intArrayOf(android.R.id.background, android.R.id.secondaryProgress, android.R.id.progress)
    ids.forEachIndexed { index, id ->
        layers.setId(index, id)
        layers.setLayerHeight(index, height)
        layers.setLayerGravity(index, Gravity.FILL_HORIZONTAL or Gravity.CENTER_VERTICAL)
    }
    progressDrawable = layers
    minimumWidth = 0
    minimumHeight = 0
    minWidth = 0
    minHeight = 0
    val inset = ScriptUiControlMetrics.pixels(ScriptUiControlMetrics.INSET, scale)
    setPadding(inset, 0, inset, 0)
    if (this is SeekBar) {
        thumbTintList = null
        val size = ScriptUiControlMetrics.pixels(ScriptUiControlMetrics.SLIDER_THUMB, scale)
        thumb = roundedControl(size, size, compactAccent)
        thumbOffset = size / 2
        splitTrack = false
        // Reserve half a thumb so the two extreme positions remain inside the saved rectangle.
        setPadding(maxOf(inset, (size + 1) / 2), 0, maxOf(inset, (size + 1) / 2), 0)
    }
}
