package com.ivy.commands.command

import arrow.core.raise.Raise
import arrow.core.raise.ensure
import arrow.core.raise.ensureNotNull
import com.ivy.base.model.IntervalType
import com.ivy.base.model.TransactionType
import com.ivy.data.db.dao.read.PlannedPaymentRuleDao
import com.ivy.data.db.dao.write.WritePlannedPaymentRuleDao
import com.ivy.data.db.entity.PlannedPaymentRuleEntity
import com.ivy.data.model.Account
import com.ivy.data.model.CategoryId
import com.ivy.data.model.Expense
import com.ivy.data.model.Income
import com.ivy.data.model.PositiveValue
import com.ivy.data.model.Transaction
import com.ivy.data.model.TransactionId
import com.ivy.data.model.TransactionMetadata
import com.ivy.data.model.Transfer
import com.ivy.data.model.primitive.NotBlankTrimmedString
import com.ivy.data.model.primitive.PositiveDouble
import com.ivy.data.repository.TransactionRepository
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.Instant
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit
import java.util.UUID
import javax.inject.Inject

/**
 * Planned payments (one-time and recurring) and paying their upcoming transactions,
 * like the Planned payments screen: saving a rule recreates its planned transactions.
 */
class PlannedPaymentCommands @Inject constructor(
    deps: CommandDeps,
    private val ruleDao: PlannedPaymentRuleDao,
    private val ruleWriter: WritePlannedPaymentRuleDao,
    private val transactionRepository: TransactionRepository,
) : CommandGroup(deps) {
    override val commands = setOf(
        Command.ListPlannedPayments,
        Command.AddPlannedPayment,
        Command.UpdatePlannedPayment,
        Command.DeletePlannedPayment,
        Command.PayTransaction,
    )

    override suspend fun Raise<String>.execute(command: Command, args: CommandArgs): JsonElement =
        when (command) {
            Command.ListPlannedPayments -> buildJsonArray {
                ruleDao.findAll().forEach { add(it.toJson()) }
            }

            Command.AddPlannedPayment -> save(args, existing = null)
            Command.UpdatePlannedPayment -> save(args, existing = findRule(required(args, "planned_payment")))
            Command.DeletePlannedPayment -> {
                val rule = findRule(required(args, "planned_payment"))
                ruleWriter.deleteById(rule.id)
                transactionRepository.deletedByRecurringRuleIdAndNoDateTime(rule.id)
                buildJsonObject { put("deleted", rule.id.toString()) }
            }

            else -> pay(args)
        }

    @Suppress("CyclomaticComplexMethod")
    private suspend fun Raise<String>.save(args: CommandArgs, existing: PlannedPaymentRuleEntity?): JsonElement {
        val type = when (args.string("type")?.lowercase()) {
            null -> existing?.type ?: TransactionType.EXPENSE
            TYPE_EXPENSE -> TransactionType.EXPENSE
            TYPE_INCOME -> TransactionType.INCOME
            else -> raise("Invalid type '${args.string("type")}'. Use expense or income.")
        }
        val accountName = args.string("account")
        val account = when {
            accountName != null -> findAccount(accountName)
            existing != null -> ensureNotNull(
                deps.accountRepository.findAll().find { it.id.value == existing.accountId }
            ) { "The planned payment's account is missing. Give 'account'." }

            else -> accountOrOnly(null)
        }
        val amount = when (val raw = args.double("amount")) {
            null -> ensureNotNull(existing?.amount) { "Missing 'amount', a positive number." }
            else -> positive(raw, "amount").value
        }
        val startDate = args.string("start_date")?.let { time(it) }
            ?: ensureNotNull(existing?.startDate) { "Missing 'start_date'." }
        val repeat = args.string("repeat")?.lowercase()?.also {
            ensure(it in REPEATS) { "Invalid repeat '$it'. Use ${REPEATS.joinToString()}." }
        }
        val oneTime = repeat?.let { it == ONCE } ?: existing?.oneTime ?: true
        val intervalType = repeat?.takeIf { it != ONCE }?.let { IntervalType.valueOf(it.uppercase()) }
            ?: existing?.intervalType ?: IntervalType.MONTH
        val intervalN = args.int("every") ?: existing?.intervalN ?: 1
        ensure(intervalN > 0) { "'every' must be at least 1." }

        val rule = PlannedPaymentRuleEntity(
            startDate = startDate,
            intervalN = intervalN,
            intervalType = intervalType,
            oneTime = oneTime,
            type = type,
            accountId = account.id.value,
            amount = amount,
            categoryId = when (val category = args.clearable("category")) {
                null -> existing?.categoryId
                CommandArgs.Clear -> null
                is CommandArgs.Value -> findCategory(category.value).id.value
            },
            title = text(args, "title", existing?.title),
            description = text(args, "description", existing?.description),
            id = existing?.id ?: UUID.randomUUID(),
        )
        ruleWriter.save(rule)
        generate(rule, account)
        return rule.toJson()
    }

    private fun text(args: CommandArgs, name: String, old: String?): String? =
        when (val value = args.clearable(name)) {
            null -> old
            CommandArgs.Clear -> null
            is CommandArgs.Value -> value.value
        }

    /** Recreates the rule's planned transactions, like `PlannedPaymentsGenerator`. */
    private suspend fun generate(rule: PlannedPaymentRuleEntity, account: Account) {
        transactionRepository.deletedByRecurringRuleIdAndNoDateTime(rule.id)
        val paid = transactionRepository.findAllByRecurringRuleId(rule.id).size
        val start = rule.startDate ?: return
        val dueDates = if (rule.oneTime) {
            if (paid == 0) listOf(start) else emptyList()
        } else {
            val end = start.plus(GENERATE_DAYS, ChronoUnit.DAYS)
            val intervalType = rule.intervalType ?: IntervalType.MONTH
            val intervalN = rule.intervalN ?: 1
            generateSequence(start) { increment(it, intervalType, intervalN) }
                .takeWhile { it.isBefore(end) }
                .drop(paid)
                .take(MAX_GENERATED)
                .toList()
        }
        transactionRepository.saveMany(dueDates.map { planned(rule, account, it) })
    }

    private fun increment(date: Instant, type: IntervalType, n: Int): Instant = when (type) {
        IntervalType.DAY -> date.plus(n.toLong(), ChronoUnit.DAYS)
        IntervalType.WEEK -> date.plus(n.toLong() * DAYS_IN_WEEK, ChronoUnit.DAYS)
        IntervalType.MONTH -> date.atZone(ZoneOffset.UTC).plusMonths(n.toLong()).toInstant()
        IntervalType.YEAR -> date.atZone(ZoneOffset.UTC).plusYears(n.toLong()).toInstant()
    }

    private fun planned(rule: PlannedPaymentRuleEntity, account: Account, dueDate: Instant): Transaction {
        val id = TransactionId(UUID.randomUUID())
        val title = rule.title?.let { NotBlankTrimmedString.from(it).getOrNull() }
        val description = rule.description?.let { NotBlankTrimmedString.from(it).getOrNull() }
        val category = rule.categoryId?.let(::CategoryId)
        val metadata = TransactionMetadata(
            recurringRuleId = rule.id,
            paidForDateTime = null,
            loanId = null,
            loanRecordId = null,
        )
        val value = PositiveValue(PositiveDouble.unsafe(rule.amount), account.asset)
        return if (rule.type == TransactionType.INCOME) {
            Income(id, title, description, category, dueDate, false, metadata, emptyList(), value, account.id)
        } else {
            Expense(id, title, description, category, dueDate, false, metadata, emptyList(), value, account.id)
        }
    }

    private suspend fun Raise<String>.pay(args: CommandArgs): JsonElement {
        val rawId = required(args, "id")
        val id = TransactionId(uuid(rawId, "transaction"))
        val transaction = ensureNotNull(transactionRepository.findById(id)) { "Transaction '$rawId' not found." }
        ensure(!transaction.settled) { "Transaction '$rawId' is already paid." }
        val skip = args.boolean("skip") == true
        if (skip) {
            transactionRepository.deleteById(transaction.id)
        } else {
            transactionRepository.save(transaction.paid(deps.timeProvider.utcNow()))
        }
        transaction.metadata.recurringRuleId
            ?.let { ruleDao.findById(it) }
            ?.takeIf { it.oneTime }
            ?.let { ruleWriter.deleteById(it.id) }
        return if (skip) {
            buildJsonObject { put("skipped", rawId) }
        } else {
            transactionRepository.findById(transaction.id)?.toJson() ?: buildJsonObject { put("paid", rawId) }
        }
    }

    private fun Transaction.paid(now: Instant): Transaction {
        val metadata = metadata.copy(paidForDateTime = time)
        return when (this) {
            is Expense -> copy(settled = true, time = now, metadata = metadata)
            is Income -> copy(settled = true, time = now, metadata = metadata)
            is Transfer -> copy(settled = true, time = now, metadata = metadata)
        }
    }

    private suspend fun Raise<String>.findRule(query: String): PlannedPaymentRuleEntity =
        find(ruleDao.findAll(), query, "planned payment", { it.id }, { it.title })

    private suspend fun PlannedPaymentRuleEntity.toJson(): JsonObject {
        val account = deps.accountRepository.findAll().find { it.id.value == accountId }
        val category = categoryId?.let { id -> deps.categoryRepository.findAll().find { it.id.value == id } }
        val next = transactionRepository.findAllByRecurringRuleId(id)
            .filter { !it.settled }
            .minOfOrNull(Transaction::time)
        return buildJsonObject {
            put("id", id.toString())
            put("type", if (type == TransactionType.INCOME) TYPE_INCOME else TYPE_EXPENSE)
            put("amount", round(amount))
            put("currency", account?.asset?.code)
            put("account", account?.name?.value)
            put("category", category?.name?.value)
            put("title", title)
            put("description", description)
            put("start_date", startDate?.let { localDateTime(it) })
            put("repeat", if (oneTime) ONCE else intervalType?.name?.lowercase())
            if (!oneTime) put("every", intervalN)
            put("next_due", next?.let { localDateTime(it) })
        }
    }

    companion object {
        private const val ONCE = "once"
        private val REPEATS = listOf(ONCE, "day", "week", "month", "year")

        /** About 3 years, like the app. */
        private const val GENERATE_DAYS = 1095L
        private const val MAX_GENERATED = 72
        private const val DAYS_IN_WEEK = 7
    }
}
