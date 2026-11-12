package com.autoscript.studio

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import com.autoscript.runtime.api.RuntimeSystemLog

/** Observe public operation feedback, never form input, credentials or model responses. */
@Composable
internal fun StudioSystemLogEffect(source: String, message: String?, failure: Boolean? = null, skipInitial: Boolean = false) {
    val files = LocalContext.current.applicationContext.filesDir
    val initial = remember(source) { if (skipInitial) message else null }
    LaunchedEffect(source, message, failure) {
        if (!message.isNullOrBlank() && message != initial) {
            if (failure == null) RuntimeSystemLog.notice(files, source, message)
            else RuntimeSystemLog.notice(files, source, message, failure)
        }
    }
}
