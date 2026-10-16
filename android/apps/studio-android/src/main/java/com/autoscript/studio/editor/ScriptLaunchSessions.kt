package com.autoscript.studio

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

internal enum class ScriptLaunchPhase { LOADING, CONFIGURING, STARTING, RUNNING }
internal data class ScriptLaunchStatus(val projectId: String, val phase: ScriptLaunchPhase)
internal object ScriptLaunchSessions {
    private val mutableStatus = MutableStateFlow<ScriptLaunchStatus?>(null)
    val status = mutableStatus.asStateFlow()
    fun publish(value: ScriptLaunchStatus?) { mutableStatus.value = value }
}

internal fun canOpenScriptLaunch(editorId: String?, launchId: String?, requested: String): Boolean =
    editorId == null && (launchId == null || launchId == requested)
