package com.autoscript.studio

import com.autoscript.project.store.ProjectSourceMode
import com.autoscript.project.store.ProjectSummary

/** UI entry policy only; historical persistence and Runner formats remain compatible. */
internal object StudioProjectPolicy {
    val creationMode = ProjectSourceMode.VISUAL
    fun canOperate(mode: ProjectSourceMode): Boolean = mode == ProjectSourceMode.VISUAL
    fun active(projects: List<ProjectSummary>) = projects.filter { canOperate(it.sourceMode) }
    fun historical(projects: List<ProjectSummary>) = projects.filterNot { canOperate(it.sourceMode) }
    const val historicalMessage = "旧源码项目已保留，请从旧源码项目列表导出备份；不支持自动转换任意 Lua。"
}
