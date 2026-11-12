package com.autoscript.studio

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** The picker temporarily covers a form; returning a selection keeps that form's draft. */
internal class ImageRecognitionCaptureState {
    var active by mutableStateOf(false)
    var polygonToEdit by mutableStateOf<String?>(null)
    var selection by mutableStateOf<ImageToolCodeGen.VisualSelection?>(null)
    var templatePath by mutableStateOf<String?>(null)

    fun begin(polygon: String? = null) {
        selection = null
        templatePath = null
        polygonToEdit = polygon
        active = true
    }
}
