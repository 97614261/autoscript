package com.autoscript.studio

internal enum class StudioDestination(
    val label: String,
    val symbol: String,
) {
    HOME("首页", "⌂"),
    WORKSPACE("项目", "▦"),
    PROFILE("我的", "●"),
}

internal data class StudioNavigationState(
    val destination: StudioDestination = StudioDestination.HOME,
)

internal fun StudioNavigationState.navigateTo(
    destination: StudioDestination,
): StudioNavigationState =
    if (this.destination == destination) this else copy(destination = destination)
