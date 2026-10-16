package com.autoscript.studio

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/** Brand-only playground; interactions never open projects or execute scripts. */
@Composable
internal fun StudioLaunchCanvas(modifier: Modifier = Modifier, spaceState: HomeSpaceState = rememberHomeSpaceState()) {
    StudioInteractiveSpace(modifier, spaceState)
}
