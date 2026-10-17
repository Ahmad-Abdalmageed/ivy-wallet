package com.ivy.voicecommands.parser

import androidx.annotation.Keep
import arrow.core.Either
import arrow.core.raise.either
import arrow.core.raise.ensure
import arrow.core.raise.ensureNotNull
import com.ivy.base.model.TransactionType
import com.ivy.data.model.primitive.NotBlankTrimmedString
import com.ivy.data.model.primitive.PositiveDouble
import com.ivy.voicecommands.LlmConfig
import com.ivy.voicecommands.llm.LlmClient
import com.ivy.voicecommands.llm.LlmPrompt
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import java.time.LocalDate
import java.time.format.TextStyle
import java.util.Locale
import javax.inject.Inject
import kotlin.math.abs

/**
 * Parses voice commands in any language by asking an LLM to extract the transaction as JSON.
 */
class LlmVoiceTransactionParser @Inject constructor(
    private val llmClient: LlmClient,
) {
    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        isLenient = true
        coerceInputValues = true
    }

    /**
     * @return one draft per transaction mentioned in the command
     */
    suspend fun parse(
        config: LlmConfig,
        input: VoiceParserInput,
    ): Either<String, List<VoiceTransactionDraft>> = either {
        val response = llmClient.complete(config, prompt(input)).bind()
        val dtos = Either.catch { decode(extractJsonObject(response)) }
            .mapLeft { "Unexpected AI response: $response" }
            .bind()
        val drafts = dtos.mapNotNull { toDraft(it, input).getOrNull() }
        ensure(drafts.isNotEmpty()) { "The AI couldn't find an amount" }
        drafts
    }

    /**
     * Accepts {"transactions": [...]} and, from models that ignore the format, a single transaction.
     */
    private fun decode(response: String): List<LlmTransactionDto> {
        val element = json.parseToJsonElement(response).jsonObject
        return if (element.containsKey("transactions")) {
            json.decodeFromJsonElement(LlmTransactionsDto.serializer(), element).transactions.orEmpty()
        } else {
            listOf(json.decodeFromJsonElement(LlmTransactionDto.serializer(), element))
        }
    }

    fun prompt(input: VoiceParserInput): LlmPrompt {
        val categories = JsonArray(input.categories.map { JsonPrimitive(it.name.value) })
        val accounts = JsonArray(input.accounts.map { JsonPrimitive(it.name.value) })
        val weekday = input.today.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.ENGLISH)
        return LlmPrompt(
            system = """
                You turn a spoken command from the user of a personal finance app into
                transactions. The command can be in any language and can mention one or several
                transactions (e.g. "spent 50 on food and 20 on coffee" is two transactions).
                Today is ${input.today} ($weekday).
                Reply with a JSON object only: {"transactions": [...]}, one item per transaction
                in the order they were said, each with these fields:
                - "type": "EXPENSE", "INCOME" or "TRANSFER" (money moved between the user's own
                  accounts).
                - "amount": the amount as a positive number, without currency.
                - "title": a short description of what the transaction was for (at most 5 words,
                  in the language of the command), or null.
                - "category": the name of the best matching category from the list below, or null
                  if none fits or for transfers.
                - "account": the name of the account from the list below that the money was paid
                  from, received to, or transferred from, or null if not mentioned.
                - "toAccount": for TRANSFER only, the name of the account from the list below that
                  received the money, otherwise null.
                - "date": the date of the transaction as YYYY-MM-DD if the command mentions one
                  (e.g. "yesterday"), otherwise null.
                When a transaction doesn't mention its type, date or account, use the ones of the
                transaction said before it.
                Use category and account names exactly as written in the lists.
                Categories: $categories
                Accounts: $accounts
            """.trimIndent(),
            user = input.text,
            schema = transactionJsonSchema,
        )
    }

    fun toDraft(
        dto: LlmTransactionDto,
        input: VoiceParserInput,
    ): Either<String, VoiceTransactionDraft> = either {
        val amount = ensureNotNull(
            dto.amount?.let { PositiveDouble.from(abs(it)).getOrNull() }
        ) { "The AI couldn't find an amount" }
        val type = TransactionType.entries.firstOrNull { it.name == dto.type?.uppercase() }
            ?: TransactionType.EXPENSE
        val account = dto.account?.let { name ->
            NameMatcher.bestMatch(name, input.accounts) { it.name.value }
        }
        val toAccount = dto.toAccount
            ?.takeIf { type == TransactionType.TRANSFER }
            ?.let { name -> NameMatcher.bestMatch(name, input.accounts) { it.name.value } }
            ?.takeIf { it.id != account?.id }
        val category = dto.category
            ?.takeIf { type != TransactionType.TRANSFER }
            ?.let { name -> NameMatcher.bestMatch(name, input.categories) { it.name.value } }

        VoiceTransactionDraft(
            type = type,
            amount = amount,
            title = dto.title?.let { NotBlankTrimmedString.from(it).getOrNull() },
            category = category?.id,
            account = account?.id,
            toAccount = toAccount?.id,
            date = dto.date?.let { Either.catch { LocalDate.parse(it) }.getOrNull() }
                ?.takeIf { !it.isAfter(input.today) },
        )
    }

    /**
     * Models sometimes wrap the JSON in markdown code fences or add text around it.
     */
    private fun extractJsonObject(text: String): String {
        val start = text.indexOf('{')
        val end = text.lastIndexOf('}')
        return if (start in 0 until end) text.substring(start, end + 1) else text
    }
}

@Keep
@Serializable
data class LlmTransactionsDto(
    val transactions: List<LlmTransactionDto>?,
)

@Keep
@Serializable
data class LlmTransactionDto(
    val type: String?,
    val amount: Double?,
    val title: String?,
    val category: String?,
    val account: String?,
    val toAccount: String?,
    val date: String?,
)

private val transactionJsonSchema = """
    {
      "type": "object",
      "properties": {
        "transactions": {
          "type": "array",
          "items": {
            "type": "object",
            "properties": {
              "type": {"type": "string", "enum": ["EXPENSE", "INCOME", "TRANSFER"]},
              "amount": {"type": "number"},
              "title": {"anyOf": [{"type": "string"}, {"type": "null"}]},
              "category": {"anyOf": [{"type": "string"}, {"type": "null"}]},
              "account": {"anyOf": [{"type": "string"}, {"type": "null"}]},
              "toAccount": {"anyOf": [{"type": "string"}, {"type": "null"}]},
              "date": {"anyOf": [{"type": "string", "format": "date"}, {"type": "null"}]}
            },
            "required": ["type", "amount", "title", "category", "account", "toAccount", "date"],
            "additionalProperties": false
          }
        }
      },
      "required": ["transactions"],
      "additionalProperties": false
    }
""".trimIndent()
