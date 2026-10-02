package com.autoscript.project.store

import java.io.File
import com.google.gson.JsonObject
import com.google.gson.JsonElement
import com.google.gson.annotations.SerializedName

const val CURRENT_PROJECT_FORMAT_VERSION = 2
const val LEGACY_PROJECT_FORMAT_VERSION = 1
const val MAX_LUA_SOURCE_BYTES = 16 * 1024 * 1024
const val MAX_FLOW_SOURCE_BYTES = 64 * 1024 * 1024
const val MAX_IMAGE_RESOURCE_BYTES = 32L * 1024 * 1024
const val MAX_GLYPH_DICTIONARY_BYTES = 8L * 1024 * 1024

enum class ProjectResourceKind {
    IMAGE,
    GLYPH_DICTIONARY,
}

enum class ProjectSourceMode {
    @SerializedName("lua")
    LUA,

    @SerializedName("visual")
    VISUAL,
}

enum class ProjectVariableScope {
    @SerializedName("global") GLOBAL,
    @SerializedName("flow") FLOW,
}

enum class ProjectVariableType {
    @SerializedName("integer") INTEGER,
    @SerializedName("number") NUMBER,
    @SerializedName("string") STRING,
    @SerializedName("image") IMAGE,
}

/** Declared variable metadata; Flow nodes only reference this stable declaration by name. */
data class ProjectVariable(
    val name: String,
    val scope: ProjectVariableScope,
    val flowId: String? = null,
    val type: ProjectVariableType,
)

data class ProjectDesign(
    val width: Int = 720,
    val height: Int = 1280,
    val scaleMode: String = "letterbox",
    val orientationPolicy: String = "follow",
)

/** Runtime-affecting debug controls. Values are per project and travel with a backup/export. */
data class ProjectDebugSettings(
    val runDelayMs: Int = 0,
    val showRunPrompts: Boolean = true,
    val runPromptFilters: RunPromptFilters = RunPromptFilters(),
    val popupStyle: PopupStyle = PopupStyle(),
)

/** Project-level appearance for short-lived script popup tips. */
data class PopupStyle(
    val widthPx: Int = 520,
    val heightPx: Int = 144,
    val xPx: Int = -1,
    val yPx: Int = -1,
    val backgroundColor: String = "#B3000000",
    val textColor: String = "#FFFFFFFF",
    val fontPx: Int = 32,
    val cornerPx: Int = 16,
    val durationMs: Int = 3_000,
    val textAlign: String = "center",
)

/** Automatic visual Flow events sent to the user-facing floating prompt window. */
data class RunPromptFilters(
    val loops: Boolean = false,
    val jumps: Boolean = false,
    val flowStart: Boolean = false,
    val flowReturn: Boolean = false,
    val imageSearch: Boolean = false,
    val variables: Boolean = false,
    val variableScope: String = "all",
    val variableType: String = "all",
    val variableName: String = "all",
)

data class ProjectFlow(
    val flowId: String,
    val path: String,
    val rootBlockId: String,
    val params: List<JsonObject> = emptyList(),
    val returns: JsonElement? = null,
)

data class ProjectManifestDocument(
    val formatVersion: Int = CURRENT_PROJECT_FORMAT_VERSION,
    val flowSchemaVersion: Int = 1,
    val runtimeApi: String = "1.7",
    val projectId: String,
    val name: String,
    val sourceMode: ProjectSourceMode,
    val entryPoint: String? = null,
    /** Authoritative Lua source files. `entryPoint` is always one member for Lua projects. */
    val luaFiles: List<String> = emptyList(),
    /** Persisted Lua folders, including empty folders that a ZIP would otherwise drop. */
    val luaDirectories: List<String> = emptyList(),
    val entryFlowId: String? = null,
    val flows: List<ProjectFlow> = emptyList(),
    val variables: List<ProjectVariable> = emptyList(),
    val resources: List<JsonObject> = emptyList(),
    val capabilities: List<String> = listOf("core.task"),
    val design: ProjectDesign = ProjectDesign(),
    val debugSettings: ProjectDebugSettings = ProjectDebugSettings(),
    val runnerUi: JsonObject? = null,
    val ownerId: String? = null,
    val cloudId: String? = null,
    val syncState: String? = null,
    val signature: JsonElement? = null,
    val licensePolicy: JsonElement? = null,
)

data class ProjectSummary(
    val projectId: String,
    val name: String,
    val sourceMode: ProjectSourceMode,
    val designWidth: Int,
    val designHeight: Int,
    val createdAt: Long,
    val updatedAt: Long,
    val lastOpenedAt: Long?,
)

data class ProjectSnapshot(
    val directory: File,
    val manifest: ProjectManifestDocument,
    val luaSource: String?,
    /** Every declared Lua source, keyed by its project-relative canonical path. */
    val luaSources: Map<String, String> = emptyMap(),
    val luaDirectories: List<String> = emptyList(),
    val flowSources: Map<String, String>,
)

/** 本地备份槽位（参考新版每个项目 3 个槽位）；`createdAt == null` 表示空槽。 */
data class BackupSlot(
    val index: Int,
    val createdAt: Long?,
    val bytes: Long?,
    val remark: String?,
    val projectName: String?,
) {
    val occupied: Boolean get() = createdAt != null
}

/** 备份管理页的一行：一个有槽位备份的项目，本地项目可能已被删除。 */
data class BackupProjectSummary(
    val projectId: String,
    val projectName: String,
    val slots: List<BackupSlot>,
    val localProjectExists: Boolean,
    val latestBackupAt: Long?,
    val totalBytes: Long,
)

/**
 * 源文件管理里的虚拟分组。
 *
 * 参考新版易编精灵：分组只是编辑器视图（它存 `分组配置.json`），Flow 文件仍在同一目录，
 * 所以分组不进入 `project.json`，也不参与备份；一个 Flow 最多属于一个分组。
 */
data class SourceGroup(
    val name: String,
    val flowIds: List<String> = emptyList(),
)

class ProjectStoreException(message: String, cause: Throwable? = null) :
    IllegalStateException(message, cause)

class ProjectWriteConflictException : IllegalStateException("main.lua 已被其他写入修改，请重新打开项目")

class FlowWriteConflictException(flowId: String) :
    IllegalStateException("插件 $flowId 已被其他写入修改，请重新打开项目")

class ProjectManifestConflictException :
    IllegalStateException("项目清单已被其他写入修改，请重新打开项目")
