package com.autoscript.runtime.api

import android.os.Parcel
import android.os.Parcelable

enum class InputPointAction(val wire: String, val title: String, val needsPointer: Boolean, val needsEnd: Boolean = false) {
    TAP("tap", "单击", false),
    SWIPE("swipe", "滑动", false, true),
    LONG_PRESS("longPress", "长按", true),
    DRAG("drag", "拖动", true, true),
    DOWN("down", "按下", true),
    MOVE("move", "移动", true),
    UP("up", "弹起", true);

    val needsDuration: Boolean get() = this == SWIPE || this == LONG_PRESS || this == DRAG
    fun available(features: Int): Boolean = features and RuntimeProtocol.INPUT_FEATURE_BASIC != 0 &&
        (!needsPointer || features and RuntimeProtocol.INPUT_FEATURE_SINGLE_POINTER != 0)

    companion object {
        fun fromWire(value: String?): InputPointAction? = entries.firstOrNull { it.wire == value }
    }
}

/** One bounded selection result, never a screen frame or executable payload. */
data class InputPointPickReply(
    val requestId: Long,
    val status: Int,
    val action: String,
    val width: Int = 0,
    val height: Int = 0,
    val x1: Int = 0,
    val y1: Int = 0,
    val x2: Int = 0,
    val y2: Int = 0,
    val durationMs: Int = 800,
    val message: String = "",
) : Parcelable {
    fun validFor(expectedRequestId: Long, features: Int): Boolean {
        val selected = InputPointAction.fromWire(action) ?: return false
        if (requestId <= 0 || requestId != expectedRequestId || status != SUCCESS || !selected.available(features)) return false
        if (width !in 1..16384 || height !in 1..16384 || message.length > 160) return false
        if (selected != InputPointAction.UP && (x1 !in 0 until width || y1 !in 0 until height)) return false
        if (selected.needsEnd && (x2 !in 0 until width || y2 !in 0 until height)) return false
        return !selected.needsDuration || durationMs in 100..5000
    }

    private constructor(p: Parcel) : this(
        p.readLong(), p.readInt(), p.readString().orEmpty(), p.readInt(), p.readInt(),
        p.readInt(), p.readInt(), p.readInt(), p.readInt(), p.readInt(), p.readString().orEmpty(),
    )
    override fun writeToParcel(p: Parcel, flags: Int) {
        p.writeLong(requestId)
        p.writeInt(status)
        p.writeString(action)
        listOf(width, height, x1, y1, x2, y2, durationMs).forEach(p::writeInt)
        p.writeString(message)
    }
    override fun describeContents(): Int = 0

    companion object {
        const val SUCCESS = 0
        const val CANCELLED = 1
        const val FAILED = 2
        @JvmField val CREATOR: Parcelable.Creator<InputPointPickReply> = object : Parcelable.Creator<InputPointPickReply> {
            override fun createFromParcel(p: Parcel) = InputPointPickReply(p)
            override fun newArray(size: Int): Array<InputPointPickReply?> = arrayOfNulls(size)
        }
    }
}
