package com.autoscript.runtime.api

import android.os.Parcel
import android.os.Parcelable

data class VisualCompileReply(
    val status: Int,
    val generationId: String?,
    val code: String?,
    val diagnostic: String?,
    val flowId: String?,
    val nodeId: String?,
    val line: Int,
) : Parcelable {
    private constructor(parcel: Parcel) : this(
        status = parcel.readInt(),
        generationId = parcel.readString(),
        code = parcel.readString(),
        diagnostic = parcel.readString(),
        flowId = parcel.readString(),
        nodeId = parcel.readString(),
        line = parcel.readInt(),
    )

    override fun writeToParcel(parcel: Parcel, flags: Int) {
        parcel.writeInt(status)
        parcel.writeString(generationId)
        parcel.writeString(code)
        parcel.writeString(diagnostic)
        parcel.writeString(flowId)
        parcel.writeString(nodeId)
        parcel.writeInt(line)
    }

    override fun describeContents(): Int = 0

    companion object {
        @JvmField
        val CREATOR: Parcelable.Creator<VisualCompileReply> =
            object : Parcelable.Creator<VisualCompileReply> {
                override fun createFromParcel(parcel: Parcel): VisualCompileReply =
                    VisualCompileReply(parcel)

                override fun newArray(size: Int): Array<VisualCompileReply?> = arrayOfNulls(size)
            }
    }
}
