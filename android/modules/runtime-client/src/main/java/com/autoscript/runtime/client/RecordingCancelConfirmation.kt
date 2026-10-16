package com.autoscript.runtime.client

import com.autoscript.runtime.api.RuntimeProtocol
import java.util.concurrent.atomic.AtomicReference

/** A rejected/failed/late cancel must not release the lease and prevent a retry. */
internal fun <T : Any> confirmRecordingCancel(
    leases: AtomicReference<T?>, lease: T, sessionCurrent: () -> Boolean, cancel: () -> Int,
): Result<Unit> = runCatching {
    check(leases.get() === lease && sessionCurrent()) { "录制会话已变化，未确认取消" }
    when (val result = cancel()) {
        RuntimeProtocol.CONTROL_ACCEPTED -> Unit
        RuntimeProtocol.CONTROL_SESSION_MISMATCH -> error("Runner会话已变化，未确认取消")
        RuntimeProtocol.CONTROL_INVALID_STATE -> error("Runner拒绝取消：录制状态不匹配，请重试或获取结果")
        RuntimeProtocol.CONTROL_ENGINE_ERROR -> error("Runner取消录制失败，请重试")
        else -> error("Runner返回未知取消结果（$result），未确认取消")
    }
    check(sessionCurrent() && leases.compareAndSet(lease,null)) { "录制会话已变化，未确认取消" }
}
