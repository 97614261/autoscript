package com.autoscript.studio

import android.app.Instrumentation
import android.graphics.Canvas
import android.graphics.Color
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import androidx.activity.ComponentActivity
import com.autoscript.project.store.ProjectSnapshot
import com.autoscript.project.store.ProjectStore
import com.autoscript.project.store.ProjectVariableType
import com.autoscript.runtime.api.RecordingReply
import com.autoscript.runtime.client.RuntimeClient
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/** Actual evdev acquisition and playback, restricted to the observed 720x1280 QA device/view. */
internal object RecordingUsageAcceptanceFixture {
    fun run(test: Instrumentation, activity: ComponentActivity, client: RuntimeClient, store: ProjectStore,
        initial: ProjectSnapshot, record: (String, () -> Unit) -> Unit): ProjectSnapshot {
        check(android.os.Process.is64Bit() && initial.manifest.design.width == 720 && initial.manifest.design.height == 1280)
        val rt = OwnedUsageRuntime(client, store, initial)
        rt.variables("value" to ProjectVariableType.INTEGER)
        store.updateCapabilities(rt.id, setOf("core.task", "input.basic"), store.openProject(rt.id).manifest.capabilities.toSet())
        val ui = UsageTestUi(test)
        val received = AtomicInteger()
        val surface = object : View(activity) {
            override fun onDraw(canvas: Canvas) { canvas.drawColor(Color.rgb(15, 35, 50)) }
            override fun onTouchEvent(event: MotionEvent): Boolean {
                if (event.actionMasked == MotionEvent.ACTION_UP) received.incrementAndGet()
                return true
            }
        }
        test.runOnMainSync { activity.setContentView(surface) }
        test.waitForIdleSync()
        fun ownSurface() = test.runOnMainSync {
            check(surface.isShown && activity.hasWindowFocus() && surface.width >= 600 && surface.height >= 800) { "QA_RECORDING_SURFACE_NOT_ACTIVE" }
        }
        val capability = ParcelFileDescriptor.AutoCloseInputStream(test.uiAutomation.executeShellCommand("getevent -lp /dev/input/event1")).use {
            it.bufferedReader().readText().also { text -> check(text.length < 8192) }
        }
        check("ABS_MT_SLOT" in capability && "ABS_MT_TRACKING_ID" in capability && "max 719" in capability && "max 1279" in capability)
        var active: Long? = null
        var captured: RecordingReply? = null
        var pointerHeld = false
        // Kernel timestamps are assigned by evdev; UiAutomation injection bypasses this recorder.
        val events = FileOutputStream("/dev/input/event1")
        fun send(vararg values: Triple<Int, Int, Int>) {
            val bytes = ByteBuffer.allocate(values.size * 24).order(ByteOrder.nativeOrder())
            for ((type, code, value) in values) bytes.putLong(0).putLong(0).putShort(type.toShort()).putShort(code.toShort()).putInt(value)
            events.write(bytes.array())
        }
        fun up() {
            send(Triple(3,47,0), Triple(3,57,-1), Triple(3,58,0), Triple(3,24,0), Triple(1,330,0), Triple(0,0,0))
            pointerHeld = false
        }
        fun gesture(x: Int, y: Int, duration: Long, x2: Int = x, y2: Int = y) {
            ownSurface()
            pointerHeld = true
            try {
                send(Triple(3,47,0), Triple(3,57,412), Triple(3,53,x), Triple(3,54,y), Triple(3,0,x), Triple(3,1,y),
                    Triple(3,58,1), Triple(3,24,1), Triple(1,330,1), Triple(0,0,0))
                if (x == x2 && y == y2) SystemClock.sleep(duration)
                else for (step in 1..8) {
                    SystemClock.sleep(duration / 8)
                    val px = x + (x2 - x) * step / 8; val py = y + (y2 - y) * step / 8
                    send(Triple(3,53,px), Triple(3,54,py), Triple(3,0,px), Triple(3,1,py), Triple(0,0,0))
                }
            } finally { up() }
            SystemClock.sleep(100)
        }
        fun start(duration: Int = 10_000): Long {
            ownSurface()
            val id = runBlocking { withTimeout(20_000) { client.startRecording(rt.id, rt.main.flowId, duration, 512).getOrThrow() } }
            active = id
            return id
        }
        fun finish(id: Long): RecordingReply {
            val result = runBlocking { withTimeout(8_000) { client.finishRecording(id).getOrThrow() } }
            check(result.valid())
            if (result.status != RecordingReply.BUSY) active = null
            return result
        }
        fun completed(id: Long): RecordingReply {
            var result: RecordingReply? = null
            ui.await("recording completion") { result = client.consumeRecordingCompletion(id); result != null }
            active = null
            check(client.consumeRecordingCompletion(id) == null) { "QA_RECORDING_COMPLETION_REPEATED" }
            return requireNotNull(result)
        }
        try {
            record("recording-actual-evdev-tap-longhold-swipe-stop-and-checked-coordinates") {
                val before = received.get()
                val id = start()
                gesture(180, 450, 90); gesture(230, 550, 650); gesture(200, 700, 400, 400, 800)
                ui.await("three received raw gestures") { received.get() == before + 3 }
                val result = finish(id)
                check(result.status == RecordingReply.SUCCESS) { "QA_RECORDING_STATUS:${result.status}" }
                val actions = decodeRecordedActions(result.actions, result.width, result.height, result.elapsedMs)
                test.sendStatus(1, Bundle().apply { putString("stream", "QA_RECORDED_ACTIONS:${actions.joinToString()}\n") })
                check(actions.map { it.kind } == listOf(1, 2, 3)) { "QA_RECORDING_ACTIONS:$actions" }
                check(actions[0].x1 == 180 && actions[0].y1 == 450 && actions[1].x1 == 230 && actions[1].y1 == 550)
                check(actions[2].x1 == 200 && actions[2].y1 == 700 && actions[2].x2 == 400 && actions[2].y2 == 800)
                captured = result
            }
            record("recording-real-result-to-visual-project-and-compiled-playback") {
                val reply = requireNotNull(captured)
                val actions = decodeRecordedActions(reply.actions, reply.width, reply.height, reply.elapsedMs)
                val snippet = recordedSnippet(actions, reply.width, reply.height, store.openProject(rt.id).manifest.design, true)
                check(snippet.rejection == null && snippet.flowBlocks.count { it.kind.startsWith("input.") } == 3)
                val nodes = snippet.flowBlocks.map { block -> rt.block(block.kind, *block.arguments.map { it.key to requireNotNull(it.value) }.toTypedArray()) }
                val before = received.get()
                rt.run(nodes) { check(received.get() == before + 3) { "QA_RECORDING_REPLAY_NOT_DELIVERED" } }
            }
            record("recording-floating-stop-completes-once-and-keeps-the-recorded-tap") {
                val id = start(); gesture(180, 450, 90)
                ui.click("停止")
                val reply = completed(id)
                check(reply.status == RecordingReply.SUCCESS && decodeRecordedActions(reply.actions, reply.width, reply.height, reply.elapsedMs).size == 1)
                ui.absent("REC")
            }
            record("recording-cancel-discards-events-and-releases-input-for-the-next-run") {
                val id = start(); gesture(180, 450, 90)
                check(runBlocking { client.cancelRecording(id) }.isSuccess); active = null
                ui.absent("REC"); check(client.consumeRecordingCompletion(id) == null)
                rt.run(listOf(rt.set("value", "51"))) { check(it["value"] == "51") }
            }
            record("recording-floating-cancel-completes-without-publishing-actions") {
                val id = start(); gesture(180, 450, 90); ui.click("取消")
                val reply = completed(id)
                check(reply.status == RecordingReply.CANCELLED && reply.actions.isEmpty())
                ui.absent("REC")
            }
            record("recording-time-limit-is-explicit-and-a-new-recording-can-start") {
                val id = start(1000)
                val reply = completed(id)
                check(reply.status == RecordingReply.TIMEOUT && reply.actions.isEmpty()) { "QA_RECORDING_TIMEOUT:${reply.status}" }
                ui.absent("REC")
                val next = start(); check(runBlocking { client.cancelRecording(next) }.isSuccess); active = null
            }
            return store.openProject(rt.id)
        } finally {
            if (pointerHeld) runCatching { up() }
            events.close()
            active?.let { runBlocking { client.cancelRecording(it) } }
            rt.stop()
        }
    }
}
