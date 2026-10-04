package com.autoscript.runtime.api

import android.os.Parcel
import android.os.Parcelable

data class RuntimeDebugValue(
    val scope: String,
    val name: String,
    val type: String,
    val value: String,
    val truncated: Boolean = false,
    val declaredType: String = "",
) {
    init {
        require(scope in setOf("local", "global") && name.toByteArray(Charsets.UTF_8).size in 1..128 && type in setOf("nil", "boolean", "integer", "number", "string", "unsupported"))
        require(value.length <= 1024)
        require(declaredType in setOf("", "boolean", "integer", "number", "string", "image"))
        require(when (type) {
            "nil" -> value.isEmpty()
            "boolean" -> value == "true" || value == "false"
            "integer" -> value.toLongOrNull() != null
            "number" -> value.toDoubleOrNull()?.isFinite() == true
            else -> true
        })
    }
}

/** Bounded snapshot explicitly requested by Studio, not a script/user log channel. */
data class RuntimeDebugReply(
    val status: Int,
    val flowId: String,
    val nodeId: String,
    val variables: List<RuntimeDebugValue>,
    val runtimeState: Int = 0,
    val sessionGeneration: Long = 0,
    val runRevision: Long = 0,
    val variablesTruncated: Boolean = false,
) : Parcelable {
    init {
        require(status in SUCCESS..BUSY && flowId.toByteArray(Charsets.UTF_8).size <= 128 && nodeId.toByteArray(Charsets.UTF_8).size <= 128 && variables.size <= 128)
        require(runtimeState in 0..RuntimeProtocol.STATE_PAUSED && sessionGeneration >= 0 && runRevision >= 0)
    }
    private constructor(parcel: Parcel) : this(parcel.readInt(), parcel.readString().orEmpty(), parcel.readString().orEmpty(), readValues(parcel),
        parcel.readInt(), parcel.readLong(), parcel.readLong(), parcel.readInt() != 0)
    override fun writeToParcel(parcel: Parcel, flags: Int) {
        parcel.writeInt(status); parcel.writeString(flowId); parcel.writeString(nodeId); parcel.writeInt(variables.size)
        variables.forEach { value ->
            parcel.writeString(value.scope); parcel.writeString(value.name); parcel.writeString(value.type); parcel.writeString(value.value)
            parcel.writeInt(if (value.truncated) 1 else 0); parcel.writeString(value.declaredType)
        }
        parcel.writeInt(runtimeState); parcel.writeLong(sessionGeneration); parcel.writeLong(runRevision)
        parcel.writeInt(if (variablesTruncated) 1 else 0)
    }
    override fun describeContents(): Int = 0
    companion object {
        const val SUCCESS = 0
        const val UNAVAILABLE = 1
        const val SESSION_MISMATCH = 2
        const val BUSY = 3
        fun unavailable(status: Int = UNAVAILABLE) = RuntimeDebugReply(status, "", "", emptyList())
        private fun readValues(parcel: Parcel): List<RuntimeDebugValue> {
            val size = parcel.readInt()
            require(size in 0..128)
            return List(size) { RuntimeDebugValue(parcel.readString().orEmpty(), parcel.readString().orEmpty(), parcel.readString().orEmpty(), parcel.readString().orEmpty(), parcel.readInt() != 0, parcel.readString().orEmpty()) }
        }
        @JvmField val CREATOR: Parcelable.Creator<RuntimeDebugReply> = object : Parcelable.Creator<RuntimeDebugReply> {
            override fun createFromParcel(parcel: Parcel) = RuntimeDebugReply(parcel)
            override fun newArray(size: Int): Array<RuntimeDebugReply?> = arrayOfNulls(size)
        }
    }
}
