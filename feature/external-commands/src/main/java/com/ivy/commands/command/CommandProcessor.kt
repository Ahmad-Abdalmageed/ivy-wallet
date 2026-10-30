package com.ivy.commands.command

import arrow.core.Either
import arrow.core.raise.Raise
import arrow.core.raise.either
import arrow.core.raise.ensure
import arrow.core.raise.ensureNotNull
import com.ivy.base.time.TimeProvider
import com.ivy.data.model.Account
import com.ivy.data.model.AccountId
import com.ivy.data.model.Category
import com.ivy.data.model.CategoryId
import com.ivy.data.model.Expense
import com.ivy.data.model.Income
import com.ivy.data.model.PositiveValue
import com.ivy.data.model.Tag
import com.ivy.data.model.TagId
import com.ivy.data.model.Transaction
import com.ivy.data.model.TransactionId
import com.ivy.data.model.TransactionMetadata
import com.ivy.data.model.Transfer
import com.ivy.data.model.getFromAccount
import com.ivy.data.model.getFromValue
import com.ivy.data.model.primitive.AssetCode
import com.ivy.data.model.primitive.AssociationId
import com.ivy.data.model.primitive.NotBlankTrimmedString
import com.ivy.data.model.primitive.PositiveDouble
import com.ivy.data.repository.AccountRepository
import com.ivy.data.repository.CategoryRepository
import com.ivy.data.repository.CurrencyRepository
import com.ivy.data.repository.ExchangeRatesRepository
import com.ivy.data.repository.TagRepository
import com.ivy.data.repository.TransactionRepository
import com.ivy.domain.usecase.exchange.CurrencyConverter
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.format.DateTimeParseException
import java.util.UUID
import javax.inject.Inject
import kotlin.math.roundToLong

/**
 * Runs the [Command]s. Names of accounts, categories and tags are matched ignoring case.
 */
@Suppress("TooManyFunctions")
class CommandProcessor @Inject constructor(
    private val accountRepository: AccountRepository,
    private val categoryRepository: CategoryRepository,
    private val tagRepository: TagRepository,
    private val transactionRepository: TransactionRepository,
    private val exchangeRatesRepository: ExchangeRatesRepository,
    private val currencyRepository: CurrencyRepository,
    private val timeProvider: TimeProvider,
) {
    suspend fun execute(method: String, args: CommandArgs): Either<String, JsonElement> = either {
        val command = ensureNotNull(Command.fromMethod(method)) {
            "Unknown command '$method'. Commands: ${Command.entries.joinToString { it.method }}."
        }
        when (command) {
            Command.Help -> help()
            Command.ListAccounts -> listAccounts()
            Command.ListCategories -> buildJsonArray {
                categoryRepository.findAll().forEach { category ->
                    addJsonObject {
                        put("id", category.id.value.toString())
                        put("name", category.name.value)
                    }
                }
            }

            Command.ListTags -> buildJsonArray {
                tagRepository.findAll().forEach { tag ->
                    addJsonObject {
                        put("id", tag.id.value.toString())
                        put("name", tag.name.value)
                    }
                }
            }

            Command.AddTransaction -> addTransaction(args)
            Command.ListTransactions -> listTransactions(args)
            Command.DeleteTransaction -> deleteTransaction(args)
        }
    }

    private fun help(): JsonElement = buildJsonArray {
        Command.entries.forEach { command ->
            addJsonObject {
                put("command", command.method)
                put("description", command.description)
                putJsonObject("arguments") {
                    command.arguments.forEach { (name, description) -> put(name, description) }
                }
            }
        }
    }

    private suspend fun listAccounts(): JsonElement {
        val transactions = transactionRepository.findAll().filter(Transaction::settled)
        return buildJsonArray {
            accountRepository.findAll().forEach { account ->
                addJsonObject {
                    put("id", account.id.value.toString())
                    put("name", account.name.value)
                    put("currency", account.asset.code)
                    put("balance", round(balance(account.id, transactions)))
                }
            }
        }
    }

    private fun balance(account: AccountId, transactions: List<Transaction>): Double =
        transactions.sumOf { transaction ->
            when (transaction) {
                is Income -> if (transaction.account == account) transaction.value.amount.value else 0.0
                is Expense -> if (transaction.account == account) -transaction.value.amount.value else 0.0
                is Transfer -> when (account) {
                    transaction.toAccount -> transaction.toValue.amount.value
                    transaction.fromAccount -> -transaction.fromValue.amount.value
                    else -> 0.0
                }
            }
        }

    @Suppress("CyclomaticComplexMethod", "LongMethod")
    private suspend fun Raise<String>.addTransaction(args: CommandArgs): JsonElement {
        val type = type(args.string("type") ?: TYPE_EXPENSE)
        val amount = positive(args.double("amount"), "amount")
        val accounts = accountRepository.findAll()
        val account = when (val name = args.string("account")) {
            null -> {
                ensure(accounts.size == 1) {
                    "Missing 'account'. Accounts: ${accounts.names { it.name.value }}."
                }
                accounts.first()
            }

            else -> find(accounts, name, "account", { it.id.value }, { it.name.value })
        }
        val converter = converter()
        val value = when (val currencyCode = args.string("currency")) {
            null -> amount
            else -> exchange(converter, amount, AssetCode.from(currencyCode).bind(), account.asset)
        }
        val category = args.string("category")?.let { name ->
            find(categoryRepository.findAll(), name, "category", { it.id.value }, { it.name.value })
        }
        val tags = args.list("tags").takeIf { it.isNotEmpty() }?.let { names ->
            val allTags = tagRepository.findAll()
            names.map { find(allTags, it, "tag", { tag -> tag.id.value }, { tag -> tag.name.value }) }
        }.orEmpty()

        val id = TransactionId(UUID.randomUUID())
        val title = args.string("title")?.let { NotBlankTrimmedString.from(it).getOrNull() }
        val description = args.string("description")?.let { NotBlankTrimmedString.from(it).getOrNull() }
        val time = time(args.string("date"))
        val metadata = TransactionMetadata(
            recurringRuleId = null,
            paidForDateTime = null,
            loanId = null,
            loanRecordId = null,
        )
        val transaction = when (type) {
            TYPE_EXPENSE -> Expense(
                id = id,
                title = title,
                description = description,
                category = category?.id,
                time = time,
                settled = true,
                metadata = metadata,
                tags = tags.map(Tag::id),
                value = PositiveValue(value, account.asset),
                account = account.id,
            )

            TYPE_INCOME -> Income(
                id = id,
                title = title,
                description = description,
                category = category?.id,
                time = time,
                settled = true,
                metadata = metadata,
                tags = tags.map(Tag::id),
                value = PositiveValue(value, account.asset),
                account = account.id,
            )

            else -> {
                val toName = ensureNotNull(args.string("to_account")) {
                    "Missing 'to_account' for the transfer. Accounts: ${accounts.names { it.name.value }}."
                }
                val toAccount = find(accounts, toName, "account", { it.id.value }, { it.name.value })
                ensure(toAccount.id != account.id) { "'account' and 'to_account' must be different." }
                val toValue = when (val toAmount = args.double("to_amount")) {
                    null -> exchange(converter, value, account.asset, toAccount.asset)
                    else -> positive(toAmount, "to_amount")
                }
                Transfer(
                    id = id,
                    title = title,
                    description = description,
                    category = category?.id,
                    time = time,
                    settled = true,
                    metadata = metadata,
                    tags = tags.map(Tag::id),
                    fromAccount = account.id,
                    fromValue = PositiveValue(value, account.asset),
                    toAccount = toAccount.id,
                    toValue = PositiveValue(toValue, toAccount.asset),
                )
            }
        }

        transactionRepository.save(transaction)
        tags.forEach { tagRepository.associateTagToEntity(AssociationId(id.value), it.id) }
        return transaction.toJson(
            accounts = accounts.associateBy(Account::id),
            categories = listOfNotNull(category).associateBy(Category::id),
            tags = tags.associateBy(Tag::id),
        )
    }

    @Suppress("CyclomaticComplexMethod")
    private suspend fun Raise<String>.listTransactions(args: CommandArgs): JsonElement {
        val zone = timeProvider.getZoneId()
        val from = args.string("from")?.let { date(it) }
        val to = args.string("to")?.let { date(it) }
        val type = args.string("type")?.let { type(it) }
        val accounts = accountRepository.findAll()
        val categories = categoryRepository.findAll()
        val account = args.string("account")?.let {
            find(accounts, it, "account", { account -> account.id.value }, { account -> account.name.value })
        }
        val category = args.string("category")?.let {
            find(categories, it, "category", { category -> category.id.value }, { category -> category.name.value })
        }
        val limit = (args.int("limit") ?: DEFAULT_LIMIT).coerceIn(1, MAX_LIMIT)

        val transactions = if (from != null || to != null) {
            transactionRepository.findAllBetween(
                startDate = (from ?: LocalDate.of(MIN_YEAR, 1, 1)).atStartOfDay(zone).toInstant(),
                endDate = (to ?: LocalDate.of(MAX_YEAR, 1, 1)).plusDays(1).atStartOfDay(zone)
                    .toInstant().minusMillis(1),
            )
        } else {
            transactionRepository.findAll()
        }.filter { transaction ->
            (type == null || transaction.type() == type) &&
                (account == null || account.id in transaction.accounts()) &&
                (category == null || transaction.category == category.id)
        }.sortedByDescending(Transaction::time)

        val baseCurrency = currencyRepository.getBaseCurrency()
        val converter = converter()
        fun total(type: String): Double = transactions
            .filter { it.settled && it.type() == type }
            .sumOf {
                val value = it.getFromValue()
                converter.convert(value.amount.value, value.asset, baseCurrency) ?: 0.0
            }

        val tags = tagRepository.findAll().associateBy(Tag::id)
        return buildJsonObject {
            put("count", transactions.size)
            put("base_currency", baseCurrency.code)
            put("total_expenses", round(total(TYPE_EXPENSE)))
            put("total_income", round(total(TYPE_INCOME)))
            putJsonArray("transactions") {
                transactions.take(limit).forEach {
                    add(
                        it.toJson(
                            accounts = accounts.associateBy(Account::id),
                            categories = categories.associateBy(Category::id),
                            tags = tags,
                        )
                    )
                }
            }
        }
    }

    private suspend fun Raise<String>.deleteTransaction(args: CommandArgs): JsonElement {
        val rawId = ensureNotNull(args.string("id")) { "Missing 'id'." }
        val id = ensureNotNull(rawId.toUuid()) { "Invalid transaction id '$rawId'." }
        val transaction = ensureNotNull(transactionRepository.findById(TransactionId(id))) {
            "Transaction '$rawId' not found."
        }
        transactionRepository.deleteById(transaction.id)
        return buildJsonObject { put("deleted", rawId) }
    }

    private suspend fun converter(): CurrencyConverter = CurrencyConverter(
        rates = exchangeRatesRepository.findAll().first(),
        preferredBase = currencyRepository.getBaseCurrency(),
    )

    private fun Raise<String>.exchange(
        converter: CurrencyConverter,
        amount: PositiveDouble,
        from: AssetCode,
        to: AssetCode,
    ): PositiveDouble {
        val converted = ensureNotNull(converter.convert(amount.value, from, to)) {
            "No exchange rate from ${from.code} to ${to.code}. Give the amount in ${to.code}."
        }
        return PositiveDouble.from(converted).bind()
    }

    private fun Raise<String>.type(raw: String): String {
        val type = raw.lowercase()
        ensure(type in TYPES) { "Invalid type '$raw'. Use ${TYPES.joinToString()}." }
        return type
    }

    private fun Raise<String>.positive(value: Double?, name: String): PositiveDouble {
        ensureNotNull(value) { "Missing '$name', a positive number." }
        return PositiveDouble.from(value).mapLeft { "'$name' must be a positive number." }.bind()
    }

    private fun Raise<String>.time(raw: String?): Instant {
        if (raw == null) return timeProvider.utcNow()
        val zone = timeProvider.getZoneId()
        return parse { OffsetDateTime.parse(raw).toInstant() }
            ?: parse { Instant.parse(raw) }
            ?: parse { LocalDateTime.parse(raw).atZone(zone).toInstant() }
            ?: date(raw).atTime(timeProvider.localTimeNow()).atZone(zone).toInstant()
    }

    private fun Raise<String>.date(raw: String): LocalDate {
        val today = timeProvider.localDateNow()
        return when (raw.lowercase()) {
            "today" -> today
            "yesterday" -> today.minusDays(1)
            else -> ensureNotNull(parse { LocalDate.parse(raw) }) {
                "Invalid date '$raw'. Use YYYY-MM-DD, YYYY-MM-DDTHH:MM, today or yesterday."
            }
        }
    }

    private fun <T> parse(parse: () -> T): T? = try {
        parse()
    } catch (_: DateTimeParseException) {
        null
    }

    @Suppress("LongParameterList")
    private fun <T> Raise<String>.find(
        items: List<T>,
        query: String,
        kind: String,
        id: (T) -> UUID,
        name: (T) -> String,
    ): T {
        val match = items.find { id(it).toString().equals(query, ignoreCase = true) }
            ?: items.find { name(it).trim().equals(query.trim(), ignoreCase = true) }
        return ensureNotNull(match) { "Unknown $kind '$query'. Available: ${items.names(name)}." }
    }

    private fun <T> List<T>.names(name: (T) -> String): String =
        if (isEmpty()) "none" else joinToString { name(it) }

    private fun Transaction.accounts(): List<AccountId> =
        listOfNotNull(getFromAccount(), (this as? Transfer)?.toAccount)

    private fun Transaction.type(): String = when (this) {
        is Expense -> TYPE_EXPENSE
        is Income -> TYPE_INCOME
        is Transfer -> TYPE_TRANSFER
    }

    private fun Transaction.toJson(
        accounts: Map<AccountId, Account>,
        categories: Map<CategoryId, Category>,
        tags: Map<TagId, Tag>,
    ): JsonObject = buildJsonObject {
        val zone = timeProvider.getZoneId()
        put("id", id.value.toString())
        put("type", type())
        val value = getFromValue()
        put("amount", round(value.amount.value))
        put("currency", value.asset.code)
        put("account", accounts[getFromAccount()]?.name?.value)
        if (this@toJson is Transfer) {
            put("to_account", accounts[toAccount]?.name?.value)
            put("to_amount", round(toValue.amount.value))
            put("to_currency", toValue.asset.code)
        }
        put("category", category?.let { categories[it]?.name?.value })
        put("title", title?.value)
        put("description", description?.value)
        put("date", LocalDateTime.ofInstant(time, zone).withNano(0).toString())
        put("settled", settled)
        putJsonArray("tags") {
            this@toJson.tags.mapNotNull { tags[it]?.name?.value }.forEach { add(it) }
        }
    }

    private fun round(value: Double): Double = (value * CENTS).roundToLong() / CENTS

    private fun String.toUuid(): UUID? = try {
        UUID.fromString(this)
    } catch (_: IllegalArgumentException) {
        null
    }

    companion object {
        const val TYPE_EXPENSE = "expense"
        const val TYPE_INCOME = "income"
        const val TYPE_TRANSFER = "transfer"
        private val TYPES = listOf(TYPE_EXPENSE, TYPE_INCOME, TYPE_TRANSFER)
        private const val DEFAULT_LIMIT = 20
        private const val MAX_LIMIT = 200
        private const val MIN_YEAR = 1970
        private const val MAX_YEAR = 3000
        private const val CENTS = 100.0
    }
}
