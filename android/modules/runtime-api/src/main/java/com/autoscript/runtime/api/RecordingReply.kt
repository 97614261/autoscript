package com.autoscript.runtime.api

import android.os.Parcel
import android.os.Parcelable
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Low-frequency input actions, not screenshots. Coordinates are original logical display pixels. */
data class RecordingReply(
    val recordingId: Long, val generation: Long, val status: Int,
    val width: Int = 0, val height: Int = 0, val rotation: Int = 0,
    val elapsedMs: Int = 0, val actions: ByteArray = ByteArray(0), val skippedGestures: Int = 0,
) : Parcelable {
    private constructor(parcel: Parcel) : this(parcel.readLong(), parcel.readLong(), parcel.readInt(),
        parcel.readInt(), parcel.readInt(), parcel.readInt(), parcel.readInt(), readActions(parcel), parcel.readInt()) {
        require(valid()) { "RECORDING_REPLY_INVALID" }
    }
    fun valid(): Boolean {
        if (recordingId <= 0 || generation == 0L || status !in SUCCESS..ENGINE_ERROR || rotation !in 0..3 ||
            elapsedMs !in 0..60_000 || actions.size > MAX_ACTION_BYTES || actions.size % ACTION_BYTES != 0) return false
        if (skippedGestures !in 0..512 || skippedGestures + actions.size / ACTION_BYTES > 512) return false
        if (status != SUCCESS) return actions.isEmpty() && skippedGestures == 0
        if (width !in 1..8192 || height !in 1..8192 || width.toLong() * height > 33_554_432) return false
        val data = ByteBuffer.wrap(actions).order(ByteOrder.LITTLE_ENDIAN)
        var previousEnd = 0L
        while (data.hasRemaining()) {
            val kind = data.int; val start = data.int; val duration = data.int
            val x1 = data.int; val y1 = data.int; val x2 = data.int; val y2 = data.int; val key = data.int
            val end = start.toLong() + duration
            if (start < previousEnd || duration !in 0..5000 || end > elapsedMs || kind !in 1..4) return false
            if (kind == 4) {
                if (key !in KEY_CODES || duration != 0 || listOf(x1,y1,x2,y2).any { it != 0 }) return false
            } else if (key != 0 || duration < 1 || (kind != 1 && duration < 50) ||
                x1 !in 0 until width || x2 !in 0 until width || y1 !in 0 until height || y2 !in 0 until height ||
                (kind != 3 && (x1 != x2 || y1 != y2))) return false
            previousEnd = end
        }
        return true
    }
    override fun writeToParcel(parcel: Parcel, flags: Int) {
        require(valid()) { "RECORDING_REPLY_INVALID" }
        parcel.writeLong(recordingId); parcel.writeLong(generation); parcel.writeInt(status)
        parcel.writeInt(width); parcel.writeInt(height); parcel.writeInt(rotation); parcel.writeInt(elapsedMs)
        parcel.writeInt(actions.size); parcel.writeByteArray(actions); parcel.writeInt(skippedGestures)
    }
    override fun describeContents(): Int = 0
    companion object {
        const val SUCCESS = 0
        const val UNSUPPORTED_DEVICE = 1
        const val MULTI_TOUCH = 2
        const val BUDGET_EXCEEDED = 3
        const val CANCELLED = 4
        const val TIMEOUT = 5
        const val INCOMPLETE_TOUCH = 6
        const val INVALID_EVENT = 7
        const val UNSUPPORTED_GESTURE = 8
        const val SESSION_MISMATCH = 9
        const val BUSY = 10
        const val ENGINE_ERROR = 11
        const val ACTION_BYTES = 32
        const val MAX_ACTION_BYTES = 512 * ACTION_BYTES
        val KEY_CODES: Set<Int> = setOf(3,4,24,25,66,82)
        private fun readActions(parcel: Parcel): ByteArray {
            val size = parcel.readInt()
            require(size in 0..MAX_ACTION_BYTES && size % ACTION_BYTES == 0 && parcel.dataAvail() >= size + 4) { "RECORDING_BUDGET" }
            return ByteArray(size).also(parcel::readByteArray)
        }
        /** Native header: token/u64, count/u32, elapsed/u32, status/u32, skipped/u32. */
        fun fromNative(bytes: ByteArray, id: Long, generation: Long, width: Int, height: Int, rotation: Int): RecordingReply {
            require(bytes.size in 24..24 + MAX_ACTION_BYTES) { "RECORDING_BUDGET" }
            val data = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            require(data.long == id) { "RECORDING_SESSION_MISMATCH" }
            val count = data.int; val elapsed = data.int; val status = data.int
            val skipped = data.int
            require(count in 0..512 && skipped in 0..512 && bytes.size == 24 + count * ACTION_BYTES && status in SUCCESS..UNSUPPORTED_GESTURE)
            return RecordingReply(id,generation,status,width,height,rotation,elapsed,bytes.copyOfRange(24,bytes.size),skipped).also { require(it.valid()) }
        }
        @JvmField val CREATOR: Parcelable.Creator<RecordingReply> = object : Parcelable.Creator<RecordingReply> {
            override fun createFromParcel(parcel: Parcel) = RecordingReply(parcel)
            override fun newArray(size: Int): Array<RecordingReply?> = arrayOfNulls(size)
        }
    }
}
