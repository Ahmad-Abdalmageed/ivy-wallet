package com.ivy.commands.command

import arrow.core.raise.Raise
import com.ivy.data.db.dao.read.BudgetDao
import com.ivy.data.db.dao.write.WriteBudgetDao
import com.ivy.data.db.entity.BudgetEntity
import com.ivy.data.model.Account
import com.ivy.data.model.AccountId
import com.ivy.data.model.Category
import com.ivy.data.model.CategoryId
import com.ivy.data.model.Expense
import com.ivy.data.model.primitive.AssetCode
import com.ivy.data.repository.TransactionRepository
import com.ivy.domain.usecase.exchange.CurrencyConverter
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import java.util.UUID
import javax.inject.Inject

/**
 * Lists, adds, changes and deletes monthly budgets.
 * Spent amounts are counted like the Budgets screen: paid expenses in the current budget month,
 * in the budget's categories and accounts (none means all), in the base currency.
 */
class BudgetCommands @Inject constructor(
    deps: CommandDeps,
    private val budgetDao: BudgetDao,
    private val budgetWriter: WriteBudgetDao,
    private val transactionRepository: TransactionRepository,
) : CommandGroup(deps) {
    override val commands = setOf(
        Command.ListBudgets,
        Command.AddBudget,
        Command.UpdateBudget,
        Command.DeleteBudget,
    )

    override suspend fun Raise<String>.execute(command: Command, args: CommandArgs): JsonElement =
        when (command) {
            Command.ListBudgets -> listBudgets()
            Command.AddBudget -> addBudget(args)
            Command.UpdateBudget -> updateBudget(args)
            else -> {
                val budget = findBudget(required(args, "budget"))
                budgetWriter.deleteById(budget.id)
                buildJsonObject { put("deleted", budget.name) }
            }
        }

    private suspend fun listBudgets(): JsonElement {
        val (from, to) = budgetMonth()
        val budgets = budgetDao.findAll()
        val context = context()
        return buildJsonObject {
            put("from", from.toString())
            put("to", to.toString())
            put("base_currency", context.baseCurrency.code)
            putJsonArray("budgets") {
                budgets.forEach { add(it.toJson(context)) }
            }
        }
    }

    private suspend fun Raise<String>.addBudget(args: CommandArgs): JsonElement {
        val name = name(required(args, "name")).value
        ensureUnique(budgetDao.findAll(), name, "budget") { it.name }
        val budget = BudgetEntity(
            name = name,
            amount = positive(args.double("amount"), "amount").value,
            categoryIdsSerialized = serialize(findCategories(args.list("categories")).map { it.id.value }),
            accountIdsSerialized = serialize(findAccounts(args.list("accounts")).map { it.id.value }),
            orderId = (budgetDao.findMaxOrderNum() ?: 0.0) + 1,
        )
        budgetWriter.save(budget)
        return budget.toJson(context())
    }

    private suspend fun Raise<String>.updateBudget(args: CommandArgs): JsonElement {
        val existing = findBudget(required(args, "budget"))
        val newName = args.string("name")?.let { name(it).value }
        if (newName != null && !newName.equals(existing.name, ignoreCase = true)) {
            ensureUnique(budgetDao.findAll(), newName, "budget") { it.name }
        }
        val budget = existing.copy(
            name = newName ?: existing.name,
            amount = args.double("amount")?.let { positive(it, "amount").value } ?: existing.amount,
            categoryIdsSerialized = if (args.has("categories")) {
                serialize(findCategories(args.list("categories")).map { it.id.value })
            } else {
                existing.categoryIdsSerialized
            },
            accountIdsSerialized = if (args.has("accounts")) {
                serialize(findAccounts(args.list("accounts")).map { it.id.value })
            } else {
                existing.accountIdsSerialized
            },
        )
        budgetWriter.save(budget)
        return budget.toJson(context())
    }

    private suspend fun Raise<String>.findBudget(query: String): BudgetEntity =
        find(budgetDao.findAll(), query, "budget", { it.id }, { it.name })

    private suspend fun Raise<String>.findCategories(queries: List<String>): List<Category> =
        queries.map { findCategory(it) }.distinctBy(Category::id)

    private suspend fun Raise<String>.findAccounts(queries: List<String>): List<Account> =
        queries.map { findAccount(it) }.distinctBy(Account::id)

    /** What the budgets are counted with. */
    private class Context(
        val baseCurrency: AssetCode,
        val expenses: List<Expense>,
        val converter: CurrencyConverter,
        val categories: Map<CategoryId, Category>,
        val accounts: Map<AccountId, Account>,
    )

    private suspend fun context(): Context {
        val (from, to) = budgetMonth()
        return Context(
            baseCurrency = deps.currencyRepository.getBaseCurrency(),
            expenses = transactionRepository.findAllBetween(startOfDay(from), endOfDay(to))
                .filterIsInstance<Expense>()
                .filter { it.settled },
            converter = converter(),
            categories = deps.categoryRepository.findAll().associateBy(Category::id),
            accounts = deps.accountRepository.findAll().associateBy(Account::id),
        )
    }

    private fun BudgetEntity.toJson(context: Context): JsonObject = buildJsonObject {
        val categoryIds = parse(categoryIdsSerialized).map(::CategoryId)
        val accountIds = parse(accountIdsSerialized).map(::AccountId)
        val spent = context.expenses
            .filter { categoryIds.isEmpty() || it.category in categoryIds }
            .filter { accountIds.isEmpty() || it.account in accountIds }
            .sumOf {
                context.converter.convert(it.value.amount.value, it.value.asset, context.baseCurrency) ?: 0.0
            }
        put("id", id.toString())
        put("name", name)
        put("amount", round(amount))
        put("spent", round(spent))
        put("left", round(amount - spent))
        put("percent_spent", if (amount > 0) round(spent / amount * PERCENT) else 0.0)
        putJsonArray("categories") {
            categoryIds.forEach { add(context.categories[it]?.name?.value ?: it.value.toString()) }
        }
        putJsonArray("accounts") {
            accountIds.forEach { add(context.accounts[it]?.name?.value ?: it.value.toString()) }
        }
    }

    private fun serialize(ids: List<UUID>): String? = ids.takeIf { it.isNotEmpty() }?.joinToString(",")

    private fun parse(ids: String?): List<UUID> =
        ids.orEmpty().split(",").mapNotNull { it.toUuid() }

    companion object {
        private const val PERCENT = 100.0
    }
}
