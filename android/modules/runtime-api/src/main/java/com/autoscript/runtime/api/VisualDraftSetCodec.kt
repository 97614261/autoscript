package com.autoscript.runtime.api

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction

/** ADR 0060: bounded, versioned overlays; no project paths or executable parsing. */
object VisualDraftSetCodec {
    const val MAX_BYTES = 20 * 1024 * 1024
    private const val MAX_SOURCE_BYTES = 1024 * 1024
    fun encode(drafts: Map<String, ByteArray>): ByteArray {
        require(drafts.size in 1..16)
        val output = ByteArrayOutputStream()
        DataOutputStream(output).use { writer ->
            writer.writeBytes("ZGDS"); writer.writeInt(1); writer.writeInt(drafts.size)
            for ((id, source) in drafts.toSortedMap()) {
                require(id.length in 1..64 && id.all { it in 'A'..'Z' || it in 'a'..'z' || it in '0'..'9' || it in "_.-" })
                require(source.size <= MAX_SOURCE_BYTES)
                Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(source))
                val identity = id.toByteArray(Charsets.UTF_8)
                require(output.size().toLong() + 8 + identity.size + source.size <= MAX_BYTES)
                writer.writeInt(identity.size); writer.write(identity)
                writer.writeInt(source.size); writer.write(source)
            }
        }
        return output.toByteArray()
    }
}
