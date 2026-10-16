package com.autoscript.studio

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable

@Stable
internal class HomeSpaceState(initialTheme: HomeSpaceTheme = HomeSpaceTheme.AURORA) {
    var theme by mutableStateOf(initialTheme)
    companion object {
        val Saver = Saver<HomeSpaceState, String>(save = { it.theme.name }, restore = { saved ->
            HomeSpaceState(HomeSpaceTheme.entries.firstOrNull { it.name == saved } ?: HomeSpaceTheme.AURORA)
        })
    }
}

@Composable
internal fun rememberHomeSpaceState(): HomeSpaceState = rememberSaveable(saver = HomeSpaceState.Saver) { HomeSpaceState() }
