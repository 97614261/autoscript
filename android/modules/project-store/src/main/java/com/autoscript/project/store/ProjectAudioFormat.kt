package com.autoscript.project.store

/** Format classification only; MediaPlayer validates the complete stream at playback. */
fun projectAudioExtension(bytes: ByteArray): String? {
    fun ascii(offset: Int, value: String) = bytes.size >= offset + value.length &&
        value.indices.all { bytes[offset + it].toInt() == value[it].code }
    return when {
        ascii(0, "RIFF") && ascii(8, "WAVE") -> ".wav"
        ascii(0, "OggS") -> ".ogg"
        ascii(0, "fLaC") -> ".flac"
        ascii(0, "ID3") && bytes.size >= 10 && bytes[3].toInt() in 2..4 -> ".mp3"
        bytes.size >= 2 && (bytes[0].toInt() and 255) == 255 && (bytes[1].toInt() and 224) == 224 -> ".mp3"
        ascii(4, "ftyp") && (ascii(8, "M4A ") || ascii(8, "isom") || ascii(8, "mp42")) -> ".m4a"
        else -> null
    }
}
