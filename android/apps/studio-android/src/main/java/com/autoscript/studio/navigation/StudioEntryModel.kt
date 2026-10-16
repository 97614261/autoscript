package com.autoscript.studio

import com.autoscript.core.model.RuntimeConnectionPhase
import com.autoscript.core.model.RuntimeConnectionState
import com.autoscript.core.model.RuntimeEngineState
import com.autoscript.core.model.RuntimeRootState
import com.autoscript.project.store.ProjectSummary

internal enum class StudioEntry(val title: String, val detail: String) {
    PROJECTS("全部项目", "编写、启动与管理"),
    CREATE("新建项目", "从空白积木开始"),
    IMPORT("导入项目", ".asproject 本地备份"),
    BACKUPS("备份与恢复", "项目槽位和导入导出"),
    PERMISSIONS("软件权限", "悬浮授权与实际能力"),
    ENVIRONMENT("运行环境", "Runner、Root 与诊断"),
    DOCS("函数文档", "本地真实接口与积木"),
    GUIDE("使用指南", "编写、启动与截图流程"),
    ABOUT("关于智构", "版本、隐私与运行边界"),
}

internal data class StudioProfileSection(val title: String, val entries: List<StudioEntry>)

internal val studioProfileSections = listOf(
    StudioProfileSection("设备与运行", listOf(StudioEntry.PERMISSIONS, StudioEntry.ENVIRONMENT)),
    StudioProfileSection("项目与数据", listOf(StudioEntry.BACKUPS)),
    StudioProfileSection("帮助与应用", listOf(StudioEntry.DOCS, StudioEntry.GUIDE, StudioEntry.ABOUT)),
)
internal val profileStudioEntries = studioProfileSections.flatMap { it.entries }

/** Display hint only, never an identity for lookup, copying, persistence or export. */
internal fun compactProjectId(projectId: String): String {
    val label = projectId.removePrefix("project-")
    return if (label.length <= 10) label else "${label.take(4)}…${label.takeLast(4)}"
}

internal enum class WorkspaceEntryAction { CREATE, IMPORT, BACKUPS, EDIT }

/** In-memory navigation intent, not a filesystem path or persisted project mutation. */
internal data class WorkspaceEntryRequest(val token: Long, val action: WorkspaceEntryAction, val projectId: String? = null)

internal class WorkspaceEntryGate {
    private var consumedToken = 0L
    fun claim(request: WorkspaceEntryRequest): Boolean {
        if (request.token <= consumedToken) return false
        consumedToken = request.token
        return true
    }
}

internal fun recentStudioProjects(projects: List<ProjectSummary>): List<ProjectSummary> =
    StudioProjectPolicy.active(projects).sortedWith(compareByDescending<ProjectSummary> { it.lastOpenedAt ?: it.updatedAt }
        .thenByDescending { it.updatedAt }.thenBy { it.projectId }).take(3)

internal fun searchStudioProjects(projects: List<ProjectSummary>, query: String): List<ProjectSummary> =
    StudioProjectPolicy.active(projects).filter { query.isBlank() || it.name.contains(query.trim(), ignoreCase = true) }

internal enum class ProjectListOrder(val label: String) { UPDATED("最近修改"), NAME("项目名称") }

/** Presentation-only ordering; never opens a project or changes its access timestamps. */
internal fun orderedStudioProjects(projects: List<ProjectSummary>, query: String, order: ProjectListOrder): List<ProjectSummary> {
    val matching = searchStudioProjects(projects, query)
    return when (order) {
        ProjectListOrder.UPDATED -> matching.sortedWith(compareByDescending<ProjectSummary> { it.updatedAt }.thenBy { it.projectId })
        ProjectListOrder.NAME -> matching.sortedWith(compareBy<ProjectSummary> { it.name.lowercase(java.util.Locale.ROOT) }.thenBy { it.projectId })
    }
}

internal data class StudioRuntimeLabels(val runner: String, val root: String, val execution: String)

internal fun studioRuntimeLabels(state: RuntimeConnectionState): StudioRuntimeLabels = StudioRuntimeLabels(
    runner = runtimeConnectionLabel(state.phase),
    root = if (state.phase != RuntimeConnectionPhase.CONNECTED && state.rootState == RuntimeRootState.READY)
        "待重新确认" else runtimeRootLabel(state.rootState),
    execution = if (state.phase != RuntimeConnectionPhase.CONNECTED && state.engineState in
        setOf(RuntimeEngineState.RUNNING, RuntimeEngineState.PAUSED, RuntimeEngineState.STOPPING))
        "状态未确认" else runtimeExecutionLabel(state.engineState),
)
