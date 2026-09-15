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

data class ProjectDesign(
    val width: Int = 720,
    val height: Int = 1280,
    val scaleMode: String = "letterbox",
    val orientationPolicy: String = "follow",
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
    val runtimeApi: String = "1.5",
    val projectId: String,
    val name: String,
    val sourceMode: ProjectSourceMode,
    val entryPoint: String? = null,
    val entryFlowId: String? = null,
    val flows: List<ProjectFlow> = emptyList(),
    val resources: List<JsonObject> = emptyList(),
    val capabilities: List<String> = listOf("core.task"),
    val design: ProjectDesign = ProjectDesign(),
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
    val flowSources: Map<String, String>,
)

class ProjectStoreException(message: String, cause: Throwable? = null) :
    IllegalStateException(message, cause)

class ProjectWriteConflictException : IllegalStateException("main.lua 已被其他写入修改，请重新打开项目")

class FlowWriteConflictException(flowId: String) :
    IllegalStateException("Flow $flowId 已被其他写入修改，请重新打开项目")

class ProjectManifestConflictException :
    IllegalStateException("项目清单已被其他写入修改，请重新打开项目")
