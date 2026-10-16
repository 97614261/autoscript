package com.autoscript.runtime.api

import java.io.File
import java.io.FileOutputStream

/** Owned by the journal IO worker. Every retained segment uses the bounded persistent condition table. */
internal class RuntimeEventJournal(private val dir: File, private val segmentBytes: Int, private val segments: Int) {
    private val encoder = RuntimeLogEventCodec.Encoder()
    private var sequence = 0L
    private var length = 0L
    private val counts = LongArray(segments)
    private var poisoned = false

    fun write(records: List<String>): Long {
        check(!poisoned)
        return try {
            val encoded = records.map(encoder::encode)
            val definitions = encoded.mapNotNull { it.condition }
            if (definitions.isNotEmpty()) {
                val file = RuntimeLogFiles.checkedFile(dir, "conditions.jsonl")
                val bytes = (definitions.joinToString("\n") + "\n").toByteArray(Charsets.UTF_8)
                require(file.length() + bytes.size <= RuntimeLogEventCodec.CONDITION_BYTES)
                FileOutputStream(file, true).buffered().use { it.write(bytes) }
            }
            var evicted = 0L
            var stream: java.io.BufferedOutputStream? = null
            try {
                encoded.forEach { record ->
                    val bytes = (record.sample.trimEnd('\n') + "\n").toByteArray(Charsets.UTF_8)
                    require(bytes.size <= segmentBytes)
                    if (length + bytes.size > segmentBytes) {
                        stream?.close(); stream = null
                        sequence++; length = 0
                        val slot = (sequence % segments).toInt()
                        if (sequence >= segments) {
                            val old = RuntimeLogFiles.checkedFile(dir, RuntimeLogFiles.segmentName(sequence - segments))
                            check(!old.exists() || old.delete())
                            evicted += counts[slot]
                        }
                        counts[slot] = 0
                    }
                    if (stream == null) {
                        val file = RuntimeLogFiles.checkedFile(dir, RuntimeLogFiles.segmentName(sequence))
                        require(file.length() == length)
                        stream = FileOutputStream(file, true).buffered()
                    }
                    stream!!.write(bytes)
                    length += bytes.size
                    counts[(sequence % segments).toInt()]++
                }
            } finally { stream?.close() }
            evicted
        } catch (error: Exception) { poisoned = true; throw error }
    }
}
