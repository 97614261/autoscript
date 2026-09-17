package com.autoscript.studio

internal enum class StudioDestination(
    val label: String,
    val symbol: String,
) {
    HOME("主页", "⌂"),
    WORKSPACE("工作台", "▦"),
    PROFILE("我的", "●"),
}

internal data class StudioNavigationState(
    val destination: StudioDestination = StudioDestination.WORKSPACE,
)

internal fun StudioNavigationState.navigateTo(
    destination: StudioDestination,
): StudioNavigationState =
    if (this.destination == destination) this else copy(destination = destination)
