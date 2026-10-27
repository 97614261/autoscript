package com.autoscript.studio

import android.app.Instrumentation
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import com.autoscript.project.store.ProjectResourceKind
import com.autoscript.project.store.ProjectSnapshot
import com.autoscript.project.store.ProjectStore
import com.autoscript.runtime.client.RuntimeClient
import com.autoscript.runtime.client.VisualCompileResult
import com.google.gson.JsonParser
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID

/** Exercises production storage/editor/compiler on Android, not a claim that dialogs were touched. */
internal object StudioProjectClosureFixture {
    fun run(instrumentation: Instrumentation, client: RuntimeClient, store: ProjectStore, initial: ProjectSnapshot,
        case: (String, () -> Unit) -> Unit): ProjectSnapshot {
        val id = initial.manifest.projectId
        check(id.startsWith("project-qa-")) { "QA_ID_REQUIRED" }
        var snapshot = store.openProject(id)
        val restoredIds = mutableListOf<String>()
        val journalRoot = File(instrumentation.targetContext.cacheDir, "qa-journal-${UUID.randomUUID()}")
        check(journalRoot.mkdir()) { "QA_JOURNAL_CREATE_FAILED" }
        try {
            case("android-multi-plugin-source-drafts-atomic-save") {
                snapshot = store.createFlow(id, "qa-worker", snapshot.manifest.flows.map { it.flowId }.toSet())
                val projectIds = VisualProjectIds(snapshot.flowSources.values, snapshot.manifest.flows.map { it.rootBlockId })
                val editors = snapshot.manifest.flows.associate { flow ->
                    flow.flowId to VisualEditorState.createProjectScoped(snapshot.flowSources.getValue(flow.flowId), flow.rootBlockId, projectIds).apply { insertNoop() }
                }
                for (flow in snapshot.manifest.flows) {
                    val editor = editors.getValue(flow.flowId)
                    val exported = pluginSourceToolchain.export(editor.currentSource, flow.rootBlockId)
                    val parsed = pluginSourceToolchain.parse(exported.text, editor.currentSource, flow.rootBlockId, snapshot.manifest.variables.map { it.name }.toSet())
                    fun nodes(source: String) = source.lineSequence().filter(String::isNotBlank).map(JsonParser::parseString).toList()
                    check(nodes(parsed.text) == nodes(editor.currentSource)) { "QA_SOURCE_ROUNDTRIP" }
                    val saved = editor.currentSource
                    check(editor.undo() && editor.redo() && editor.currentSource == saved) { "QA_SOURCE_UNDO" }
                }
                val journal = EditorDraftJournal(journalRoot)
                val checkpoint = journal.capture(snapshot, editors)
                journal.write(id, checkpoint)
                check(journal.read(id) == checkpoint && journal.compatible(checkpoint, snapshot).size == 2) { "QA_RECOVERY_MISMATCH" }
                val submitted = editors.mapValues { it.value.currentSource }
                check(client.validateVisualDraftSet(id, submitted.mapValues { it.value.toByteArray() }) is VisualCompileResult.Success) { "QA_DRAFT_COMPILE_FAILED" }
                snapshot = store.saveFlows(snapshot, submitted)
                check(snapshot.flowSources == submitted) { "QA_MULTI_SAVE_PARTIAL" }
                check(journal.compatible(checkpoint, snapshot).isEmpty()) { "QA_STALE_RECOVERY_ACCEPTED" }
                journal.discard(id)
                check(journal.read(id) == null) { "QA_RECOVERY_DISCARD_FAILED" }
            }
            case("android-stale-project-save-rejected-without-partial-write") {
                val stale = snapshot
                snapshot = store.renameProject(id, "公开合成验收更新")
                val before = snapshot.flowSources
                var rejected = false
                try { store.saveFlows(stale, before.mapValues { it.value + "\n" }) } catch (_: com.autoscript.project.store.ProjectManifestConflictException) { rejected = true }
                check(rejected && store.openProject(id).flowSources == before) { "QA_STALE_SAVE_OVERWROTE" }
            }
            case("android-image-folders-rename-collision-and-original-pixels") {
                val directories = store.readImageDirectories(id)
                val folder = newImageFolderPath("", "QA_images", directories)
                store.createImageDirectory(id, folder)
                store.createImageDirectory(id, "$folder/empty")
                val bitmap = Bitmap.createBitmap(24, 16, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.rgb(32, 160, 96)) }
                val bytes = try { ByteArrayOutputStream().apply { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, this)) }.toByteArray() }
                    finally { bitmap.recycle() }
                val name = imageSaveFileName("QA_capture.png")
                repeat(2) {
                    snapshot = store.importResource(id, ProjectResourceKind.IMAGE, name, ByteArrayInputStream(bytes),
                        snapshot.manifest.resources.map { it.get("path").asString }.toSet(), folder)
                }
                val images = snapshot.manifest.resources.filter { it.get("kind").asString == "image" }
                check(images.size == 2 && images.map { it.get("path").asString }.distinct().size == 2) { "QA_IMAGE_OVERWRITTEN" }
                images.forEach { image ->
                    val file = File(snapshot.directory, image.get("path").asString)
                    check(file.readBytes().contentEquals(bytes)) { "QA_IMAGE_BYTES_CHANGED" }
                    val decoded = requireNotNull(BitmapFactory.decodeFile(file.path))
                    try { check(decoded.width == 24 && decoded.height == 16 && decoded.getPixel(4, 4) == Color.rgb(32, 160, 96)) { "QA_IMAGE_PIXELS_CHANGED" } }
                    finally { decoded.recycle() }
                }
            }
            case("android-designer-all-controls-pages-align-and-draft-recovery") {
                var draft = RunnerUiDesignerDraft()
                RunnerUiControlKind.entries.forEach { draft = draft.add(it) }
                draft = draft.addPage(com.autoscript.script.ui.UiPage("qaOther", "QA第二页"))
                    .add(RunnerUiControlKind.TEXT, "qaOther")
                val selected = draft.fields.take(2).map { it.id }.toSet()
                val aligned = draft.align(selected, "left")
                check(aligned.fields.filter { it.id in selected }.map { it.ui.x }.distinct().size == 1) { "QA_DESIGN_ALIGN" }
                val formal = requireNotNull(draft.toRunnerUiJson())
                check(runnerUiDesignerDraft(formal) == draft) { "QA_DESIGN_ROUNDTRIP" }
                val journal = UiDesignerDraftJournal(journalRoot, id)
                val base = journal.base(snapshot.manifest)
                journal.write(base, draft, journal.ticket())
                check(journal.read()?.draft == draft) { "QA_DESIGN_RECOVERY" }
                journal.discard()
                snapshot = store.updateRunnerUi(id, formal, snapshot.manifest.runnerUi)
                check(snapshot.manifest.runnerUi == formal) { "QA_DESIGN_SAVE" }
                val definition = com.autoscript.script.ui.ScriptUiDefinition.parse(formal.toString())
                check(definition.fields.map { it.control }.toSet().containsAll(com.autoscript.script.ui.UiControl.entries)) { "QA_DESIGN_CONTROLS_MISSING" }
            }
            case("android-backup-import-preserves-sources-ui-and-empty-folders") {
                val original = store.openProject(id)
                val output = ByteArrayOutputStream()
                store.exportProjectBackup(id, output)
                check(output.size() <= 4 * 1024 * 1024) { "QA_BACKUP_BUDGET" }
                val restored = store.importProjectBackup(ByteArrayInputStream(output.toByteArray()))
                restoredIds += restored.manifest.projectId
                check(restored.manifest.projectId.startsWith("project-qa-") && restored.manifest.projectId != id) { "QA_IMPORT_IDENTITY" }
                check(restored.flowSources == original.flowSources && restored.manifest.variables == original.manifest.variables &&
                    restored.manifest.runnerUi == original.manifest.runnerUi && restored.manifest.resources == original.manifest.resources) { "QA_BACKUP_DATA_CHANGED" }
                check(store.readImageDirectories(restored.manifest.projectId) == store.readImageDirectories(id)) { "QA_EMPTY_FOLDER_LOST" }
                original.manifest.resources.forEach { image ->
                    val path = image.get("path").asString
                    check(File(restored.directory, path).readBytes().contentEquals(File(original.directory, path).readBytes())) { "QA_BACKUP_PIXELS_CHANGED" }
                }
            }
            return snapshot
        } finally {
            restoredIds.forEach { restored ->
                check(restored.startsWith("project-qa-") && restored != id) { "QA_CLEANUP_IDENTITY" }
                store.deleteProject(restored)
            }
            // Only the exact temporary tree created above, never the shared recovery store.
            check(journalRoot.canonicalFile.parentFile == instrumentation.targetContext.cacheDir.canonicalFile)
            check(journalRoot.name.startsWith("qa-journal-"))
            journalRoot.deleteRecursively()
        }
    }
}
