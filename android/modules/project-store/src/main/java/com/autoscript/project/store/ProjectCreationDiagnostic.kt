package com.autoscript.project.store

import java.util.IdentityHashMap

enum class ProjectCreationStage(val label: String) {
    MANIFEST("生成清单"), WRITE_MANIFEST("保存清单"), WRITE_ENTRY("保存插件"),
    WRITE_STATE("保存状态"), COMMIT("提交目录"), REOPEN("重新读取项目"),
}

data class ProjectCreationDiagnostic(
    val stage: ProjectCreationStage,
    val failureType: String,
    val origin: String?,
    val missingSymbol: String?,
) {
    val summary: String get() = listOfNotNull(
        "CREATE/${stage.name} · ${stage.label} · $failureType", origin, missingSymbol,
    ).joinToString("\n")
}

internal fun projectCreationDiagnostic(stage: ProjectCreationStage, failure: Throwable): ProjectCreationDiagnostic {
    val visited = IdentityHashMap<Throwable, Boolean>()
    var root = failure
    repeat(8) {
        visited[root] = true
        val cause = root.cause
        if (cause != null && !visited.containsKey(cause)) root = cause
    }
    val type = root.javaClass.simpleName.takeIf { it.matches(Regex("[A-Za-z0-9_$]{1,64}")) } ?: "Throwable"
    val frame = root.stackTrace.take(32).firstOrNull {
        (it.className.startsWith("com.autoscript.project.store.") || it.className.startsWith("com.google.gson.") ||
            it.className.startsWith("java.io.")) && it.className.matches(Regex("[A-Za-z0-9_.$]{1,180}")) &&
            it.methodName.matches(Regex("[A-Za-z0-9_$<>]{1,96}"))
    }
    val origin = frame?.let { "${it.className}.${it.methodName}" + if (it.lineNumber > 0) ":${it.lineNumber}" else "" }
    // Extract only code symbols, never an arbitrary exception message or path.
    val message = root.message.orEmpty().take(2048)
    val symbol = when (root) {
        is NoSuchMethodError -> Regex("\\bmethod\\s+([A-Za-z0-9_$<>]{1,96})\\(").find(message)?.groupValues?.get(1)
        is NoSuchFieldError -> Regex("\\bfield\\s+([A-Za-z0-9_$]{1,96})\\b").find(message)?.groupValues?.get(1)
        is NoClassDefFoundError -> Regex("L[A-Za-z0-9_$/]{1,180};").find(message)?.value
        else -> null
    }
    return ProjectCreationDiagnostic(stage, type, origin, symbol?.let { "symbol=$it" })
}
