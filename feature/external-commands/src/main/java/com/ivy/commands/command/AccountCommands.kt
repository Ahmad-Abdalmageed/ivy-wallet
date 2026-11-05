package com.ivy.commands.command

import arrow.core.raise.Raise
import com.ivy.data.db.dao.write.WritePlannedPaymentRuleDao
import com.ivy.data.model.Account
import com.ivy.data.model.AccountId
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
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.UUID
import javax.inject.Inject
import kotlin.math.abs

/**
 * Lists, adds, changes and deletes accounts.
 */
class AccountCommands @Inject constructor(
    deps: CommandDeps,
    private val transactionRepository: TransactionRepository,
    private val plannedPaymentRuleWriter: WritePlannedPaymentRuleDao,
) : CommandGroup(deps) {
    override val commands = setOf(
        Command.ListAccounts,
        Command.AddAccount,
        Command.UpdateAccount,
        Command.DeleteAccount,
    )

    override suspend fun Raise<String>.execute(command: Command, args: CommandArgs): JsonElement =
        when (command) {
            Command.ListAccounts -> listAccounts()
            Command.AddAccount -> addAccount(args)
            Command.UpdateAccount -> updateAccount(args)
            else -> deleteAccount(args)
        }

    private suspend fun listAccounts(): JsonElement {
        val transactions = transactionRepository.findAll().filter(Transaction::settled)
        return buildJsonArray {
            deps.accountRepository.findAll().forEach { account ->
                addJsonObject {
                    put("id", account.id.value.toString())
                    put("name", account.name.value)
                    put("currency", account.asset.code)
                    put("balance", round(balance(account.id, transactions)))
                    put("include_in_balance", account.includeInBalance)
                    put("color", hex(account.color))
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

    private suspend fun balance(account: AccountId): Double =
        balance(account, transactionRepository.findAll().filter(Transaction::settled))

    private suspend fun Raise<String>.addAccount(args: CommandArgs): JsonElement {
        val name = name(required(args, "name"))
        val accounts = deps.accountRepository.findAll()
        ensureUnique(accounts, name.value, "account") { it.name.value }
        val account = Account(
            id = AccountId(UUID.randomUUID()),
            name = name,
            asset = args.string("currency")?.let { currency(it) } ?: deps.currencyRepository.getBaseCurrency(),
            color = color(args.string("color")),
            icon = icon(args.string("icon")),
            includeInBalance = args.boolean("include_in_balance") ?: true,
            orderNum = deps.accountRepository.findMaxOrderNum() + 1,
        )
        deps.accountRepository.save(account)
        args.double("balance")?.let { adjustBalance(account, current = 0.0, target = it) }
        return account.toJson()
    }

    private suspend fun Raise<String>.updateAccount(args: CommandArgs): JsonElement {
        val existing = findAccount(required(args, "account"))
        val newName = args.string("name")?.let { name(it) }
        if (newName != null && !newName.value.equals(existing.name.value, ignoreCase = true)) {
            ensureUnique(deps.accountRepository.findAll(), newName.value, "account") { it.name.value }
        }
        val account = existing.copy(
            name = newName ?: existing.name,
            asset = args.string("currency")?.let { currency(it) } ?: existing.asset,
            color = args.string("color")?.let { color(it) } ?: existing.color,
            icon = args.string("icon")?.let { icon(it) } ?: existing.icon,
            includeInBalance = args.boolean("include_in_balance") ?: existing.includeInBalance,
        )
        deps.accountRepository.save(account)
        args.double("balance")?.let { adjustBalance(account, current = balance(account.id), target = it) }
        return account.toJson()
    }

    /** Records the difference as an income or expense, like the app's "Adjust balance". */
    private suspend fun adjustBalance(account: Account, current: Double, target: Double) {
        val diff = target - current
        if (abs(diff) < MIN_ADJUSTMENT) return
        val id = TransactionId(UUID.randomUUID())
        val title = NotBlankTrimmedString.unsafe(ADJUST_BALANCE)
        val value = PositiveValue(PositiveDouble.unsafe(abs(diff)), account.asset)
        val time = deps.timeProvider.utcNow()
        val metadata = TransactionMetadata(
            recurringRuleId = null,
            paidForDateTime = null,
            loanId = null,
            loanRecordId = null,
        )
        val transaction = if (diff > 0) {
            Income(id, title, null, null, time, true, metadata, emptyList(), value, account.id)
        } else {
            Expense(id, title, null, null, time, true, metadata, emptyList(), value, account.id)
        }
        transactionRepository.save(transaction)
    }

    private suspend fun Raise<String>.deleteAccount(args: CommandArgs): JsonElement {
        val account = findAccount(required(args, "account"))
        transactionRepository.deleteAllByAccountId(account.id)
        plannedPaymentRuleWriter.deletedByAccountId(account.id.value)
        deps.accountRepository.deleteById(account.id)
        return buildJsonObject { put("deleted", account.name.value) }
    }

    private suspend fun Account.toJson(): JsonObject = buildJsonObject {
        put("id", id.value.toString())
        put("name", name.value)
        put("currency", asset.code)
        put("balance", round(balance(id)))
        put("include_in_balance", includeInBalance)
        put("color", hex(color))
        icon?.let { put("icon", it.id) }
    }

    companion object {
        const val ADJUST_BALANCE = "Adjust balance"
        private const val MIN_ADJUSTMENT = 0.009
    }
}
