package com.autoscript.runtime.api

import android.os.Parcel
import android.os.Parcelable

/** Bounded one-shot recognition; image data is never returned over Binder. */
data class RecognitionPreviewReply(
    val requestId: Long,
    val generation: Long,
    val status: Int,
    val operation: Int,
    val text: String = "",
    val x: Int = -1,
    val y: Int = -1,
    val scorePermille: Int = 0,
    val coveragePermille: Int = 0,
) : Parcelable {
    private constructor(parcel: Parcel) : this(parcel.readLong(), parcel.readLong(), parcel.readInt(),
        parcel.readInt(), parcel.readString().orEmpty(), parcel.readInt(), parcel.readInt(), parcel.readInt(), parcel.readInt())
    override fun writeToParcel(parcel: Parcel, flags: Int) {
        parcel.writeLong(requestId); parcel.writeLong(generation); parcel.writeInt(status); parcel.writeInt(operation)
        parcel.writeString(text); parcel.writeInt(x); parcel.writeInt(y); parcel.writeInt(scorePermille); parcel.writeInt(coveragePermille)
    }
    override fun describeContents(): Int = 0
    companion object {
        const val SUCCESS = 0
        const val NOT_FOUND = 1
        const val INVALID_INPUT = 2
        const val BUSY = 3
        const val SESSION_MISMATCH = 4
        const val BUDGET_EXCEEDED = 5
        const val CANCELLED = 6
        const val TIMEOUT = 7
        const val ENGINE_ERROR = 8
        const val TEMPLATE = 1
        const val GRAY_TEMPLATE = 2
        const val GLYPH = 3
        const val ALPHANUMERIC = 4
        const val GLYPH_DETAILS = 5
        const val SCALED_TEMPLATE = 6
        const val FEATURE_TEMPLATE = 7
        const val ALPHANUMERIC_V5 = 8
        const val LATIN_TEXT = 9
        const val OPTIONS_SIZE = 8
        const val MAX_TEXT_LENGTH = 65_536
        /** Both fixed OCR models need only the source frame; all other previews need a target. */
        fun requiresTarget(operation: Int) = operation !in setOf(ALPHANUMERIC, ALPHANUMERIC_V5, LATIN_TEXT)
        /** Half-open L/T/R/B, threshold, RGB, tolerance, then pixel direction or glyph space gap. */
        fun validOptions(operation: Int, options: IntArray): Boolean = operation in TEMPLATE..LATIN_TEXT &&
            (if(operation in setOf(GLYPH,GLYPH_DETAILS)) options.size in 8..38 && options.size%2==0 else options.size == (if(operation==SCALED_TEMPLATE) 11 else OPTIONS_SIZE)) && options[0] >= 0 && options[1] >= 0 && options[2] > options[0] &&
            options[3] > options[1] && (options[2].toLong() - options[0]) * (options[3].toLong() - options[1]) <= (if(operation==FEATURE_TEMPLATE) 1_048_576 else 4_194_304) &&
            options[4] in 0..1000 && options[5] in 0..0xFFFFFF && options[6] in 0..255 &&
            options[7] in (if (operation in setOf(TEMPLATE,SCALED_TEMPLATE)) 0..4 else 0..65535) &&
            (operation !in setOf(GLYPH,GLYPH_DETAILS) || validGlyphColors(options)) &&
            (operation!=SCALED_TEMPLATE || (options[8] in 250..4000 && options[9] in options[8]..4000 && options[10] in 1..1000 &&
                (options[9]-options[8])%options[10]==0 && (options[9]-options[8])/options[10]+1<=16))
        private fun validGlyphColors(options: IntArray): Boolean {
            val seen = mutableSetOf(options[5])
            for (index in 8 until options.size step 2) {
                if (options[index] !in 0..0xFFFFFF || options[index+1] !in 0..255 || !seen.add(options[index])) return false
            }
            return (options[2].toLong()-options[0])*(options[3].toLong()-options[1])*(1+(options.size-8)/2) <= 64_000_000L
        }
        @JvmField val CREATOR: Parcelable.Creator<RecognitionPreviewReply> = object : Parcelable.Creator<RecognitionPreviewReply> {
            override fun createFromParcel(parcel: Parcel) = RecognitionPreviewReply(parcel)
            override fun newArray(size: Int): Array<RecognitionPreviewReply?> = arrayOfNulls(size)
        }
    }
}
