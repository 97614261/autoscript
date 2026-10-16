package com.autoscript.runtime.service

import android.content.Context
import android.system.Os
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/** Exercises real Android lstat/O_NOFOLLOW on owned synthetic files, without Root or host privileges. */
internal object DataSandboxAcceptanceFixture {
    fun run(context: Context): JSONObject {
        val report = JSONObject().put("scope", "Android private synthetic data sandbox; not user project storage")
        val cases = JSONArray()
        val cache = context.cacheDir.canonicalFile
        val owned = File(cache, "sandbox-acceptance-${UUID.randomUUID()}")
        check(owned.parentFile == cache && owned.mkdir()) { "SANDBOX_FIXTURE_CREATE" }
        val links = mutableListOf<File>()
        var adapter: NativeDataFileAdapter? = null
        var success = true
        try {
            val outside = File(owned, "outside.txt").apply { writeText("synthetic-original") }
            val outsideDirectory = File(owned, "outside-dir").apply { check(mkdir()) }
            File(outsideDirectory, "child.txt").writeText("synthetic-child")
            val native = NativeDataFileAdapter(owned).also { adapter = it }
            check(native.configure("synthetic", true)) { "SANDBOX_CONFIGURE" }
            val data = File(owned, "script-data/synthetic/data")
            fun rejected(work: Long, operation: Int, path: String, content: ByteArray = byteArrayOf()) {
                val result = native.dispatch(work, operation, path, content)
                check(result.firstOrNull() == 1.toByte() &&
                    String(result, 1, result.size - 1, Charsets.US_ASCII) == "FILE_PATH_INVALID") { "SANDBOX_LINK_NOT_REJECTED" }
            }
            val symlink = File(data, "link")
            Os.symlink(outside.path, symlink.path); links.add(symlink)
            check(AndroidProjectDataFileOps.kind(symlink) == ProjectDataKind.UNSAFE) { "SANDBOX_LSTAT_FOLLOWED" }
            rejected(1, 14100, "link")
            rejected(2, 14101, "link", "changed".toByteArray())
            check(outside.readText() == "synthetic-original") { "SANDBOX_TARGET_CHANGED" }
            cases.put(JSONObject().put("case", "symlink-read-write-rejected-target-unchanged").put("pass", true))
            check(symlink.delete()) { "SANDBOX_LINK_CLEANUP" }; links.remove(symlink)

            val directoryLink = File(data, "linked-directory")
            Os.symlink(outsideDirectory.path, directoryLink.path); links.add(directoryLink)
            rejected(3, 14100, "linked-directory/child.txt")
            rejected(4, 14101, "linked-directory/child.txt", "changed".toByteArray())
            rejected(5, 14102, "")
            check(File(outsideDirectory, "child.txt").readText() == "synthetic-child") { "SANDBOX_DIRECTORY_TARGET_CHANGED" }
            cases.put(JSONObject().put("case", "symlink-directory-rejected").put("pass", true))
            check(directoryLink.delete()) { "SANDBOX_LINK_CLEANUP" }; links.remove(directoryLink)

            val hardlink = File(data, "hardlink")
            Os.link(outside.path, hardlink.path); links.add(hardlink)
            check(AndroidProjectDataFileOps.kind(hardlink) == ProjectDataKind.UNSAFE) { "SANDBOX_HARDLINK_ACCEPTED" }
            rejected(6, 14100, "hardlink")
            rejected(7, 14101, "hardlink", "changed".toByteArray())
            check(outside.readText() == "synthetic-original") { "SANDBOX_HARDLINK_TARGET_CHANGED" }
            cases.put(JSONObject().put("case", "hardlink-read-write-rejected-target-unchanged").put("pass", true))
        } catch (failure: Throwable) {
            success = false
            report.put("error", failure.javaClass.simpleName)
                .put("code", failure.message?.takeIf { it.matches(Regex("[A-Z_]{1,80}")) } ?: "SANDBOX_ASSERTION_FAILED")
        } finally {
            adapter?.close()
            // Remove only links created by this fixture before recursive cleanup, never follow them.
            links.forEach { check(it.delete()) { "SANDBOX_LINK_CLEANUP" } }
            check(owned.parentFile == cache && owned.canonicalFile == owned.absoluteFile &&
                owned.name.startsWith("sandbox-acceptance-")) { "SANDBOX_CLEANUP_TARGET" }
            check(owned.deleteRecursively()) { "SANDBOX_CLEANUP_FAILED" }
        }
        return report.put("cases", cases).put("pass", success)
    }
}
