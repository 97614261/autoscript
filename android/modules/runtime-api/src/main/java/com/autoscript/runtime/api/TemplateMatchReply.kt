package com.autoscript.runtime.api

import android.os.Parcel
import android.os.Parcelable

/** One-shot offline preview, never a stream of screen frames over Binder. */
data class TemplateMatchReply(val status: Int, val x: Int, val y: Int, val scorePermille: Int) : Parcelable {
    private constructor(parcel: Parcel) : this(parcel.readInt(), parcel.readInt(), parcel.readInt(), parcel.readInt())
    override fun writeToParcel(parcel: Parcel, flags: Int) {
        parcel.writeInt(status)
        parcel.writeInt(x)
        parcel.writeInt(y)
        parcel.writeInt(scorePermille)
    }
    override fun describeContents(): Int = 0
    companion object {
        const val MATCH = 0
        const val NOT_FOUND = 1
        const val INVALID_IMAGE = 2
        const val BUSY = 3
        const val SESSION_MISMATCH = 4
        const val BUDGET_EXCEEDED = 5
        @JvmField val CREATOR: Parcelable.Creator<TemplateMatchReply> = object : Parcelable.Creator<TemplateMatchReply> {
            override fun createFromParcel(parcel: Parcel) = TemplateMatchReply(parcel)
            override fun newArray(size: Int): Array<TemplateMatchReply?> = arrayOfNulls(size)
        }
    }
}
