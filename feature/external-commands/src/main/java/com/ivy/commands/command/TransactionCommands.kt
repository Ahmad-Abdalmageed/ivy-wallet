package com.ivy.commands.command

import arrow.core.raise.Raise
import arrow.core.raise.ensure
import arrow.core.raise.ensureNotNull
import com.ivy.data.model.Account
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
import com.ivy.data.model.primitive.AssociationId
import com.ivy.data.model.primitive.NotBlankTrimmedString
import com.ivy.data.model.primitive.PositiveDouble
import com.ivy.data.repository.TransactionRepository
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import javax.inject.Inject

/**
 * Adds, lists, changes and deletes transactions.
 */
class TransactionCommands @Inject constructor(
    deps: CommandDeps,
    private val transactionRepository: TransactionRepository,
) : CommandGroup(deps) {
    override val commands = setOf(
        Command.AddTransaction,
        Command.ListTransactions,
        Command.UpdateTransaction,
        Command.DeleteTransaction,
        Command.CategoryTotals,
    )

    override suspend fun Raise<String>.execute(command: Command, args: CommandArgs): JsonElement =
        when (command) {
            Command.AddTransaction -> addTransaction(args)
            Command.ListTransactions -> listTransactions(args)
            Command.UpdateTransaction -> updateTransaction(args)
            Command.DeleteTransaction -> deleteTransaction(args)
            else -> categoryTotals(args)
        }

    /** What a transaction is made of, before it's built. */
    private data class Draft(
        val type: String,
        val account: Account,
        val value: PositiveDouble,
        val category: CategoryId?,
        val title: NotBlankTrimmedString?,
        val description: NotBlankTrimmedString?,
        val time: Instant,
        val tags: List<TagId>,
        val toAccount: Account?,
        val toValue: PositiveDouble?,
    )

    private suspend fun Raise<String>.addTransaction(args: CommandArgs): JsonElement {
        val type = type(args.string("type") ?: TYPE_EXPENSE)
        val amount = positive(args.double("amount"), "amount")
        val account = accountOrOnly(args.string("account"))
        val converter = converter()
        val value = args.string("currency")?.let { exchange(converter, amount, currency(it), account.asset) }
            ?: amount
        val tags = findTags(args.list("tags"))
        var toAccount: Account? = null
        var toValue: PositiveDouble? = null
        if (type == TYPE_TRANSFER) {
            val accounts = deps.accountRepository.findAll()
            val toName = ensureNotNull(args.string("to_account")) {
                "Missing 'to_account' for the transfer. Accounts: ${accounts.names { it.name.value }}."
            }
            val target = findAccount(toName)
            toAccount = target
            toValue = args.double("to_amount")?.let { positive(it, "to_amount") }
                ?: exchange(converter, value, account.asset, target.asset)
        }
        val draft = Draft(
            type = type,
            account = account,
            value = value,
            category = args.string("category")?.let { findCategory(it).id },
            title = args.string("title")?.let { NotBlankTrimmedString.from(it).getOrNull() },
            description = args.string("description")?.let { NotBlankTrimmedString.from(it).getOrNull() },
            time = time(args.string("date")),
            tags = tags.map(Tag::id),
            toAccount = toAccount,
            toValue = toValue,
        )
        val transaction = build(
            id = TransactionId(UUID.randomUUID()),
            draft = draft,
            settled = true,
            metadata = TransactionMetadata(
                recurringRuleId = null,
                paidForDateTime = null,
                loanId = null,
                loanRecordId = null,
            ),
        )
        transactionRepository.save(transaction)
        tags.forEach { deps.tagRepository.associateTagToEntity(AssociationId(transaction.id.value), it.id) }
        return transaction.toJson()
    }

    @Suppress("CyclomaticComplexMethod", "LongMethod")
    private suspend fun Raise<String>.updateTransaction(args: CommandArgs): JsonElement {
        val existing = findTransaction(args)
        val type = args.string("type")?.let { type(it) } ?: existing.type()
        val oldAccount = deps.accountRepository.findById(existing.getFromAccount())
        val account = args.string("account")?.let { findAccount(it) }
            ?: ensureNotNull(oldAccount) { "The transaction's account is missing. Give 'account'." }
        val converter = converter()
        val oldValue = existing.getFromValue()
        val value = when (val amount = args.double("amount")) {
            null -> exchange(converter, oldValue.amount, oldValue.asset, account.asset)
            else -> {
                val positive = positive(amount, "amount")
                args.string("currency")?.let { exchange(converter, positive, currency(it), account.asset) }
                    ?: positive
            }
        }

        var toAccount: Account? = null
        var toValue: PositiveDouble? = null
        if (type == TYPE_TRANSFER) {
            val oldTransfer = existing as? Transfer
            val target = args.string("to_account")?.let { findAccount(it) }
                ?: oldTransfer?.toAccount?.let { deps.accountRepository.findById(it) }
                ?: raise("Missing 'to_account' for the transfer.")
            val keptToValue = oldTransfer?.takeIf {
                it.toAccount == target.id && it.fromAccount == account.id && !args.has("amount")
            }?.toValue?.amount
            toAccount = target
            toValue = args.double("to_amount")?.let { positive(it, "to_amount") }
                ?: keptToValue
                ?: exchange(converter, value, account.asset, target.asset)
        }

        val oldTags = transactionRepository.findByIds(listOf(existing.id)).firstOrNull()?.tags.orEmpty()
        val tags = if (args.has("tags")) findTags(args.list("tags")).map(Tag::id) else oldTags
        val draft = Draft(
            type = type,
            account = account,
            value = value,
            category = when (val category = args.clearable("category")) {
                null -> existing.category
                CommandArgs.Clear -> null
                is CommandArgs.Value -> findCategory(category.value).id
            },
            title = text(args, "title", existing.title),
            description = text(args, "description", existing.description),
            time = args.string("date")?.let { time(it) } ?: existing.time,
            tags = tags,
            toAccount = toAccount,
            toValue = toValue,
        )
        val transaction = build(existing.id, draft, existing.settled, existing.metadata)
        transactionRepository.save(transaction)
        val association = AssociationId(transaction.id.value)
        (oldTags - tags.toSet()).forEach { deps.tagRepository.removeTagAssociation(association, it) }
        (tags - oldTags.toSet()).forEach { deps.tagRepository.associateTagToEntity(association, it) }
        return transaction.toJson()
    }

    private fun text(
        args: CommandArgs,
        name: String,
        old: NotBlankTrimmedString?,
    ): NotBlankTrimmedString? = when (val value = args.clearable(name)) {
        null -> old
        CommandArgs.Clear -> null
        is CommandArgs.Value -> NotBlankTrimmedString.from(value.value).getOrNull()
    }

    private fun Raise<String>.build(
        id: TransactionId,
        draft: Draft,
        settled: Boolean,
        metadata: TransactionMetadata,
    ): Transaction = when (draft.type) {
        TYPE_EXPENSE -> Expense(
            id = id,
            title = draft.title,
            description = draft.description,
            category = draft.category,
            time = draft.time,
            settled = settled,
            metadata = metadata,
            tags = draft.tags,
            value = PositiveValue(draft.value, draft.account.asset),
            account = draft.account.id,
        )

        TYPE_INCOME -> Income(
            id = id,
            title = draft.title,
            description = draft.description,
            category = draft.category,
            time = draft.time,
            settled = settled,
            metadata = metadata,
            tags = draft.tags,
            value = PositiveValue(draft.value, draft.account.asset),
            account = draft.account.id,
        )

        else -> {
            val toAccount = ensureNotNull(draft.toAccount) { "Missing 'to_account' for the transfer." }
            ensure(toAccount.id != draft.account.id) { "'account' and 'to_account' must be different." }
            Transfer(
                id = id,
                title = draft.title,
                description = draft.description,
                category = draft.category,
                time = draft.time,
                settled = settled,
                metadata = metadata,
                tags = draft.tags,
                fromAccount = draft.account.id,
                fromValue = PositiveValue(draft.value, draft.account.asset),
                toAccount = toAccount.id,
                toValue = PositiveValue(draft.toValue ?: draft.value, toAccount.asset),
            )
        }
    }

    @Suppress("CyclomaticComplexMethod", "LongMethod")
    private suspend fun Raise<String>.listTransactions(args: CommandArgs): JsonElement {
        val from = args.string("from")?.let { date(it) }
        val to = args.string("to")?.let { date(it) }
        val type = args.string("type")?.let { type(it) }
        val status = args.string("status")?.lowercase()?.also {
            ensure(it in STATUSES) { "Invalid status '$it'. Use ${STATUSES.joinToString()}." }
        }
        val accounts = deps.accountRepository.findAll()
        val categories = deps.categoryRepository.findAll()
        val account = args.string("account")?.let { findAccount(it) }
        val uncategorized = args.string("category")?.let { CommandArgs.isNone(it) } == true
        val category = args.string("category")?.takeUnless { uncategorized }?.let { findCategory(it) }
        val tag = args.string("tag")?.let { findTags(listOf(it)).first() }
        val search = args.string("search")?.lowercase()
        val limit = (args.int("limit") ?: DEFAULT_LIMIT).coerceIn(1, MAX_LIMIT)

        val transactions = if (from != null || to != null) {
            val start = startOfDay(from ?: LocalDate.of(MIN_YEAR, 1, 1))
            val end = endOfDay(to ?: LocalDate.of(MAX_YEAR, 1, 1))
            val paid = if (status != STATUS_PLANNED) {
                transactionRepository.findAllBetween(start, end)
            } else {
                emptyList()
            }
            val planned = if (status != STATUS_PAID) {
                transactionRepository.findAllDueToBetween(start, end)
            } else {
                emptyList()
            }
            paid + planned
        } else {
            transactionRepository.findAll()
        }.filter { transaction ->
            (type == null || transaction.type() == type) &&
                (status == null || transaction.settled == (status == STATUS_PAID)) &&
                (account == null || account.id in transaction.accounts()) &&
                (category == null || transaction.category == category.id) &&
                (!uncategorized || transaction.category == null) &&
                (tag == null || tag.id in transaction.tags) &&
                (search == null || transaction.matches(search))
        }.distinctBy(Transaction::id).sortedByDescending(Transaction::time)

        val baseCurrency = deps.currencyRepository.getBaseCurrency()
        val converter = converter()
        fun total(type: String): Double = transactions
            .filter { it.settled && it.type() == type }
            .sumOf {
                val value = it.getFromValue()
                converter.convert(value.amount.value, value.asset, baseCurrency) ?: 0.0
            }

        val tags = deps.tagRepository.findAll().associateBy(Tag::id)
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

    private fun Transaction.matches(search: String): Boolean =
        title?.value?.lowercase()?.contains(search) == true ||
            description?.value?.lowercase()?.contains(search) == true

    private suspend fun Raise<String>.deleteTransaction(args: CommandArgs): JsonElement {
        val transaction = findTransaction(args)
        transactionRepository.deleteById(transaction.id)
        return buildJsonObject { put("deleted", transaction.id.value.toString()) }
    }

    private suspend fun Raise<String>.findTransaction(args: CommandArgs): Transaction {
        val rawId = required(args, "id")
        val id = uuid(rawId, "transaction")
        return ensureNotNull(transactionRepository.findById(TransactionId(id))) {
            "Transaction '$rawId' not found."
        }
    }

    private suspend fun Raise<String>.categoryTotals(args: CommandArgs): JsonElement {
        val (monthStart, _) = budgetMonth()
        val from = args.string("from")?.let { date(it) } ?: monthStart
        val to = args.string("to")?.let { date(it) } ?: deps.timeProvider.localDateNow()
        val type = args.string("type")?.let { type(it) } ?: TYPE_EXPENSE
        ensure(type != TYPE_TRANSFER) { "Use expense or income." }
        val account = args.string("account")?.let { findAccount(it) }
        val baseCurrency = deps.currencyRepository.getBaseCurrency()
        val converter = converter()
        val categories = deps.categoryRepository.findAll().associateBy(Category::id)

        val totals = transactionRepository.findAllBetween(startOfDay(from), endOfDay(to))
            .filter { it.settled && it.type() == type && (account == null || it.getFromAccount() == account.id) }
            .groupBy { it.category }
            .mapValues { (_, transactions) ->
                transactions.sumOf {
                    val value = it.getFromValue()
                    converter.convert(value.amount.value, value.asset, baseCurrency) ?: 0.0
                } to transactions.size
            }
            .entries.sortedByDescending { it.value.first }

        return buildJsonObject {
            put("from", from.toString())
            put("to", to.toString())
            put("type", type)
            put("base_currency", baseCurrency.code)
            put("total", round(totals.sumOf { it.value.first }))
            putJsonArray("categories") {
                totals.forEach { (category, total) ->
                    addJsonObject {
                        put("category", category?.let { categories[it]?.name?.value } ?: "none")
                        put("total", round(total.first))
                        put("count", total.second)
                    }
                }
            }
        }
    }

    private fun Raise<String>.type(raw: String): String {
        val type = raw.lowercase()
        ensure(type in TYPES) { "Invalid type '$raw'. Use ${TYPES.joinToString()}." }
        return type
    }

    companion object {
        private const val STATUS_PAID = "paid"
        private const val STATUS_PLANNED = "planned"
        private val STATUSES = listOf(STATUS_PAID, STATUS_PLANNED)
        private const val DEFAULT_LIMIT = 20
        private const val MAX_LIMIT = 200
        private const val MIN_YEAR = 1970
        private const val MAX_YEAR = 3000
    }
}
