package com.autoscript.runtime.service

import android.os.SystemClock
import android.view.InputDevice
import android.view.InputEvent
import android.view.MotionEvent
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException

/** Privileged app_process entry. No shell, application Context or Binder is exposed to scripts. */
internal object RootInputBridge {
    @JvmStatic
    fun main(arguments: Array<String>) {
        if (arguments.isNotEmpty()) return
        val input = DataInputStream(System.`in`)
        val output = DataOutputStream(System.out)
        val injector = runCatching { RootMotionInjector() }.getOrNull() ?: return
        runRootPointerBridge(input, output, SystemClock::uptimeMillis, injector::inject)
    }
}

/** Pure stream/session logic; the privileged Android injector stays outside the protocol parser. */
internal fun runRootPointerBridge(
    input: DataInputStream,
    output: DataOutputStream,
    nowMillis: () -> Long,
    inject: (Int, Int, Int, Long, Long) -> Boolean,
) {
    var active: Pair<Int, Int>? = null
    var downTime = 0L
    try {
        output.writeInt(0x41534931) // ASI1: fixed pointer protocol, network byte order.
        output.flush()
        while (true) {
            val action = try { input.readInt() } catch (_: EOFException) { break }
            val x = input.readInt()
            val y = input.readInt()
            val valid = action in 0..2 && x >= 0 && y >= 0 &&
                (action != 0 || active == null) &&
                (action != 2 || active != null)
            val status = if (!valid) 1 else {
                val now = nowMillis()
                if (action == 0 || downTime == 0L) downTime = now
                // Record DOWN before invoking Binder: a failure may still have injected it.
                if (action == 0) active = x to y
                val accepted = runCatching { inject(action, x, y, downTime, now) }.getOrDefault(false)
                if (accepted) {
                    active = if (action == 1) null else x to y
                    0
                } else 3
            }
            output.writeInt(status)
            output.flush()
        }
    } catch (_: Exception) {
        // Never print request coordinates, project data or reflection details to ordinary logs.
    } finally {
        active?.let { (x, y) -> runCatching { inject(1, x, y, downTime, nowMillis()) } }
    }
}

private class RootMotionInjector {
    private val managerClass = sequenceOf("android.hardware.input.InputManagerGlobal", "android.hardware.input.InputManager")
        .mapNotNull { runCatching { Class.forName(it) }.getOrNull() }
        .first { runCatching { it.getDeclaredMethod("getInstance") }.isSuccess }
    private val manager = managerClass.getDeclaredMethod("getInstance").invoke(null)
    private val inject = managerClass.getMethod("injectInputEvent", InputEvent::class.java, Integer.TYPE)

    fun inject(action: Int, x: Int, y: Int, downTime: Long, eventTime: Long): Boolean {
        val event = MotionEvent.obtain(downTime, eventTime, action, x.toFloat(), y.toFloat(), 0)
        event.source = InputDevice.SOURCE_TOUCHSCREEN
        return try { inject.invoke(manager, event, 0) == true } finally { event.recycle() }
    }
}
