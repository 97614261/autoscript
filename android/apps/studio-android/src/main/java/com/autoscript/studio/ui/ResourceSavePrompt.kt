package com.autoscript.studio

import androidx.compose.material3.Text
import androidx.compose.runtime.*
import com.autoscript.project.store.ResourceImportConflictException
import com.autoscript.project.store.ResourceImportMode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred

internal enum class SaveConflictChoice { REPLACE, NUMBERED }

internal fun numberedSaveName(requested: String, existing: Collection<String>): String {
    if (requested !in existing) return requested
    val stem = requested.substring(0, requested.offsetByCodePoints(0, minOf(24, requested.codePointCount(0, requested.length))))
    var suffix = 2
    while ("$stem-$suffix" in existing) suffix++
    return "$stem-$suffix"
}

internal class ResourceSavePrompt {
    internal class Request(val name: String, val detail: String) {
        val result = CompletableDeferred<SaveConflictChoice?>()
    }

    internal var pending by mutableStateOf<Request?>(null)
        private set

    suspend fun choose(name: String, detail: String): SaveConflictChoice {
        check(pending == null) { "请先完成当前保存确认" }
        val request = Request(name, detail)
        pending = request
        try {
            return request.result.await() ?: throw CancellationException("已取消保存")
        } finally {
            if (pending === request) pending = null
        }
    }

    internal fun finish(request: Request, choice: SaveConflictChoice?) {
        if (pending === request) request.result.complete(choice)
    }

    internal fun dispose() { pending?.result?.cancel(); pending = null }

    suspend fun <T> run(operation: suspend (ResourceImportMode) -> T): T {
        val conflict = try { return operation(ResourceImportMode.Ask) }
        catch (failure: ResourceImportConflictException) { failure.replacement }
        val choice = choose(conflict.path, "覆盖将替换原文件内容，已有积木仍使用这个路径；编号另存会保留原文件。")
        return operation(if (choice == SaveConflictChoice.REPLACE) ResourceImportMode.Replace(conflict) else ResourceImportMode.Numbered)
    }
}

@Composable
internal fun rememberResourceSavePrompt(): ResourceSavePrompt {
    val prompt = remember { ResourceSavePrompt() }
    DisposableEffect(prompt) { onDispose { prompt.dispose() } }
    prompt.pending?.let { request ->
        StudioConfirmDialog(onDismissRequest = { prompt.finish(request, null) }, compact = true,
            title = { Text("同名内容已存在") }, text = { Text("${request.name}\n${request.detail}") },
            actions = listOf(
                StudioConfirmAction("取消", { prompt.finish(request, null) }),
                StudioConfirmAction("编号另存", { prompt.finish(request, SaveConflictChoice.NUMBERED) }),
                StudioConfirmAction("覆盖保存", { prompt.finish(request, SaveConflictChoice.REPLACE) }, destructive = true),
            ))
    }
    return prompt
}
