package com.autoscript.studio

import android.app.Instrumentation
import androidx.activity.ComponentActivity
import com.autoscript.project.store.*
import com.autoscript.runtime.client.RuntimeClient
import org.json.JSONArray
import org.json.JSONObject

/** All paths belong to the newly created QA project's private data sandbox. */
internal object LibraryFilesUsageFixture {
    fun run(test: Instrumentation, activity: ComponentActivity, client: RuntimeClient, store: ProjectStore,
        initial: ProjectSnapshot, record: (String, () -> Unit) -> Unit): ProjectSnapshot {
        val rt = OwnedUsageRuntime(client, store, initial)
        rt.variables("text" to ProjectVariableType.STRING, "value" to ProjectVariableType.INTEGER,
            "other" to ProjectVariableType.STRING)
        fun checkResult(kind: String, expected: String, vararg args: Pair<String, Any>) {
            record("library-$kind-real-sandbox-result") {
                val output = if (utilityResultType(kind) == ProjectVariableType.INTEGER) "value" else "text"
                rt.run(listOf(rt.block(kind, *args, "resultVariable" to output))) {
                    check(it[output] == expected) { "QA_FILE_RESULT:$kind:expected=$expected:actual=${it[output]}" }
                }
            }
        }
        try {
            checkResult("files.mkdir", "1", "path" to "public")
            checkResult("files.isdir", "1", "path" to "public")
            record("library-files.write-and-read-real-utf8-sandbox") {
                rt.run(listOf(rt.block("files.write", "path" to "public/notes.txt", "text" to "甲\n乙\n"),
                    rt.block("files.read", "path" to "public/notes.txt", "resultVariable" to "text"))) { check(it["text"] == "甲\n乙\n") }
            }
            checkResult("files.exists", "1", "path" to "public/notes.txt")
            checkResult("files.getsize", "8", "path" to "public/notes.txt")
            checkResult("files.getextension", "txt", "path" to "public/notes.txt")
            checkResult("files.append", "1", "path" to "public/notes.txt", "text" to "丙\n")
            checkResult("files.linecount", "3", "path" to "public/notes.txt")
            checkResult("files.readline", "乙", "path" to "public/notes.txt", "line" to 2)
            checkResult("files.insertline", "1", "path" to "public/notes.txt", "line" to 2, "text" to "插入")
            checkResult("files.writeline", "1", "path" to "public/notes.txt", "line" to 2, "text" to "替换")
            checkResult("files.deleteline", "1", "path" to "public/notes.txt", "line" to 2)
            checkResult("files.copy", "1", "path" to "public/notes.txt", "destination" to "public/copy.txt")
            checkResult("files.move", "1", "path" to "public/copy.txt", "destination" to "public/moved.txt")
            record("library-files.list-and-directorystats-match-written-files") {
                rt.run(listOf(rt.block("files.list", "path" to "public", "resultVariable" to "text"),
                    rt.block("files.directorystats", "path" to "public", "resultVariable" to "other"))) {
                    check(JSONArray(it["text"]).length() == 2)
                    val stats = JSONObject(it["other"])
                    check(stats.getInt("files") == 2 && stats.getInt("bytes") == 24)
                }
            }
            record("library-files.modifiedtime-reports-real-file-time") {
                rt.run(listOf(rt.block("files.modifiedtime", "path" to "public/notes.txt", "resultVariable" to "value"))) {
                    check((it["value"]?.toLongOrNull() ?: 0) > 0)
                }
            }
            checkResult("files.kvset", "1", "path" to "state.json", "key" to "中文", "valueJson" to "\"公开值\"")
            checkResult("files.kvget", "\"公开值\"", "path" to "state.json", "key" to "中文")
            checkResult("files.kvkeys", "[\"中文\"]", "path" to "state.json")
            checkResult("files.kvadd", "42", "path" to "state.json", "key" to "count", "delta" to 42)
            checkResult("files.kvremove", "1", "path" to "state.json", "key" to "中文")
            checkResult("files.frombase64", "1", "path" to "public/bytes.txt", "base64" to "UFVCTElDX1FB")
            checkResult("files.tobase64", "UFVCTElDX1FB", "path" to "public/bytes.txt")
            checkResult("files.zip", "1", "path" to "public", "destination" to "public.zip")
            checkResult("files.unzip", "1", "path" to "public.zip", "destination" to "unpacked")
            record("library-files-unzip-reads-identical-public-payload") {
                rt.run(listOf(rt.block("files.read", "path" to "unpacked/bytes.txt", "resultVariable" to "text"))) {
                    check(it["text"] == "PUBLIC_QA")
                }
            }
            record("library-files.remove-and-empty-rmdir-real-sandbox") {
                rt.run(listOf(rt.block("files.remove", "path" to "public/moved.txt"),
                    rt.block("files.exists", "path" to "public/moved.txt", "resultVariable" to "value"))) { check(it["value"] == "0") }
            }
            checkResult("files.mkdir", "1", "path" to "empty")
            checkResult("files.rmdir", "1", "path" to "empty")
            checkResult("files.isdir", "0", "path" to "empty")
            return store.openProject(rt.id)
        } finally { rt.stop() }
    }
}
