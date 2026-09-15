package com.autoscript.studio

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.autoscript.core.designsystem.AutoScriptDimens
import com.autoscript.project.store.ProjectSnapshot

@Composable
internal fun ProjectFilesScreen(
    snapshot: ProjectSnapshot,
    busy: Boolean,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
    onOpenFile: (StudioProjectFile) -> Unit,
    onRun: () -> Unit,
    onOpenUiDesigner: () -> Unit,
    onOpenImageTools: () -> Unit,
    onOpenPackager: () -> Unit,
    onOpenRecorder: () -> Unit,
    onSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    BackHandler(enabled = !busy, onBack = onBack)
    val files = projectFileCatalog(snapshot)
    var query by remember(snapshot.manifest.projectId) { mutableStateOf("") }
    val visibleFiles = remember(files, query) {
        val needle = query.trim()
        if (needle.isEmpty()) files
        else files.filter { file ->
            file.path.contains(needle, ignoreCase = true) ||
                file.kind.label.contains(needle, ignoreCase = true)
        }
    }
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            horizontal = AutoScriptDimens.PageHorizontal,
            vertical = AutoScriptDimens.PageVertical,
        ),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = onBack, enabled = !busy) { Text("‹ 返回") }
                Column(Modifier.weight(1f)) {
                    Text(
                        snapshot.manifest.name,
                        style = MaterialTheme.typography.titleLarge,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        "本地文件 · ${files.size} 项",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                TextButton(onClick = onRefresh, enabled = !busy) { Text("刷新") }
            }
        }
        item {
            OutlinedButton(
                onClick = onOpenRecorder,
                enabled = !busy,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("动作录制") }
        }
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = onOpenUiDesigner,
                    enabled = !busy,
                    modifier = Modifier.weight(1f),
                ) { Text("界面") }
                OutlinedButton(
                    onClick = onOpenImageTools,
                    enabled = !busy,
                    modifier = Modifier.weight(1f),
                ) { Text("图片") }
                OutlinedButton(
                    onClick = onOpenPackager,
                    enabled = !busy,
                    modifier = Modifier.weight(1f),
                ) { Text("打包") }
            }
        }
        item {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(10.dp),
                color = MaterialTheme.colorScheme.surfaceVariant,
            ) {
                Text(
                    "/${snapshot.manifest.projectId}",
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Button(
                    onClick = {
                        files.firstOrNull(StudioProjectFile::opensEditor)?.let(onOpenFile)
                    },
                    enabled = !busy && files.any(StudioProjectFile::opensEditor),
                    modifier = Modifier.weight(1f),
                ) { Text("编辑入口") }
                OutlinedButton(onClick = onRun, enabled = !busy, modifier = Modifier.weight(1f)) {
                    Text("运行")
                }
                OutlinedButton(onClick = onSettings, enabled = !busy, modifier = Modifier.weight(1f)) {
                    Text("设置")
                }
            }
        }
        item {
            Text("项目文件", style = MaterialTheme.typography.headlineSmall)
        }
        item {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("搜索文件") },
                singleLine = true,
            )
        }
        if (visibleFiles.isEmpty()) {
            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Text("没有匹配的项目文件", modifier = Modifier.padding(16.dp))
                }
            }
        }
        items(visibleFiles, key = StudioProjectFile::path) { file ->
            ProjectFileRow(
                file = file,
                enabled = !busy,
                onClick = { onOpenFile(file) },
            )
        }
        item {
            Text(
                "这里只显示项目清单声明的源码和资源；缓存、备份与生成物不会作为可编辑文件暴露。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 4.dp),
            )
        }
    }
}

@Composable
private fun ProjectFileRow(
    file: StudioProjectFile,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = onClick),
        shape = RoundedCornerShape(AutoScriptDimens.ControlRadius),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Surface(
                modifier = Modifier.size(36.dp),
                shape = CircleShape,
                color = MaterialTheme.colorScheme.primaryContainer,
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text(
                        file.kind.symbol,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                Text(
                    file.path.substringAfterLast('/'),
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    "${file.kind.label} · ${formatFileSize(file.sizeBytes)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if ('/' in file.path) {
                    Text(
                        file.path.substringBeforeLast('/'),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Text(
                if (file.opensEditor) "编辑 ›" else "查看 ›",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    }
}

private fun formatFileSize(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "${bytes / 1024} KiB"
    else -> "${"%.1f".format(bytes.toDouble() / (1024 * 1024))} MiB"
}
