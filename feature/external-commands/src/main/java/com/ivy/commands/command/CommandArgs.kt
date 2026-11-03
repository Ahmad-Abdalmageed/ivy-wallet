package com.ivy.commands.command

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * The arguments of a command, read leniently: numbers and booleans may be sent as strings
 * and lists as JSON arrays or comma separated strings.
 */
class CommandArgs(private val values: JsonObject) {
    fun string(name: String): String? = (values[name] as? JsonPrimitive)
        ?.contentOrNull
        ?.trim()
        ?.takeIf { it.isNotEmpty() }

    fun double(name: String): Double? = string(name)?.toDoubleOrNull()

    fun int(name: String): Int? = string(name)?.toIntOrNull()

    fun list(name: String): List<String> = when (val value = values[name]) {
        is JsonArray -> value.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
        is JsonPrimitive -> value.contentOrNull?.split(",").orEmpty()
        else -> emptyList()
    }.map(String::trim).filter(String::isNotEmpty)

    companion object {
        val Empty = CommandArgs(JsonObject(emptyMap()))
    }
}
