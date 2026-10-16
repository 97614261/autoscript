package com.autoscript.runtime.client

import com.autoscript.runtime.api.RecordingReply
import java.util.concurrent.atomic.AtomicReference

internal fun acceptsRecordingCompletion(reply: RecordingReply, id: Long, generation: Long, sessionCurrent: Boolean): Boolean =
    sessionCurrent && reply.recordingId == id && reply.generation == generation &&
        reply.status != RecordingReply.BUSY && reply.valid()

internal fun <T> takeRecordingCompletion(leases: AtomicReference<T?>, current: T, reply: RecordingReply,
    id: Long, generation: Long, sessionCurrent: Boolean): RecordingReply? {
    if (!acceptsRecordingCompletion(reply, id, generation, sessionCurrent)) return null
    return reply.takeIf { leases.compareAndSet(current, null) }
}
