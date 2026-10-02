package com.autoscript.runtime.api

import android.os.Parcel
import android.os.Parcelable

data class RuntimeDebugValue(val scope: String, val name: String, val type: String, val value: String, val truncated: Boolean = false) {
    init {
        require(scope in setOf("local", "global") && name.length in 1..128 && type in setOf("nil", "boolean", "integer", "number", "string"))
        require(value.length <= 1024)
    }
}

/** Bounded snapshot explicitly requested by Studio, not a script/user log channel. */
data class RuntimeDebugReply(val status: Int, val flowId: String, val nodeId: String, val variables: List<RuntimeDebugValue>) : Parcelable {
    init { require(flowId.length <= 128 && nodeId.length <= 128 && variables.size <= 128) }
    private constructor(parcel: Parcel) : this(parcel.readInt(), parcel.readString().orEmpty(), parcel.readString().orEmpty(), readValues(parcel))
    override fun writeToParcel(parcel: Parcel, flags: Int) {
        parcel.writeInt(status); parcel.writeString(flowId); parcel.writeString(nodeId); parcel.writeInt(variables.size)
        variables.forEach { value ->
            parcel.writeString(value.scope); parcel.writeString(value.name); parcel.writeString(value.type); parcel.writeString(value.value)
            parcel.writeInt(if (value.truncated) 1 else 0)
        }
    }
    override fun describeContents(): Int = 0
    companion object {
        const val SUCCESS = 0
        const val UNAVAILABLE = 1
        const val SESSION_MISMATCH = 2
        fun unavailable(status: Int = UNAVAILABLE) = RuntimeDebugReply(status, "", "", emptyList())
        private fun readValues(parcel: Parcel): List<RuntimeDebugValue> {
            val size = parcel.readInt()
            require(size in 0..128)
            return List(size) { RuntimeDebugValue(parcel.readString().orEmpty(), parcel.readString().orEmpty(), parcel.readString().orEmpty(), parcel.readString().orEmpty(), parcel.readInt() != 0) }
        }
        @JvmField val CREATOR: Parcelable.Creator<RuntimeDebugReply> = object : Parcelable.Creator<RuntimeDebugReply> {
            override fun createFromParcel(parcel: Parcel) = RuntimeDebugReply(parcel)
            override fun newArray(size: Int): Array<RuntimeDebugReply?> = arrayOfNulls(size)
        }
    }
}
