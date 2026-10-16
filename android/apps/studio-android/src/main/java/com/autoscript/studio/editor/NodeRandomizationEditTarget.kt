package com.autoscript.studio

internal data class NodeRandomizationEditTarget(val nodeId:String,val source:String,val kind:String,
    val initial:com.autoscript.project.store.ProjectRandomization,val mode:String)
