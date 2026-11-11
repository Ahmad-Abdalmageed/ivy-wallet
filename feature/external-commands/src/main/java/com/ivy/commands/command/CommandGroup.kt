package com.ivy.commands.command

import arrow.core.raise.Raise
import arrow.core.raise.ensure
import arrow.core.raise.ensureNotNull
import com.ivy.base.legacy.SharedPrefs
import com.ivy.base.time.TimeProvider
import com.ivy.data.model.Account
import com.ivy.data.model.AccountId
import com.ivy.data.model.Category
import com.ivy.data.model.CategoryId
import com.ivy.data.model.Expense
import com.ivy.data.model.Income
import com.ivy.data.model.Tag
import com.ivy.data.model.TagId
import com.ivy.data.model.Transaction
import com.ivy.data.model.Transfer
import com.ivy.data.model.getFromAccount
import com.ivy.data.model.getFromValue
import com.ivy.data.model.primitive.AssetCode
import com.ivy.data.model.primitive.ColorInt
import com.ivy.data.model.primitive.IconAsset
import com.ivy.data.model.primitive.NotBlankTrimmedString
import com.ivy.data.model.primitive.PositiveDouble
import com.ivy.data.repository.AccountRepository
import com.ivy.data.repository.CategoryRepository
import com.ivy.data.repository.CurrencyRepository
import com.ivy.data.repository.ExchangeRatesRepository
import com.ivy.data.repository.TagRepository
import com.ivy.domain.usecase.exchange.CurrencyConverter
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.format.DateTimeParseException
import java.util.UUID
import javax.inject.Inject
import kotlin.math.roundToLong

/**
 * What every [CommandGroup] needs: lookups by name, dates, currencies.
 */
class CommandDeps @Inject constructor(
    val accountRepository: AccountRepository,
    val categoryRepository: CategoryRepository,
    val tagRepository: TagRepository,
    val exchangeRatesRepository: ExchangeRatesRepository,
    val currencyRepository: CurrencyRepository,
    val timeProvider: TimeProvider,
    val sharedPrefs: SharedPrefs,
)

/**
 * Runs some of the [Command]s. Names are matched ignoring case; ids work too.
 */
@Suppress("TooManyFunctions")
abstract class CommandGroup(protected val deps: CommandDeps) {
    abstract val commands: Set<Command>

    abstract suspend fun Raise<String>.execute(command: Command, args: CommandArgs): JsonElement

    protected suspend fun converter(): CurrencyConverter = CurrencyConverter(
        rates = deps.exchangeRatesRepository.findAll().first(),
        preferredBase = deps.currencyRepository.getBaseCurrency(),
    )

    protected fun Raise<String>.exchange(
        converter: CurrencyConverter,
        amount: PositiveDouble,
        from: AssetCode,
        to: AssetCode,
    ): PositiveDouble {
        if (from == to) return amount
        val converted = ensureNotNull(converter.convert(amount.value, from, to)) {
            "No exchange rate from ${from.code} to ${to.code}. Give the amount in ${to.code} " +
                "or set the rate with set_exchange_rate."
        }
        return PositiveDouble.from(converted).bind()
    }

    protected fun Raise<String>.positive(value: Double?, name: String): PositiveDouble {
        ensureNotNull(value) { "Missing '$name', a positive number." }
        return PositiveDouble.from(value).mapLeft { "'$name' must be a positive number." }.bind()
    }

    protected fun Raise<String>.required(args: CommandArgs, name: String): String =
        ensureNotNull(args.string(name)) { "Missing '$name'." }

    protected fun Raise<String>.name(raw: String, what: String = "name"): NotBlankTrimmedString =
        NotBlankTrimmedString.from(raw).mapLeft { "'$what' can't be blank." }.bind()

    protected fun Raise<String>.currency(raw: String): AssetCode {
        return AssetCode.from(raw).mapLeft { "Invalid currency '$raw'." }.bind()
    }

    protected fun Raise<String>.color(raw: String?, default: Int = DEFAULT_COLOR): ColorInt {
        if (raw == null) return ColorInt(default)
        val hex = raw.removePrefix("#")
        ensure(hex.length == RGB_HEX_LENGTH && hex.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }) {
            "Invalid color '$raw'. Use #RRGGBB."
        }
        return ColorInt(OPAQUE or hex.toInt(HEX))
    }

    protected fun icon(raw: String?): IconAsset? = raw?.let { IconAsset.from(it).getOrNull() }

    protected fun Raise<String>.time(raw: String?): Instant {
        if (raw == null) return deps.timeProvider.utcNow()
        val zone = deps.timeProvider.getZoneId()
        return parse { OffsetDateTime.parse(raw).toInstant() }
            ?: parse { Instant.parse(raw) }
            ?: parse { LocalDateTime.parse(raw).atZone(zone).toInstant() }
            ?: date(raw).atTime(deps.timeProvider.localTimeNow()).atZone(zone).toInstant()
    }

    protected fun Raise<String>.date(raw: String): LocalDate {
        val today = deps.timeProvider.localDateNow()
        return when (raw.lowercase()) {
            "today" -> today
            "yesterday" -> today.minusDays(1)
            "tomorrow" -> today.plusDays(1)
            else -> ensureNotNull(parse { LocalDate.parse(raw) }) {
                "Invalid date '$raw'. Use YYYY-MM-DD, YYYY-MM-DDTHH:MM, today or yesterday."
            }
        }
    }

    protected fun startOfDay(date: LocalDate): Instant = date.atStartOfDay(deps.timeProvider.getZoneId()).toInstant()

    protected fun endOfDay(date: LocalDate): Instant = startOfDay(date.plusDays(1)).minusMillis(1)

    protected fun localDateTime(time: Instant): String =
        LocalDateTime.ofInstant(time, deps.timeProvider.getZoneId()).withNano(0).toString()

    protected fun startDayOfMonth(): Int =
        deps.sharedPrefs.getInt(SharedPrefs.START_DATE_OF_MONTH, 1).coerceIn(1, MAX_DAY_OF_MONTH)

    /** The current budget month, from the start day of month setting, as first and last date. */
    protected fun budgetMonth(): Pair<LocalDate, LocalDate> {
        val today = deps.timeProvider.localDateNow()
        val startDay = startDayOfMonth()
        fun start(month: LocalDate): LocalDate = month.withDayOfMonth(minOf(startDay, month.lengthOfMonth()))
        val thisMonth = start(today)
        val from = if (today < thisMonth) start(today.minusMonths(1)) else thisMonth
        return from to start(from.plusMonths(1)).minusDays(1)
    }

    private fun <T> parse(parse: () -> T): T? = try {
        parse()
    } catch (_: DateTimeParseException) {
        null
    }

    protected suspend fun Raise<String>.findAccount(query: String): Account =
        find(deps.accountRepository.findAll(), query, "account", { it.id.value }, { it.name.value })

    /** The named account, or the only one when there's one. */
    protected suspend fun Raise<String>.accountOrOnly(query: String?): Account {
        if (query != null) return findAccount(query)
        val accounts = deps.accountRepository.findAll()
        ensure(accounts.size == 1) { "Missing 'account'. Accounts: ${accounts.names { it.name.value }}." }
        return accounts.first()
    }

    protected suspend fun Raise<String>.findCategory(query: String): Category =
        find(deps.categoryRepository.findAll(), query, "category", { it.id.value }, { it.name.value })

    protected suspend fun Raise<String>.findTags(queries: List<String>): List<Tag> {
        if (queries.isEmpty()) return emptyList()
        val tags = deps.tagRepository.findAll()
        return queries.map { find(tags, it, "tag", { tag -> tag.id.value }, { tag -> tag.name.value }) }
            .distinctBy(Tag::id)
    }

    @Suppress("LongParameterList")
    protected fun <T> Raise<String>.find(
        items: List<T>,
        query: String,
        kind: String,
        id: (T) -> UUID,
        name: (T) -> String?,
    ): T {
        val match = items.find { id(it).toString().equals(query.trim(), ignoreCase = true) }
            ?: items.find { name(it)?.trim().equals(query.trim(), ignoreCase = true) }
        return ensureNotNull(match) {
            "Unknown $kind '$query'. Available: ${items.names { name(it) ?: id(it).toString() }}."
        }
    }

    /** Fails when another item already has the name. */
    protected fun <T> Raise<String>.ensureUnique(
        items: List<T>,
        name: String,
        kind: String,
        itemName: (T) -> String,
    ) {
        ensure(items.none { itemName(it).trim().equals(name.trim(), ignoreCase = true) }) {
            "There's already a $kind named '$name'."
        }
    }

    protected fun <T> List<T>.names(name: (T) -> String): String =
        if (isEmpty()) "none" else joinToString { name(it) }

    protected fun Raise<String>.uuid(raw: String, kind: String): UUID =
        ensureNotNull(raw.toUuid()) { "Invalid $kind id '$raw'." }

    protected fun Transaction.type(): String = when (this) {
        is Expense -> TYPE_EXPENSE
        is Income -> TYPE_INCOME
        is Transfer -> TYPE_TRANSFER
    }

    protected fun Transaction.accounts(): List<AccountId> =
        listOfNotNull(getFromAccount(), (this as? Transfer)?.toAccount)

    protected fun Transaction.toJson(
        accounts: Map<AccountId, Account>,
        categories: Map<CategoryId, Category>,
        tags: Map<TagId, Tag>,
    ): JsonObject = buildJsonObject {
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
        put("date", localDateTime(time))
        put("settled", settled)
        putJsonArray("tags") {
            this@toJson.tags.mapNotNull { tags[it]?.name?.value }.forEach { add(it) }
        }
        metadata.recurringRuleId?.let { put("planned_payment_id", it.toString()) }
        metadata.loanId?.let { put("loan_id", it.toString()) }
    }

    protected suspend fun Transaction.toJson(): JsonObject = toJson(
        accounts = deps.accountRepository.findAll().associateBy(Account::id),
        categories = deps.categoryRepository.findAll().associateBy(Category::id),
        tags = deps.tagRepository.findAll().associateBy(Tag::id),
    )

    protected fun hex(color: ColorInt): String = "#%06X".format(color.value and RGB_MASK)

    companion object {
        const val TYPE_EXPENSE = "expense"
        const val TYPE_INCOME = "income"
        const val TYPE_TRANSFER = "transfer"
        val TYPES = listOf(TYPE_EXPENSE, TYPE_INCOME, TYPE_TRANSFER)

        /** Ivy's purple. */
        const val DEFAULT_COLOR = -0xA3C20B // 0xFF5C3DF5
        private const val OPAQUE = -0x1000000 // 0xFF000000
        private const val RGB_MASK = 0xFFFFFF
        private const val RGB_HEX_LENGTH = 6
        private const val HEX = 16
        private const val MAX_DAY_OF_MONTH = 31
        private const val CENTS = 100.0

        fun round(value: Double): Double = (value * CENTS).roundToLong() / CENTS

        fun String.toUuid(): UUID? = try {
            UUID.fromString(trim())
        } catch (_: IllegalArgumentException) {
            null
        }
    }
}
