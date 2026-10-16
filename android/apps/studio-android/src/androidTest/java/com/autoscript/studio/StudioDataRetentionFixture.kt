package com.autoscript.studio

import android.app.Instrumentation
import android.graphics.Bitmap
import com.autoscript.project.store.ProjectResourceKind
import com.autoscript.project.store.ProjectSourceMode
import com.autoscript.project.store.ProjectStore
import com.autoscript.project.store.ProjectSnapshot
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import org.json.JSONObject

/** A ticket for exactly one self-created project across a data-preserving package update. */
internal object StudioDataRetentionFixture {
    fun run(test: Instrumentation, prepare: Boolean): JSONObject {
        val ticket = File(test.targetContext.filesDir, "qa-package-retention.json")
        val store = ProjectStore(File(test.targetContext.filesDir, "projects"), idFactory = { "project-qa-${UUID.randomUUID()}" })
        fun digest(snapshot: ProjectSnapshot): String {
            val paths = listOf("project.json") + snapshot.manifest.flows.map { it.path } + snapshot.manifest.resources.map { it["path"].asString }
            val hash = MessageDigest.getInstance("SHA-256")
            for (path in paths.sorted()) {
                val file = File(snapshot.directory, path)
                check(file.canonicalFile.toPath().startsWith(snapshot.directory.canonicalFile.toPath()) && file.length() <= 1_048_576) { "QA_RETENTION_PATH" }
                hash.update(path.toByteArray()); hash.update(0.toByte()); hash.update(file.readBytes()); hash.update(0.toByte())
            }
            return hash.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
        }
        if (prepare) {
            check(!ticket.exists()) { "QA_RETENTION_PREVIOUS_TICKET_EXISTS" }
            var snapshot = store.createProject("公开覆盖安装验收", ProjectSourceMode.VISUAL)
            try {
                snapshot = store.createFlow(snapshot.manifest.projectId, "qa-retained-worker", snapshot.manifest.flows.map { it.flowId }.toSet())
                val bitmap = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888).apply { eraseColor(android.graphics.Color.MAGENTA) }
                val bytes = try { ByteArrayOutputStream().apply { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, this)) }.toByteArray() }
                    finally { bitmap.recycle() }
                snapshot = store.importResource(snapshot.manifest.projectId, ProjectResourceKind.IMAGE, "QA_retained.png", ByteArrayInputStream(bytes), emptySet())
                val record = JSONObject().put("version", 1).put("projectId", snapshot.manifest.projectId).put("digest", digest(snapshot))
                ticket.outputStream().use { it.write(record.toString().toByteArray()); it.fd.sync() }
            } catch (failure: Throwable) {
                store.deleteProject(snapshot.manifest.projectId)
                throw failure
            }
        } else {
            check(ticket.isFile && ticket.length() in 1..4096) { "QA_RETENTION_TICKET_MISSING" }
            val record = JSONObject(ticket.readText())
            val id = record.getString("projectId")
            check(record.getInt("version") == 1 && id.matches(Regex("project-qa-[a-f0-9-]{36}")) &&
                record.getString("digest").matches(Regex("[a-f0-9]{64}"))) { "QA_RETENTION_TICKET_INVALID" }
            val snapshot = store.openProject(id)
            check(digest(snapshot) == record.getString("digest")) { "QA_PACKAGE_UPDATE_CHANGED_FORMAL_FILES" }
            store.deleteProject(id)
            check(ticket.delete()) { "QA_RETENTION_TICKET_CLEANUP" }
        }
        return JSONObject().put("scope", "one owned synthetic project across same-version data-preserving install")
            .put("pass", true).put("prepared", prepare).put("cleanup", !prepare)
    }
}
