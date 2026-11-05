package com.ivy.commands.assistant

import com.ivy.commands.command.Command
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/**
 * The [Command]s as Gemini function declarations.
 */
object AssistantTools {
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
            put("parameters", command.parameters)
        }
    }
}
