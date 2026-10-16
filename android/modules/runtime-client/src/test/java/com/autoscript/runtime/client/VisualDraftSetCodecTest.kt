package com.autoscript.runtime.client

import com.autoscript.runtime.api.VisualDraftSetCodec
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.DataInputStream

class VisualDraftSetCodecTest {
    @Test fun versionAndStableOrderingMatchNativeWire() {
        val encoded = VisualDraftSetCodec.encode(linkedMapOf("z" to "[]".toByteArray(), "main" to "{}".toByteArray()))
        DataInputStream(ByteArrayInputStream(encoded)).use { input ->
            val magic = ByteArray(4); input.readFully(magic); assertEquals("ZGDS", String(magic))
            assertEquals(1, input.readInt()); assertEquals(2, input.readInt())
            for ((id, source) in listOf("main" to "{}", "z" to "[]")) {
                val identity = ByteArray(input.readInt()); input.readFully(identity); assertEquals(id, String(identity))
                val value = ByteArray(input.readInt()); input.readFully(value); assertEquals(source, String(value))
            }
            assertEquals(-1, input.read())
        }
    }
    @Test fun invalidIdentityUtf8AndBudgetsFailBeforeTransport() {
        val rejected = listOf(emptyMap(), mapOf("../escape" to byteArrayOf()), mapOf("main" to byteArrayOf(-1)),
            mapOf("main" to ByteArray(1024 * 1024 + 1)), (0..16).associate { "p$it" to byteArrayOf() })
        rejected.forEach { drafts ->
            try { VisualDraftSetCodec.encode(drafts); fail("must reject malformed set") } catch (_: IllegalArgumentException) {}
            catch (_: java.nio.charset.CharacterCodingException) {}
        }
    }
}
