package com.autoscript.studio

import android.app.Instrumentation
import com.autoscript.project.store.ProjectSourceMode
import com.autoscript.project.store.ProjectStore
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Destructive fault inputs are confined to one new cache sandbox, never the user's projects. */
internal object StudioDataSafetyAcceptanceFixture {
    fun run(test: Instrumentation, record: (String, () -> Unit) -> Unit) {
        val root = File(test.targetContext.cacheDir.canonicalFile, "qa-data-safety-${UUID.randomUUID()}")
        check(root.mkdir()) { "QA_DATA_SANDBOX_CREATE" }
        val projects = File(root, "projects")
        val store = ProjectStore(projects, idFactory = { "project-qa-${UUID.randomUUID()}" })
        try {
            var project = store.createProject("公开数据安全", ProjectSourceMode.VISUAL)
            val id = project.manifest.projectId
            val original = project.flowSources
            val output = ByteArrayOutputStream().also { store.exportProjectBackup(id, it) }.toByteArray()
            fun zip(vararg pairs: Pair<String, ByteArray>): ByteArray = ByteArrayOutputStream().also { bytes ->
                ZipOutputStream(bytes).use { archive -> pairs.forEach { (path, content) ->
                    archive.putNextEntry(ZipEntry(path)); archive.write(content); archive.closeEntry()
                } }
            }.toByteArray()
            fun reject(input: InputStream) {
                val before = projects.listFiles().orEmpty().map { it.name }.toSet()
                check(runCatching { store.importProjectBackup(input) }.isFailure) { "QA_BAD_BACKUP_ACCEPTED" }
                check(projects.listFiles().orEmpty().map { it.name }.toSet() == before) { "QA_IMPORT_LEFT_PARTIAL_DATA" }
                check(store.openProject(id).flowSources == original) { "QA_IMPORT_CHANGED_ORIGINAL" }
            }
            record("data-backup-invalid-json-missing-flow-and-undeclared-entry-atomic-rejection") {
                val manifest = File(project.directory, "project.json").readBytes()
                val flow = project.manifest.flows.single()
                reject(ByteArrayInputStream(zip("project.json" to "{".toByteArray())))
                reject(ByteArrayInputStream(zip("project.json" to manifest)))
                reject(ByteArrayInputStream(zip("project.json" to manifest,
                    flow.path to original.getValue(flow.flowId).toByteArray(), "private.txt" to byteArrayOf(1))))
            }
            record("data-backup-traversal-and-manifest-budget-rejection") {
                reject(ByteArrayInputStream(zip("../outside.txt" to byteArrayOf(1))))
                check(!File(root, "outside.txt").exists()) { "QA_BACKUP_PATH_ESCAPE" }
                // Small compressed payload, 1 MiB+1 expanded manifest: exercises the real per-entry limit.
                reject(ByteArrayInputStream(zip("project.json" to ByteArray(1_048_577) { 32 })))
            }
            record("data-backup-read-io-failure-cleans-only-owned-staging") {
                val broken = object : InputStream() {
                    var offset = 0
                    override fun read(): Int {
                        if (offset >= output.size / 2) throw IOException("public synthetic read failure")
                        return output[offset++].toInt() and 255
                    }
                    override fun read(buffer: ByteArray, start: Int, length: Int): Int {
                        if (length == 0) return 0
                        val count = minOf(length, (output.size / 2 - offset).coerceAtLeast(0))
                        if (count == 0) throw IOException("public synthetic read failure")
                        output.copyInto(buffer, start, offset, offset + count); offset += count
                        return count
                    }
                }
                reject(broken)
            }
            record("data-interrupted-multifile-transaction-restores-formal-baseline") {
                val flow = project.manifest.flows.single()
                val manifest = File(project.directory, "project.json")
                val baseline = manifest.readText()
                val before = JsonObject().apply { addProperty("project.json", baseline); addProperty(flow.path, original.getValue(flow.flowId)) }
                val journal = File(project.directory, ".studio/variable-transaction.json")
                check(journal.parentFile?.isDirectory == true || requireNotNull(journal.parentFile).mkdirs())
                journal.writeText(JsonObject().apply { addProperty("version", 1); add("before", before) }.toString())
                File(project.directory, flow.path).writeText("INTERRUPTED_UNCOMMITTED")
                project = store.openProject(id)
                check(project.flowSources == original && manifest.readText() == baseline && !journal.exists()) { "QA_TRANSACTION_RECOVERY_PARTIAL" }
            }
            record("data-save-io-failure-recovers-all-formal-files-on-reopen") {
                var multi = store.createProject("公开保存故障", ProjectSourceMode.VISUAL)
                multi = store.createFlow(multi.manifest.projectId, "qa-second", multi.manifest.flows.map { it.flowId }.toSet())
                val baseline = multi.flowSources
                val manifest = File(multi.directory, "project.json").readText()
                val path = multi.manifest.flows.last().path
                val blocked = File(multi.directory, "$path.tmp")
                check(blocked.mkdir()) { "QA_IO_FAULT_SETUP" }
                try {
                    check(runCatching { store.saveFlows(multi, baseline.mapValues { it.value + "\n" }) }.isFailure) { "QA_IO_FAULT_WAS_IGNORED" }
                } finally { check(blocked.delete()) { "QA_IO_FAULT_RELEASE" } }
                val restored = store.openProject(multi.manifest.projectId)
                check(restored.flowSources == baseline && File(multi.directory, "project.json").readText() == manifest &&
                    !File(multi.directory, ".studio/variable-transaction.json").exists()) { "QA_IO_ROLLBACK_PARTIAL" }
                store.deleteProject(multi.manifest.projectId)
            }
            record("data-uncommitted-flow-and-unproven-previous-not-promoted") {
                val flow = project.manifest.flows.single()
                val target = File(project.directory, flow.path)
                val temp = File(target.parentFile, "${target.name}.tmp")
                temp.writeText(original.getValue(flow.flowId))
                check(target.delete())
                check(runCatching { store.openProject(id) }.isFailure && !target.exists() && temp.exists()) { "QA_UNCOMMITTED_FLOW_PROMOTED" }
                // A previous file without a proven commit is not a valid recovery record.
                val previous = File(target.parentFile, "${target.name}.previous")
                previous.writeText(original.getValue(flow.flowId))
                check(runCatching { store.openProject(id) }.isFailure) { "QA_UNPROVEN_PREVIOUS_PROMOTED" }
                target.writeText(original.getValue(flow.flowId))
                check(store.openProject(id).flowSources == original)
            }
            record("data-legacy-lua-backup-retains-mode-and-source-without-opening-editor") {
                val legacy = store.createProject("公开历史Lua", ProjectSourceMode.LUA)
                val saved = store.saveLua(legacy.manifest.projectId, "return function() return 42 end\n", legacy.luaSource)
                val bytes = ByteArrayOutputStream().also { store.exportProjectBackup(saved.manifest.projectId, it) }.toByteArray()
                val restored = store.importProjectBackup(ByteArrayInputStream(bytes))
                check(restored.manifest.sourceMode == ProjectSourceMode.LUA && restored.luaSource == saved.luaSource) { "QA_LEGACY_DATA_CHANGED" }
                check(!StudioProjectPolicy.canOperate(restored.manifest.sourceMode)) { "QA_LEGACY_EDITOR_REOPENED" }
            }
            record("data-multiselect-copy-before-undo-redo-and-source-roundtrip") {
                val rootBlock = project.manifest.flows.single().rootBlockId
                val editor = VisualEditorState.create(original.values.single(), rootBlock)
                repeat(24) { editor.insertNoop() }
                val baseline = editor.currentSource
                val baselineRows = editor.rows.size
                check(editor.selectNodes(editor.rows.take(20).map { it.nodeId }))
                val copied = editor.copySelection()
                check(copied.size == 20 && editor.pasteSelection(copied, beforeSelection = true)) { "QA_MULTI_PASTE_FAILED" }
                val pasted = editor.currentSource
                check(editor.rows.size == baselineRows + copied.sumOf { it.nodes.size } && editor.undo() &&
                    editor.currentSource == baseline && editor.redo() && editor.currentSource == pasted) { "QA_MULTI_UNDO_PARTIAL" }
                val exported = pluginSourceToolchain.export(pasted, rootBlock)
                val parsed = pluginSourceToolchain.parse(exported.text, pasted, rootBlock, emptySet())
                fun nodes(source: String) = source.lineSequence().filter(String::isNotBlank).map(JsonParser::parseString).toSet()
                check(nodes(parsed.text) == nodes(pasted)) { "QA_MULTI_SOURCE_ROUNDTRIP" }
            }
            record("data-deep-tree-disable-subtree-and-source-roundtrip") {
                val rootBlock = project.manifest.flows.single().rootBlockId
                val lines = (0..24).map { depth ->
                    org.json.JSONObject().put("flowSchemaVersion", 1).put("nodeVersion", 1).put("nodeId", "qa-deep-$depth")
                        .put("blockId", if (depth == 0) rootBlock else "qa-deep-body-${depth - 1}")
                        .put("parentId", if (depth == 0) org.json.JSONObject.NULL else "qa-deep-${depth - 1}")
                        .put("depth", depth).put("orderKey", "a0").put("kind", if (depth == 24) "task.noop" else "control.repeat")
                        .put("args", org.json.JSONObject().apply { if (depth != 24) put("times", 1) })
                        .apply { if (depth != 24) put("childBlocks", org.json.JSONObject().put("body", "qa-deep-body-$depth")) }.toString()
                }.joinToString("\n", postfix = "\n")
                val editor = VisualEditorState.create(lines, rootBlock)
                check(!editor.isReadOnly && editor.rows.size == 25 && editor.rows.last().depth == 24) { "QA_DEEP_TREE_STRUCTURE" }
                editor.selectOnly("qa-deep-0")
                check(editor.toggleDisabledSelection() && editor.isNodeDisabled("qa-deep-24")) { "QA_DISABLED_PARENT_NOT_INHERITED" }
                val exported = pluginSourceToolchain.export(editor.currentSource, rootBlock)
                val parsed = pluginSourceToolchain.parse(exported.text, editor.currentSource, rootBlock, emptySet())
                check(VisualEditorState.create(parsed.text, rootBlock).isNodeDisabled("qa-deep-24")) { "QA_DEEP_DISABLED_SOURCE_LOST" }
                check(editor.undo() && !editor.isNodeDisabled("qa-deep-24")) { "QA_DEEP_UNDO_DISABLE" }
            }
        } finally {
            check(root.canonicalFile.parentFile == test.targetContext.cacheDir.canonicalFile && root.name.startsWith("qa-data-safety-"))
            check(root.deleteRecursively()) { "QA_DATA_SANDBOX_CLEANUP" }
        }
    }
}
