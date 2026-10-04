package com.autoscript.runtime.service

import android.content.Context
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import com.autoscript.engine.jni.NativeEngineBridge
import com.autoscript.runtime.api.RecordingReply
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** One real Root lease and one bounded, one-shot completed reply. Native I/O never holds the gate. */
internal class RuntimeInputRecordingController(
    context: Context,
    private val nativeHandle: () -> Long,
    private val generation: () -> Long,
    private val ready: () -> Boolean,
    private val onFinished: () -> Unit,
) {
    private class Lease(val id: Long, val generation: Long, val handle: Long, val width: Int, val height: Int, val rotation: Int) {
        val finishing = AtomicBoolean(false)
        val cancelled = AtomicBoolean(false)
        val started = CountDownLatch(1)
        @Volatile var accepted = false
        @Volatile var controls: Rect = Rect()
        var expiry: Runnable? = null
    }
    private val main = Handler(Looper.getMainLooper())
    private val active = AtomicReference<Lease?>()
    private val cached = AtomicReference<RecordingReply?>()
    private val closed = AtomicBoolean(false)
    private val overlay = RuntimeRecordingOverlay(context) { cancel ->
        active.get()?.let { lease -> finishAsync(lease, cancel, discardTouch = true) }
    }
    val busy: Boolean get() = active.get() != null

    fun start(id: Long, expectedGeneration: Long, width: Int, height: Int, rotation: Int, duration: Int, budget: Int): RecordingReply {
        fun failure(status: Int) = RecordingReply(id,expectedGeneration,status)
        if (closed.get() || id <= 0 || expectedGeneration != generation()) return failure(RecordingReply.SESSION_MISMATCH)
        if (duration !in 1000..60_000 || budget !in 128..16_384 || width !in 1..8192 || height !in 1..8192 ||
            width.toLong() * height > 33_554_432 || rotation !in 0..3) return failure(RecordingReply.INVALID_EVENT)
        val lease = Lease(id,expectedGeneration,nativeHandle(),width,height,rotation)
        if (!active.compareAndSet(null,lease)) return failure(RecordingReply.BUSY)
        cached.set(null)
        try {
            if (!ready() || lease.handle == 0L || !overlay.canShow()) return failure(RecordingReply.UNSUPPORTED_DEVICE)
            val shown = CountDownLatch(1)
            main.post {
                if (active.get() === lease && !lease.cancelled.get()) overlay.show(false) { bounds -> lease.controls = bounds ?: Rect(); shown.countDown() }
                else shown.countDown()
            }
            if (!shown.await(2,TimeUnit.SECONDS) || lease.controls.isEmpty || lease.cancelled.get()) return failure(RecordingReply.ENGINE_ERROR)
            // Fixed JNI work runs on the caller's Binder/IO thread, never the main thread or under a lock.
            val status = NativeEngineBridge.nativeStartRecording(lease.handle,id,width,height,rotation,duration,budget)
            if (status != RecordingReply.SUCCESS) return failure(status.takeIf { it in 1..11 } ?: RecordingReply.ENGINE_ERROR)
            lease.accepted = true
            if (lease.cancelled.get() || generation() != expectedGeneration || !ready()) {
                finishAsync(lease,true,true)
                return failure(RecordingReply.SESSION_MISMATCH)
            }
            val expiry = Runnable { finishAsync(lease,false,true,RecordingReply.TIMEOUT) }
            lease.expiry = expiry
            main.post { if (active.get() === lease) { overlay.enable(); main.postDelayed(expiry,duration.toLong()) } }
            return RecordingReply(id,expectedGeneration,RecordingReply.SUCCESS,width,height,rotation)
        } catch (_: Exception) {
            if (lease.accepted) finishAsync(lease,true,true)
            return failure(RecordingReply.ENGINE_ERROR)
        } finally {
            lease.started.countDown()
            if (!lease.accepted) {
                active.compareAndSet(lease,null)
                main.post { if (active.get() == null) overlay.hide() }
            }
        }
    }

    /** Stop from Studio; a floating-stop result is consumed here exactly once. */
    fun finish(id: Long, expectedGeneration: Long): RecordingReply {
        takeCached(id,expectedGeneration)?.let { return it }
        val lease = active.get()
        if (lease == null || lease.id != id || lease.generation != expectedGeneration || expectedGeneration != generation())
            return RecordingReply(id,expectedGeneration,RecordingReply.SESSION_MISMATCH)
        if (!lease.finishing.compareAndSet(false,true)) return RecordingReply(id,expectedGeneration,RecordingReply.BUSY)
        return finishNative(lease,false,true,null,cache = false)
    }
    fun cancel(id: Long, expectedGeneration: Long): Boolean {
        cached.get()?.takeIf { it.recordingId == id && it.generation == expectedGeneration }?.let { cached.compareAndSet(it,null); return true }
        val lease = active.get() ?: return false
        if (lease.id != id || lease.generation != expectedGeneration) return false
        lease.cancelled.set(true)
        finishAsync(lease,true,true)
        return true
    }
    fun abort(status: Int = RecordingReply.SESSION_MISMATCH) {
        active.get()?.let { it.cancelled.set(true); finishAsync(it,true,true,status) }
    }
    fun close() { closed.set(true); cached.set(null); abort(); main.post { overlay.hide() } }
    private fun takeCached(id: Long, expectedGeneration: Long): RecordingReply? {
        val reply = cached.get() ?: return null
        if (reply.recordingId != id || reply.generation != expectedGeneration || expectedGeneration != generation()) return null
        return reply.takeIf { cached.compareAndSet(reply,null) }
    }
    private fun finishAsync(lease: Lease, cancel: Boolean, discardTouch: Boolean, forcedStatus: Int? = null) {
        if (cancel) lease.cancelled.set(true)
        if (!lease.finishing.compareAndSet(false,true)) return
        // At most one finishing worker; no business queue or unbounded executor is involved.
        Thread({ finishNative(lease,cancel,discardTouch,forcedStatus,cache = true) },"input-recording-stop").start()
    }
    private fun finishNative(lease: Lease, cancel: Boolean, discardTouch: Boolean, forcedStatus: Int?, cache: Boolean): RecordingReply {
        main.post { lease.expiry?.let(main::removeCallbacks); overlay.hide() }
        var reply = RecordingReply(lease.id,lease.generation,RecordingReply.ENGINE_ERROR)
        try {
            if (!lease.started.await(7,TimeUnit.SECONDS) || !lease.accepted) {
                reply = RecordingReply(lease.id,lease.generation,forcedStatus ?: RecordingReply.CANCELLED)
            } else {
                val bytes = NativeEngineBridge.nativeFinishRecording(lease.handle,lease.id,cancel || lease.cancelled.get(),discardTouch)
                if (bytes != null) reply = RecordingReply.fromNative(bytes,lease.id,lease.generation,lease.width,lease.height,lease.rotation)
                if (generation() != lease.generation || nativeHandle() != lease.handle) reply = RecordingReply(lease.id,lease.generation,RecordingReply.SESSION_MISMATCH)
                else if (forcedStatus != null) reply = reply.copy(status = forcedStatus,actions = ByteArray(0),skippedGestures = 0)
                else if (cancel || lease.cancelled.get()) reply = reply.copy(status = RecordingReply.CANCELLED,actions = ByteArray(0),skippedGestures = 0)
                else if (reply.status == RecordingReply.SUCCESS) reply = reply.copy(actions = excludeControls(reply.actions,lease.controls.left,lease.controls.top,lease.controls.right,lease.controls.bottom))
            }
        } catch (_: Exception) { /* Stable, private failure; no paths/event contents enter logs. */ }
        active.compareAndSet(lease,null)
        if (cache && !closed.get()) {
            cached.set(reply)
            val completed = reply
            main.postDelayed({ cached.compareAndSet(completed,null) },120_000L)
        }
        main.post { if (active.get() == null) overlay.hide(); if (!closed.get()) onFinished() }
        return reply
    }
    companion object {
        internal fun excludeControls(actions: ByteArray, left: Int, top: Int, right: Int, bottom: Int): ByteArray {
            require(actions.size <= RecordingReply.MAX_ACTION_BYTES && actions.size % RecordingReply.ACTION_BYTES == 0)
            val kept = actions.asList().chunked(RecordingReply.ACTION_BYTES).filter { chunk ->
                val data = ByteBuffer.wrap(chunk.toByteArray()).order(ByteOrder.LITTLE_ENDIAN)
                val kind = data.int; data.int; data.int
                val x1=data.int; val y1=data.int; val x2=data.int; val y2=data.int
                fun inside(x: Int, y: Int) = x >= left && x < right && y >= top && y < bottom
                kind == 4 || (!inside(x1,y1) && !inside(x2,y2))
            }
            return kept.flatten().toByteArray()
        }
    }
}
