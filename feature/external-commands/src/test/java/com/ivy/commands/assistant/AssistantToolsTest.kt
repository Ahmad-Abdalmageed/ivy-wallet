package com.ivy.commands.assistant

import com.ivy.commands.command.Command
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Test

class AssistantToolsTest {
    private val declarations = AssistantTools.declarations[0].jsonObject["functionDeclarations"]!!.jsonArray
        .map { it.jsonObject }
        .associateBy { it["name"]!!.jsonPrimitive.content }

    @Test
    fun `declares every command except help`() {
        declarations.keys shouldBe Command.entries.filter { it != Command.Help }.map { it.method }.toSet()
        declarations.keys.containsAll(listOf("add_budget", "add_planned_payment", "update_transaction")) shouldBe
            true
        declarations.getValue("list_accounts")["parameters"] shouldBe null
    }

    @Test
    fun `typed parameters`() {
        val parameters = declarations.getValue("add_transaction")["parameters"]!!.jsonObject
        val properties = parameters["properties"]!!.jsonObject

        properties.type("amount") shouldBe "number"
        properties.type("account") shouldBe "string"
        properties.type("tags") shouldBe "array"
        properties["type"]!!.jsonObject["enum"]!!.jsonArray.map { it.jsonPrimitive.content } shouldBe
            listOf("expense", "income", "transfer")
        parameters["required"]!!.jsonArray.map { it.jsonPrimitive.content } shouldBe listOf("amount")
        declarations.getValue("list_transactions")["parameters"]!!.jsonObject["properties"]!!.jsonObject
            .type("limit") shouldBe "integer"
        val budget = declarations.getValue("add_budget")["parameters"]!!.jsonObject
        budget["properties"]!!.jsonObject.type("categories") shouldBe "array"
        budget["required"]!!.jsonArray.map { it.jsonPrimitive.content } shouldBe listOf("name", "amount")
        declarations.getValue("add_account")["parameters"]!!.jsonObject["properties"]!!.jsonObject
            .type("include_in_balance") shouldBe "boolean"
    }

    private fun JsonObject.type(name: String): String = this[name]!!.jsonObject["type"]!!.jsonPrimitive.content
}
