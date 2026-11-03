package com.ivy.commands.command

import arrow.core.Either
import com.ivy.commands.ExternalCommandsSettings
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Before
import org.junit.Test

class ExternalCommandHandlerTest {
    private val settings = mockk<ExternalCommandsSettings>()
    private val processor = mockk<CommandProcessor>()
    private val handler = ExternalCommandHandler(settings, processor)
    private val args = slot<CommandArgs>()

    @Before
    fun setup() {
        coEvery { settings.isEnabled() } returns true
        coEvery { settings.isValidKey(any()) } returns false
        coEvery { settings.isValidKey("secret") } returns true
        coEvery { processor.execute(any(), capture(args)) } returns Either.Right(JsonPrimitive("done"))
    }

    @Test
    fun `runs the command with the JSON and extra arguments`() = runTest {
        val response = handler.handle(
            method = "add_transaction",
            arguments = """{"key": "secret", "amount": 25}""",
            extras = mapOf("account" to "Cash"),
        )

        response shouldBe """{"ok":true,"result":"done"}"""
        args.captured.double("amount") shouldBe 25.0
        args.captured.string("account") shouldBe "Cash"
    }

    @Test
    fun `the key can be an extra`() = runTest {
        handler.handle("list_accounts", arguments = null, extras = mapOf("key" to "secret")) shouldContain
            "\"ok\":true"
    }

    @Test
    fun `rejects a wrong or missing key`() = runTest {
        handler.handle("list_accounts", """{"key": "guess"}""", emptyMap()) shouldContain "Wrong or missing access key"
        handler.handle("list_accounts", null, emptyMap()) shouldContain "\"ok\":false"
        coVerify(exactly = 0) { processor.execute(any(), any()) }
    }

    @Test
    fun `rejects commands while they're off`() = runTest {
        coEvery { settings.isEnabled() } returns false

        handler.handle("list_accounts", """{"key": "secret"}""", emptyMap()) shouldContain
            "External commands are off"
    }

    @Test
    fun `help works without a key`() = runTest {
        handler.handle("help", null, emptyMap()) shouldBe """{"ok":true,"result":"done"}"""
    }

    @Test
    fun `invalid JSON`() = runTest {
        handler.handle("list_accounts", "amount=25", emptyMap()) shouldContain "must be a JSON object"
    }

    @Test
    fun `reports errors and crashes`() = runTest {
        coEvery { processor.execute("delete_transaction", any()) } returns Either.Left("Transaction 'x' not found.")
        coEvery { processor.execute("list_tags", any()) } throws IllegalStateException("db closed")

        handler.handle("delete_transaction", """{"key": "secret"}""", emptyMap()) shouldBe
            """{"ok":false,"error":"Transaction 'x' not found."}"""
        handler.handle("list_tags", """{"key": "secret"}""", emptyMap()) shouldContain "db closed"
    }
}
