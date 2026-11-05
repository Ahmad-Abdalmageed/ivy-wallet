package com.ivy.commands.command

import arrow.core.raise.Raise
import arrow.core.raise.ensureNotNull
import com.ivy.base.model.LoanRecordType
import com.ivy.data.db.dao.read.LoanDao
import com.ivy.data.db.dao.read.LoanRecordDao
import com.ivy.data.db.dao.write.WriteLoanDao
import com.ivy.data.db.dao.write.WriteLoanRecordDao
import com.ivy.data.db.entity.LoanEntity
import com.ivy.data.db.entity.LoanRecordEntity
import com.ivy.data.model.Account
import com.ivy.data.model.Category
import com.ivy.data.model.CategoryId
import com.ivy.data.model.Expense
import com.ivy.data.model.Income
import com.ivy.data.model.LoanType
import com.ivy.data.model.PositiveValue
import com.ivy.data.model.TransactionId
import com.ivy.data.model.TransactionMetadata
import com.ivy.data.model.primitive.AssetCode
import com.ivy.data.model.primitive.ColorInt
import com.ivy.data.model.primitive.IconAsset
import com.ivy.data.model.primitive.NotBlankTrimmedString
import com.ivy.data.model.primitive.PositiveDouble
import com.ivy.data.repository.TransactionRepository
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import java.time.Instant
import java.time.LocalDateTime
import java.util.Locale
import java.util.UUID
import javax.inject.Inject

/**
 * Loans and their records, like the Loans screen: with an account, the money moving in or out
 * is recorded there as a transaction in the "Loans" category.
 */
class LoanCommands @Inject constructor(
    deps: CommandDeps,
    private val loanDao: LoanDao,
    private val loanWriter: WriteLoanDao,
    private val recordDao: LoanRecordDao,
    private val recordWriter: WriteLoanRecordDao,
    private val transactionRepository: TransactionRepository,
) : CommandGroup(deps) {
    override val commands = setOf(
        Command.ListLoans,
        Command.AddLoan,
        Command.UpdateLoan,
        Command.DeleteLoan,
        Command.AddLoanRecord,
        Command.DeleteLoanRecord,
    )

    override suspend fun Raise<String>.execute(command: Command, args: CommandArgs): JsonElement =
        when (command) {
            Command.ListLoans -> buildJsonArray {
                loanDao.findAll().forEach { add(it.toJson()) }
            }

            Command.AddLoan -> addLoan(args)
            Command.UpdateLoan -> updateLoan(args)
            Command.DeleteLoan -> {
                val loan = findLoan(required(args, "loan"))
                transactionRepository.findAllByLoanId(loan.id).forEach { transactionRepository.deleteById(it.id) }
                recordDao.findAllByLoanId(loan.id).forEach { recordWriter.deleteById(it.id) }
                loanWriter.deleteById(loan.id)
                buildJsonObject { put("deleted", loan.name) }
            }

            Command.AddLoanRecord -> addRecord(args)
            else -> {
                val rawId = required(args, "id")
                val record = ensureNotNull(recordDao.findById(uuid(rawId, "loan record"))) {
                    "Loan record '$rawId' not found."
                }
                transactionRepository.findLoanRecordTransaction(record.id)
                    ?.let { transactionRepository.deleteById(it.id) }
                recordWriter.deleteById(record.id)
                buildJsonObject { put("deleted", rawId) }
            }
        }

    private suspend fun Raise<String>.addLoan(args: CommandArgs): JsonElement {
        val name = name(required(args, "name")).value
        val type = when (required(args, "type").lowercase()) {
            "borrow", "borrowed" -> LoanType.BORROW
            "lend", "lent" -> LoanType.LEND
            else -> raise("Invalid type '${args.string("type")}'. Use borrow or lend.")
        }
        val amount = positive(args.double("amount"), "amount")
        val account = args.string("account")?.let { findAccount(it) }
        val time = time(args.string("date"))
        val loan = LoanEntity(
            name = name,
            amount = amount.value,
            type = type,
            color = color(args.string("color")).value,
            icon = null,
            orderNum = (loanDao.findMaxOrderNum() ?: 0.0) + 1,
            accountId = account?.id?.value,
            note = args.string("note"),
            dateTime = LocalDateTime.ofInstant(time, deps.timeProvider.getZoneId()),
        )
        loanWriter.save(loan)
        if (account != null) {
            // Borrowed money comes in, lent money goes out.
            saveTransaction(
                income = type == LoanType.BORROW,
                amount = amount,
                account = account,
                title = name,
                time = time,
                loanId = loan.id,
                loanRecordId = null,
            )
        }
        return loan.toJson()
    }

    private suspend fun Raise<String>.updateLoan(args: CommandArgs): JsonElement {
        val existing = findLoan(required(args, "loan"))
        val loan = existing.copy(
            name = args.string("name")?.let { name(it).value } ?: existing.name,
            amount = args.double("amount")?.let { positive(it, "amount").value } ?: existing.amount,
            note = when (val note = args.clearable("note")) {
                null -> existing.note
                CommandArgs.Clear -> null
                is CommandArgs.Value -> note.value
            },
            color = args.string("color")?.let { color(it).value } ?: existing.color,
        )
        loanWriter.save(loan)
        return loan.toJson()
    }

    private suspend fun Raise<String>.addRecord(args: CommandArgs): JsonElement {
        val loan = findLoan(required(args, "loan"))
        val amount = positive(args.double("amount"), "amount")
        val recordType = when (args.string("type")?.lowercase()) {
            null, "decrease", "payment" -> LoanRecordType.DECREASE
            "increase" -> LoanRecordType.INCREASE
            else -> raise("Invalid type '${args.string("type")}'. Use decrease or increase.")
        }
        val account = args.string("account")?.let { findAccount(it) }
        val loanCurrency = loanCurrency(loan)
        val convertedAmount = account?.asset?.takeIf { it != loanCurrency }
            ?.let { exchange(converter(), amount, it, loanCurrency).value }
        val time = time(args.string("date"))
        val record = LoanRecordEntity(
            loanId = loan.id,
            amount = amount.value,
            note = args.string("note"),
            dateTime = time,
            interest = args.boolean("interest") ?: false,
            accountId = account?.id?.value,
            convertedAmount = convertedAmount,
            loanRecordType = recordType,
        )
        recordWriter.save(record)
        if (account != null) {
            // Paying back borrowed money or getting lent money back moves it the other way.
            val borrow = loan.type == LoanType.BORROW
            saveTransaction(
                income = if (recordType == LoanRecordType.DECREASE) !borrow else borrow,
                amount = amount,
                account = account,
                title = record.note ?: loan.name,
                time = time,
                loanId = loan.id,
                loanRecordId = record.id,
            )
        }
        return loanDao.findById(loan.id)?.toJson() ?: loan.toJson()
    }

    @Suppress("LongParameterList")
    private suspend fun saveTransaction(
        income: Boolean,
        amount: PositiveDouble,
        account: Account,
        title: String,
        time: Instant,
        loanId: UUID,
        loanRecordId: UUID?,
    ) {
        val id = TransactionId(UUID.randomUUID())
        val value = PositiveValue(amount, account.asset)
        val metadata = TransactionMetadata(
            recurringRuleId = null,
            paidForDateTime = null,
            loanId = loanId,
            loanRecordId = loanRecordId,
        )
        val name = NotBlankTrimmedString.from(title).getOrNull()
        val category = loanCategory()
        val transaction = if (income) {
            Income(id, name, null, category, time, true, metadata, emptyList(), value, account.id)
        } else {
            Expense(id, name, null, category, time, true, metadata, emptyList(), value, account.id)
        }
        transactionRepository.save(transaction)
    }

    /** A category with "loan" in its name, or a new "Loans" one, like the app. */
    private suspend fun loanCategory(): CategoryId {
        val categories = deps.categoryRepository.findAll()
        categories.find { it.name.value.lowercase(Locale.ENGLISH).contains("loan") }?.let { return it.id }
        val category = Category(
            id = CategoryId(UUID.randomUUID()),
            name = NotBlankTrimmedString.unsafe("Loans"),
            color = ColorInt(DEFAULT_COLOR),
            icon = IconAsset.from("loan").getOrNull(),
            orderNum = deps.categoryRepository.findMaxOrderNum() + 1,
        )
        deps.categoryRepository.save(category)
        return category.id
    }

    private suspend fun loanCurrency(loan: LoanEntity): AssetCode =
        loan.accountId?.let { id -> deps.accountRepository.findAll().find { it.id.value == id }?.asset }
            ?: deps.currencyRepository.getBaseCurrency()

    private suspend fun Raise<String>.findLoan(query: String): LoanEntity =
        find(loanDao.findAll(), query, "loan", { it.id }, { it.name })

    private suspend fun LoanEntity.toJson(): JsonObject {
        val records = recordDao.findAllByLoanId(id)
        val principal = records.filter { !it.interest }
        val (paid, total) = principal.fold(0.0 to amount) { (paidSoFar, totalSoFar), record ->
            val value = record.convertedAmount ?: record.amount
            when (record.loanRecordType) {
                LoanRecordType.DECREASE -> paidSoFar + value to totalSoFar
                LoanRecordType.INCREASE -> paidSoFar to totalSoFar + value
            }
        }
        val accounts = deps.accountRepository.findAll()
        return buildJsonObject {
            put("id", id.toString())
            put("name", name)
            put("type", type.name.lowercase())
            put("amount", round(amount))
            put("currency", loanCurrency(this@toJson).code)
            put("account", accounts.find { it.id.value == accountId }?.name?.value)
            put("note", note)
            put("total", round(total))
            put("paid", round(paid))
            put("left", round(total - paid))
            putJsonArray("records") {
                records.forEach { record ->
                    addJsonObject {
                        put("id", record.id.toString())
                        put("type", record.loanRecordType.name.lowercase())
                        put("amount", round(record.amount))
                        put("account", accounts.find { it.id.value == record.accountId }?.name?.value)
                        put("note", record.note)
                        put("date", localDateTime(record.dateTime))
                        put("interest", record.interest)
                    }
                }
            }
        }
    }
}
