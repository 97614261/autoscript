package com.autoscript.studio

import android.app.Instrumentation
import android.content.ClipboardManager
import android.content.Intent
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import androidx.activity.ComponentActivity
import com.autoscript.project.store.*
import com.autoscript.runtime.client.RuntimeClient
import org.json.JSONArray
import org.json.JSONObject
import java.net.ServerSocket
import java.util.concurrent.atomic.AtomicInteger

/** Real input targets contain public QA text only; original clipboard is restored. */
internal object LibraryInputUsageFixture {
    fun run(test: Instrumentation, activity: ComponentActivity, client: RuntimeClient, store: ProjectStore,
        initial: ProjectSnapshot, record: (String, () -> Unit) -> Unit): ProjectSnapshot {
        val rt = OwnedUsageRuntime(client, store, initial)
        rt.variables("text" to ProjectVariableType.STRING, "value" to ProjectVariableType.INTEGER)
        val clipboard = activity.getSystemService(ClipboardManager::class.java)
        var original: android.content.ClipData? = null
        test.runOnMainSync { original = clipboard.primaryClip }
        fun waitFor(description: String, predicate: () -> Boolean) = UsageTestUi(test).await(description, predicate)
        try {
            record("library-input.imestatus-and-imetext-commit-public-unicode-to-real-field") {
                lateinit var field: EditText
                test.runOnMainSync {
                    field = EditText(activity).apply { hint = "PUBLIC_QA_INPUT"; isSingleLine = true }
                    activity.setContentView(field); field.requestFocus()
                    activity.getSystemService(InputMethodManager::class.java).showSoftInput(field, InputMethodManager.SHOW_IMPLICIT)
                }
                test.waitForIdleSync()
                test.sendStatus(1, android.os.Bundle().apply { putString("stream", "QA_USAGE_IME_READY\n") })
                SystemClock.sleep(1500)
                test.runOnMainSync { field.requestFocus(); activity.getSystemService(InputMethodManager::class.java).showSoftInput(field, InputMethodManager.SHOW_FORCED) }
                UsageTestUi(test).tap(android.graphics.Rect().also(field::getGlobalVisibleRect))
                SystemClock.sleep(700)
                rt.run(listOf(rt.block("input.imestatus", "resultVariable" to "text"),
                    rt.block("input.imetext", "text" to "PUBLIC_QA_中文Abc+190", "resultVariable" to "value"))) {
                    val status = JSONObject(it["text"].orEmpty())
                    check(status.getBoolean("selected") && status.getBoolean("connected"))
                    test.runOnMainSync { check(field.text.toString() == "PUBLIC_QA_中文Abc+190") }
                }
            }
            record("library-input.pastetext-reaches-real-editable-field") {
                lateinit var field: EditText
                test.runOnMainSync {
                    field = EditText(activity).apply { hint = "PUBLIC_QA_PASTE"; isSingleLine = true }
                    activity.setContentView(field); field.requestFocus()
                    activity.getSystemService(InputMethodManager::class.java).showSoftInput(field, InputMethodManager.SHOW_IMPLICIT)
                }
                SystemClock.sleep(400)
                rt.run(listOf(rt.block("input.pastetext", "text" to "PUBLIC_QA_粘贴123"))) {
                    waitFor("public pasted text") {
                        var matches = false
                        test.runOnMainSync { matches = field.text.toString() == "PUBLIC_QA_粘贴123" }
                        matches
                    }
                }
                test.runOnMainSync { activity.getSystemService(InputMethodManager::class.java).hideSoftInputFromWindow(field.windowToken, 0); field.clearFocus() }
            }
            record("library-input.playrecording-validates-and-delivers-owned-tap") {
                val taps = AtomicInteger()
                test.runOnMainSync { activity.setContentView(object : View(activity) {
                    override fun onTouchEvent(event: MotionEvent): Boolean { if (event.actionMasked == MotionEvent.ACTION_UP) taps.incrementAndGet(); return true }
                }) }
                test.waitForIdleSync(); SystemClock.sleep(300)
                val metrics = activity.windowManager.currentWindowMetrics.bounds
                val recording = JSONObject().put("width", metrics.width()).put("height", metrics.height())
                    .put("rotation", activity.display?.rotation ?: 0).put("elapsedMs", 120).put("skippedGestures", 0)
                    .put("actions", JSONArray().put(JSONArray(listOf(1, 0, 120, 180, 200, 180, 200, 0))))
                rt.run(listOf(rt.block("variable.set", "name" to "text", "value" to recording.toString()),
                    rt.block("input.playrecording", "recordingVariable" to "text"))) {
                    waitFor("public replay tap") { taps.get() == 1 }
                }
            }
            record("library-app.openurl-requests-system-handler-for-public-loopback") {
                ServerSocket(0, 1, java.net.InetAddress.getByName("127.0.0.1")).use { server ->
                    server.soTimeout = 8000
                    val requested = AtomicInteger()
                    val worker = Thread {
                        runCatching { server.accept().use { socket ->
                            socket.soTimeout = 3000
                            socket.getInputStream().bufferedReader().readLine()
                            requested.incrementAndGet()
                            socket.getOutputStream().write("HTTP/1.1 200 OK\r\nContent-Length: 9\r\nConnection: close\r\n\r\nPUBLIC_QA".toByteArray())
                        } }
                    }.apply { isDaemon = true; start() }
                    try {
                        rt.run(listOf(rt.block("app.openurl", "url" to "http://127.0.0.1:${server.localPort}/public-qa", "resultVariable" to "value"))) {
                            check(it["value"] == "1")
                        }
                    } finally {
                        server.close(); worker.join(1000)
                        test.runOnMainSync { activity.startActivity(Intent(activity, activity.javaClass).addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)) }
                    }
                }
            }
            record("library-app.open-requests-own-studio-launcher") {
                rt.run(listOf(rt.block("app.open", "packageName" to test.targetContext.packageName))) { }
            }
            return store.openProject(rt.id)
        } finally {
            rt.stop()
            test.runOnMainSync { original?.let(clipboard::setPrimaryClip) ?: clipboard.clearPrimaryClip() }
        }
    }
}
