package com.ivy.commands.assistant

import com.ivy.commands.command.Command
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * The [Command]s as Gemini function declarations.
 */
object AssistantTools {
    private val numberArguments = setOf("amount", "to_amount")
    private val integerArguments = setOf("limit")
    private val listArguments = setOf("tags")
    private val transactionTypes = listOf("expense", "income", "transfer")
    private val requiredArguments = mapOf(
        Command.AddTransaction to listOf("amount"),
        Command.DeleteTransaction to listOf("id"),
    )

    val commands: List<Command> = Command.entries.filter { it != Command.Help }

    val declarations: JsonArray = buildJsonArray {
        addJsonObject {
            putJsonArray("functionDeclarations") {
                commands.forEach { add(declaration(it)) }
            }
        }
    }

    private fun declaration(command: Command): JsonObject = buildJsonObject {
        put("name", command.method)
        put("description", command.description)
        if (command.arguments.isNotEmpty()) {
            putJsonObject("parameters") {
                put("type", "object")
                putJsonObject("properties") {
                    command.arguments.forEach { (name, description) ->
                        putJsonObject(name) { argument(name, description) }
                    }
                }
                requiredArguments[command]?.let { required ->
                    putJsonArray("required") { required.forEach { add(it) } }
                }
            }
        }
    }

    private fun JsonObjectBuilder.argument(name: String, description: String) {
        when (name) {
            in numberArguments -> put("type", "number")
            in integerArguments -> put("type", "integer")
            in listArguments -> {
                put("type", "array")
                putJsonObject("items") { put("type", "string") }
            }

            "type" -> {
                put("type", "string")
                putJsonArray("enum") { transactionTypes.forEach { add(it) } }
            }

            else -> put("type", "string")
        }
        put("description", description)
    }
}
