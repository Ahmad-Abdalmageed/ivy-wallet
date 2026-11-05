package com.ivy.commands.command

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * The arguments of a command, read leniently: numbers and booleans may be sent as strings
 * and lists as JSON arrays or comma separated strings.
 */
class CommandArgs(private val values: JsonObject) {
    /** Whether the argument was sent, even empty, e.g. `"tags": []` to remove all tags. */
    fun has(name: String): Boolean = values[name].let { it != null && it !is JsonNull }

    fun string(name: String): String? = (values[name] as? JsonPrimitive)
        ?.contentOrNull
        ?.trim()
        ?.takeIf { it.isNotEmpty() }

    fun double(name: String): Double? = string(name)?.toDoubleOrNull()

    fun int(name: String): Int? = string(name)?.let { it.toIntOrNull() ?: it.toDoubleOrNull()?.toInt() }

    fun boolean(name: String): Boolean? = when (string(name)?.lowercase()) {
        "true", "yes", "1" -> true
        "false", "no", "0" -> false
        else -> null
    }

    fun list(name: String): List<String> = when (val value = values[name]) {
        is JsonArray -> value.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
        is JsonPrimitive -> value.contentOrNull?.split(",").orEmpty()
        else -> emptyList()
    }.map(String::trim).filter { it.isNotEmpty() && !isNone(it) }

    /**
     * A text argument that can be cleared: null when not sent, [Clear] for "none" or empty.
     */
    fun clearable(name: String): Clearable? {
        if (!has(name)) return null
        val value = string(name)
        return if (value == null || isNone(value)) Clear else Value(value)
    }

    sealed interface Clearable
    data object Clear : Clearable
    data class Value(val value: String) : Clearable

    companion object {
        val Empty = CommandArgs(JsonObject(emptyMap()))

        fun isNone(value: String): Boolean = value.trim().lowercase() in setOf("none", "null", "-")
    }
}
