package com.ivy.commands.assistant

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
        declarations.keys shouldBe setOf(
            "list_accounts",
            "list_categories",
            "list_tags",
            "add_transaction",
            "list_transactions",
            "delete_transaction",
        )
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
    }

    private fun JsonObject.type(name: String): String = this[name]!!.jsonObject["type"]!!.jsonPrimitive.content
}
