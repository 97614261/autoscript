package com.autoscript.project.store

/** Explicit legacy fixture for tests that mutate a sample node; production projects start empty. */
internal fun ProjectStore.createFixtureProject(name:String,mode:ProjectSourceMode,designWidth:Int=720,designHeight:Int=1280,
    initialCapabilities:Set<String> = setOf("core.task")):ProjectSnapshot {
    val created=createProject(name,mode,designWidth,designHeight,initialCapabilities)
    if(mode!=ProjectSourceMode.VISUAL) return created
    val flow=created.manifest.flows.single()
    val source="""{"flowSchemaVersion":1,"nodeId":"node-1","blockId":"${flow.rootBlockId}","parentId":null,"orderKey":"a0","kind":"task.noop","nodeVersion":1,"depth":0,"args":{}}"""+"\n"
    return saveFlow(created.manifest.projectId,flow.flowId,source,created.flowSources.getValue(flow.flowId))
}
