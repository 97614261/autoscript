package com.autoscript.studio

import android.content.Context
import com.google.gson.JsonArray
import com.google.gson.JsonParser

internal data class FunctionLibraryUsage(val favorites: List<String> = emptyList(), val recent: List<String> = emptyList()) {
    fun toggle(kind: String): FunctionLibraryUsage = when {
        kind in favorites -> copy(favorites = favorites - kind)
        favorites.size < 64 -> copy(favorites = favorites + kind)
        else -> this
    }
    fun used(kind: String): FunctionLibraryUsage = copy(recent = (listOf(kind) + recent.filterNot { it == kind }).take(24))

    fun encode(): String = com.google.gson.JsonObject().apply {
        add("favorites", JsonArray().apply { favorites.take(64).forEach { add(it) } })
        add("recent", JsonArray().apply { recent.take(24).forEach { add(it) } })
    }.toString()

    companion object {
        fun decode(text: String, valid: (String) -> Boolean): FunctionLibraryUsage = runCatching {
            require(text.length <= 16_384)
            val json = JsonParser.parseString(text).asJsonObject
            fun kinds(key: String, maximum: Int): List<String> = json.getAsJsonArray(key)?.take(maximum).orEmpty()
                .mapNotNull { it.takeIf { value -> value.isJsonPrimitive && value.asJsonPrimitive.isString }?.asString }
                .filter { it.length <= 96 && valid(it) }.distinct()
            FunctionLibraryUsage(kinds("favorites", 64), kinds("recent", 24))
        }.getOrDefault(FunctionLibraryUsage())
    }
}

internal const val FUNCTION_USAGE_KEY = "usage-v1"
internal fun functionUsagePreferences(context: Context) = context.applicationContext.getSharedPreferences("function-library", Context.MODE_PRIVATE)
internal fun readFunctionUsage(context: Context): FunctionLibraryUsage = runCatching {
    FunctionLibraryUsage.decode(functionUsagePreferences(context).getString(FUNCTION_USAGE_KEY, "{}").orEmpty()) { BlockCatalog.find(it) != null }
}.getOrDefault(FunctionLibraryUsage())

internal fun updateFunctionUsage(context: Context, change: (FunctionLibraryUsage) -> FunctionLibraryUsage) {
    val next = change(readFunctionUsage(context))
    functionUsagePreferences(context).edit().putString(FUNCTION_USAGE_KEY, next.encode()).apply()
}
