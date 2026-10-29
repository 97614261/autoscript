package com.autoscript.runtime.service

internal fun inputPointPanelPosition(left: Int, top: Int, width: Int, height: Int,
    screenWidth: Int, screenHeight: Int): Pair<Int, Int> =
    left.coerceIn(0, (screenWidth.toLong() - width.coerceAtLeast(0)).coerceAtLeast(0).toInt()) to
        top.coerceIn(0, (screenHeight.toLong() - height.coerceAtLeast(0)).coerceAtLeast(0).toInt())

internal fun inputPointPanelBodyHeight(screenHeight: Int, margin: Int, fixedHeight: Int, desiredHeight: Int): Int =
    minOf(desiredHeight.coerceAtLeast(0).toLong(),
        (screenHeight.toLong() - margin.coerceAtLeast(0) - fixedHeight.coerceAtLeast(0)).coerceAtLeast(0)).toInt()
