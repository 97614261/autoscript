package com.autoscript.runtime.service

/** Canonical RFC4648, independent of Android's lenient decoder and available on API24. */
internal object StrictBase64 {
    private const val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"
    fun encode(bytes: ByteArray): String {
        require(bytes.size <= 49152)
        return buildString((bytes.size + 2) / 3 * 4) {
            var index = 0
            while (index < bytes.size) {
                val a = bytes[index++].toInt() and 255
                val b = if (index < bytes.size) bytes[index++].toInt() and 255 else -1
                val c = if (index < bytes.size) bytes[index++].toInt() and 255 else -1
                append(alphabet[a shr 2]); append(alphabet[((a and 3) shl 4) or (if (b < 0) 0 else b shr 4)])
                append(if (b < 0) '=' else alphabet[((b and 15) shl 2) or (if (c < 0) 0 else c shr 6)])
                append(if (c < 0) '=' else alphabet[c and 63])
            }
        }
    }
    fun decode(value: String): ByteArray {
        require(value.length <= 65536 && value.length % 4 == 0)
        if (value.isEmpty()) return ByteArray(0)
        val padding = value.takeLastWhile { it == '=' }.length
        require(padding <= 2)
        val result = ByteArray(value.length / 4 * 3 - padding)
        var output = 0
        for (index in value.indices step 4) {
            val digits = IntArray(4) { offset ->
                val position = index + offset
                if (value[position] == '=' && position >= value.length - padding) 0
                else alphabet.indexOf(value[position]).also { require(it >= 0) }
            }
            val bits = (digits[0] shl 18) or (digits[1] shl 12) or (digits[2] shl 6) or digits[3]
            for (shift in intArrayOf(16,8,0)) if (output < result.size) result[output++] = (bits shr shift).toByte()
        }
        require(encode(result) == value) // Reject unused nonzero tail bits and misplaced padding.
        return result
    }
}
