package com.autoscript.runtime.api

/** Classification only: the platform decoder still validates the full audio file. */
object RuntimeBinarySignature {
    fun matches(bytes: ByteArray): Boolean {
        fun ascii(offset: Int, text: String) = bytes.size >= offset + text.length &&
            text.indices.all { bytes[offset + it].toInt() == text[it].code }
        return (bytes.size >= 8 && bytes.copyOfRange(0, 8).contentEquals(byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10))) ||
            (bytes.size >= 4 && bytes[0] == 80.toByte() && bytes[1] == 75.toByte() &&
                ((bytes[2] == 3.toByte() && bytes[3] == 4.toByte()) || (bytes[2] == 5.toByte() && bytes[3] == 6.toByte()))) ||
            (ascii(0, "RIFF") && ascii(8, "WAVE")) || ascii(0, "OggS") || ascii(0, "fLaC") ||
            (ascii(0, "ID3") && bytes.size >= 10 && bytes[3].toInt() in 2..4) ||
            (bytes.size >= 2 && (bytes[0].toInt() and 255) == 255 && (bytes[1].toInt() and 224) == 224) ||
            (ascii(4, "ftyp") && (ascii(8, "M4A ") || ascii(8, "isom") || ascii(8, "mp42")))
    }
}
