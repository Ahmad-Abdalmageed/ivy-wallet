package com.ivy.commands.assistant

import arrow.core.Either
import arrow.core.raise.either
import com.ivy.base.time.TimeProvider
import com.ivy.commands.command.Command
import com.ivy.commands.command.CommandArgs
import com.ivy.commands.command.CommandProcessor
import com.ivy.data.model.TransactionId
import com.ivy.data.repository.AccountRepository
import com.ivy.data.repository.CategoryRepository
import com.ivy.data.repository.CurrencyRepository
import com.ivy.data.repository.TagRepository
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.time.format.DateTimeFormatter
import java.util.Base64
import java.util.Locale
import java.util.UUID
import javax.inject.Inject

/**
 * Something the assistant changed, shown in the chat.
 */
sealed interface AssistantAction {
    /** A transaction was added; [transaction] is the `add_transaction` result. */
    data class Added(val transaction: JsonObject) : AssistantAction

    data class Deleted(val transaction: TransactionId) : AssistantAction
}

/**
 * A conversation with Gemini that runs the [Command]s it asks for, through the same
 * [CommandProcessor] as the external commands, until it replies with text.
 * The conversation lives as long as the agent.
 */
class AssistantAgent @Inject constructor(
    private val gemini: GeminiClient,
    private val processor: CommandProcessor,
    private val accountRepository: AccountRepository,
    private val categoryRepository: CategoryRepository,
    private val tagRepository: TagRepository,
    private val currencyRepository: CurrencyRepository,
    private val timeProvider: TimeProvider,
) {
    private val contents = mutableListOf<JsonObject>()
    private val notes = mutableListOf<String>()

    fun reset() {
        contents.clear()
        notes.clear()
    }

    /**
     * Tells the model, with the next message, that the user undid a transaction.
     */
    fun noteUndo(transaction: TransactionId) {
        notes += "The user undid (deleted) the transaction with id ${transaction.value}."
    }

    /**
     * @param image a JPEG photo, e.g. of a receipt, sent with the message
     * @return the assistant's reply
     */
    suspend fun send(
        message: String,
        image: ByteArray?,
        config: AssistantConfig,
        onAction: suspend (AssistantAction) -> Unit,
    ): Either<String, String> {
        val start = contents.size
        val actions = mutableListOf<String>()
        contents += userMessage(withNotes(message.ifBlank { RECEIPT_REQUEST }), image)
        val result = either {
            val system = systemPrompt()
            repeat(MAX_STEPS) {
                val reply = gemini.generate(config, system, contents, AssistantTools.declarations).bind()
                contents += reply
                val calls = reply.functionCalls()
                if (calls.isEmpty()) return@either reply.text()
                contents += functionResponses(calls) { action ->
                    actions += action.toString()
                    onAction(action)
                }
            }
            raise("The assistant needed too many steps. Try a shorter request.")
        }
        result.onLeft {
            // Keep the conversation valid for the next message.
            while (contents.size > start) contents.removeAt(contents.lastIndex)
            if (actions.isNotEmpty()) notes += "Already done for the previous message: ${actions.joinToString()}."
        }
        return result
    }

    private fun withNotes(message: String): String {
        if (notes.isEmpty()) return message
        val text = notes.joinToString(separator = "\n", prefix = "[", postfix = "]\n") + message
        notes.clear()
        return text
    }

    private suspend fun functionResponses(
        calls: List<JsonObject>,
        onAction: suspend (AssistantAction) -> Unit,
    ): JsonObject {
        val responses = calls.map { call ->
            val name = call["name"]?.jsonPrimitive?.contentOrNull.orEmpty()
            val args = call["args"] as? JsonObject ?: JsonObject(emptyMap())
            val result = processor.execute(name, CommandArgs(args))
            result.getOrNull()?.let { action(name, args, it) }?.let { onAction(it) }
            functionResponse(call, name, result)
        }
        return buildJsonObject {
            put("role", "user")
            put("parts", JsonArray(responses))
        }
    }

    private fun functionResponse(
        call: JsonObject,
        name: String,
        result: Either<String, JsonElement>,
    ): JsonObject = buildJsonObject {
        putJsonObject("functionResponse") {
            call["id"]?.let { put("id", it) }
            put("name", name)
            putJsonObject("response") {
                result.fold(
                    ifLeft = { put("error", it) },
                    ifRight = { put("result", it) },
                )
            }
        }
    }

    private fun action(name: String, args: JsonObject, result: JsonElement): AssistantAction? =
        when (name) {
            Command.AddTransaction.method -> (result as? JsonObject)?.let { AssistantAction.Added(it) }
            Command.DeleteTransaction.method -> args["id"]?.jsonPrimitive?.contentOrNull
                ?.let { runCatching { UUID.fromString(it) }.getOrNull() }
                ?.let { AssistantAction.Deleted(TransactionId(it)) }

            else -> null
        }

    private suspend fun systemPrompt(): String {
        val today = timeProvider.localDateNow()
        val accounts = accountRepository.findAll().joinToString { "${it.name.value} (${it.asset.code})" }
        val categories = categoryRepository.findAll().joinToString { it.name.value }
        val tags = tagRepository.findAll().joinToString { it.name.value }
        return """
            You are the assistant inside Ivy Wallet, the user's money manager app. You record and look up
            the user's transactions with the tools.

            Today is ${today.format(DateTimeFormatter.ofPattern("EEEE, yyyy-MM-dd", Locale.ENGLISH))}.
            Base currency: ${currencyRepository.getBaseCurrency().code}.
            The phone's language: ${Locale.getDefault().getDisplayLanguage(Locale.ENGLISH)}.
            Accounts: ${accounts.ifEmpty { "none" }}.
            Categories: ${categories.ifEmpty { "none" }}.
            Tags: ${tags.ifEmpty { "none" }}.

            - To record money spent, received or moved between accounts, call add_transaction once per
              transaction, with the exact account, category and tag names above.
            - Amounts are in the account's currency unless the user names another currency; then pass it
              as "currency".
            - If it isn't clear which account was used and there's more than one, ask before adding.
            - Use the category that fits best, or none. Never invent account, category or tag names.
            - Dates are YYYY-MM-DD, relative to today. Leave the date out for "now".
            - For questions about spending or income, call list_transactions with from/to dates and use
              its totals.
            - Delete a transaction only when the user asks to, using its id.
            - For a photo of a receipt or bill: add one expense for its total, titled with the
              merchant, on the receipt's date and in its currency, unless the user asks to split it.
              If you can't read the total, say so instead of guessing.
            - After changing something, say in one short sentence what was saved. Answer in the user's
              language (the phone's language when they only send a photo) and keep replies short.
        """.trimIndent()
    }

    private fun userMessage(text: String, image: ByteArray?): JsonObject = buildJsonObject {
        put("role", "user")
        putJsonArray("parts") {
            if (image != null) {
                addJsonObject {
                    putJsonObject("inlineData") {
                        put("mimeType", "image/jpeg")
                        put("data", Base64.getEncoder().encodeToString(image))
                    }
                }
            }
            addJsonObject { put("text", text) }
        }
    }

    private fun JsonObject.parts(): List<JsonObject> =
        (this["parts"] as? JsonArray).orEmpty().filterIsInstance<JsonObject>()

    private fun JsonObject.functionCalls(): List<JsonObject> =
        parts().mapNotNull { it["functionCall"] as? JsonObject }

    private fun JsonObject.text(): String = parts()
        .filter { (it["thought"] as? JsonPrimitive)?.booleanOrNull != true }
        .mapNotNull { (it["text"] as? JsonPrimitive)?.contentOrNull }
        .joinToString(separator = "")
        .trim()

    companion object {
        private const val MAX_STEPS = 10
        private const val RECEIPT_REQUEST = "Add the expenses from this receipt."
    }
}
