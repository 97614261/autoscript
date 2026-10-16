package com.autoscript.studio

import com.autoscript.runtime.api.RecognitionPreviewReply as Reply

/** At most one proof per dialog; it is a sample, not a guarantee about a later screen. */
internal class TestedImageRecognition<T : Any>(private val source: T, private val operation: Int,
    options: IntArray, private val resource: String, private val reply: Reply) {
    private val options = options.copyOf()
    fun matches(source: T, operation: Int, options: IntArray, resource: String, current: (Reply) -> Boolean): Boolean =
        this.source === source && this.operation == operation && recognitionReplyOperationMatches(operation,reply.operation) &&
            this.options.contentEquals(options) && this.resource == resource && reply.status in setOf(Reply.SUCCESS, Reply.NOT_FOUND) && current(reply)
}
