package com.autoscript.runtime.api

import android.os.Parcel
import android.os.Parcelable

data class ScriptValidationReply(
    val status: Int,
    val diagnostic: String?,
) : Parcelable {
    private constructor(parcel: Parcel) : this(
        status = parcel.readInt(),
        diagnostic = parcel.readString(),
    )

    override fun writeToParcel(parcel: Parcel, flags: Int) {
        parcel.writeInt(status)
        parcel.writeString(diagnostic)
    }

    override fun describeContents(): Int = 0

    companion object {
        @JvmField
        val CREATOR: Parcelable.Creator<ScriptValidationReply> =
            object : Parcelable.Creator<ScriptValidationReply> {
                override fun createFromParcel(parcel: Parcel): ScriptValidationReply =
                    ScriptValidationReply(parcel)

                override fun newArray(size: Int): Array<ScriptValidationReply?> = arrayOfNulls(size)
            }
    }
}
