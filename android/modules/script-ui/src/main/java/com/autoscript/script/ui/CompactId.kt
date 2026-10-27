package com.autoscript.script.ui

import java.security.SecureRandom

/** Compact project identities and bounded helper tokens. Existing IDs are never rewritten. */
object CompactId {
    private val random = SecureRandom()

    /** 96 bits of entropy encoded as 16 URL-safe ASCII characters. */
    fun token(): String {
        val bytes = ByteArray(12)
        random.nextBytes(bytes)
        val output = CharArray(16)
        var buffer = 0
        var bits = 0
        var index = 0
        for (byte in bytes) {
            buffer = (buffer shl 8) or (byte.toInt() and 0xff)
            bits += 8
            while (bits >= 6) {
                bits -= 6
                output[index++] = ALPHABET[(buffer ushr bits) and 0x3f]
            }
        }
        return String(output)
    }

    fun next(prefix: String): String {
        require(prefix.matches(Regex("[A-Za-z][A-Za-z0-9_-]{0,15}"))) { "非法 ID 前缀" }
        return "$prefix-${token()}"
    }

    /** Allocates the next numeric suffix without reusing a larger historical suffix. */
    fun nextNumeric(prefix: String, used: Collection<String>): String {
        require(prefix.matches(Regex("[A-Za-z][A-Za-z0-9_-]{0,15}"))) { "非法 ID 前缀" }
        val pattern = Regex("^${Regex.escape(prefix)}-([0-9]+)$")
        val max = used.asSequence().mapNotNull { pattern.matchEntire(it)?.groupValues?.get(1)?.toLongOrNull() }
            .maxOrNull() ?: 0L
        require(max < Int.MAX_VALUE.toLong()) { "${prefix} 数字 ID 已达到上限" }
        val next = max + 1L
        return "$prefix-$next"
    }

    private const val ALPHABET = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz-_"
}
