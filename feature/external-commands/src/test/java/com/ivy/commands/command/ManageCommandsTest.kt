package com.ivy.commands.command

import arrow.core.Either
import com.ivy.base.legacy.SharedPrefs
import com.ivy.base.model.LoanRecordType
import com.ivy.base.model.TransactionType
import com.ivy.data.db.entity.BudgetEntity
import com.ivy.data.db.entity.LoanEntity
import com.ivy.data.db.entity.LoanRecordEntity
import com.ivy.data.db.entity.PlannedPaymentRuleEntity
import com.ivy.data.model.Account
import com.ivy.data.model.Category
import com.ivy.data.model.ExchangeRate
import com.ivy.data.model.Expense
import com.ivy.data.model.Income
import com.ivy.data.model.IntervalType
import com.ivy.data.model.LoanType
import com.ivy.data.model.PositiveValue
import com.ivy.data.model.Transaction
import com.ivy.data.model.TransactionId
import com.ivy.data.model.TransactionMetadata
import com.ivy.data.model.primitive.AssetCode
import com.ivy.data.model.primitive.AssociationId
import com.ivy.data.model.primitive.NotBlankTrimmedString
import com.ivy.data.model.primitive.PositiveDouble
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Before
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneOffset
import java.util.UUID

class ManageCommandsTest {
    private val commands = TestCommands()
    private val processor = commands.processor

    private val cash = account("Cash", "EGP")
    private val dollars = account("Dollars", "USD")
    private val food = category("Food")
    private val groceries = category("Groceries")
    private val work = tag("Work")
    private val now = Instant.parse("2026-11-05T10:00:00Z")
    private val saved = mutableListOf<Transaction>()

    private val metadata = TransactionMetadata(
        recurringRuleId = null,
        paidForDateTime = null,
        loanId = null,
        loanRecordId = null,
    )

    @Before
    fun setup() {
        with(commands) {
            coEvery { accountRepository.findAll() } returns listOf(cash, dollars)
            coEvery { accountRepository.findById(cash.id) } returns cash
            coEvery { accountRepository.findById(dollars.id) } returns dollars
            coEvery { accountRepository.findMaxOrderNum() } returns 1.0
            coEvery { categoryRepository.findAll() } returns listOf(food, groceries)
            coEvery { categoryRepository.findMaxOrderNum() } returns 1.0
            coEvery { tagRepository.findAll() } returns listOf(work)
            coEvery { currencyRepository.getBaseCurrency() } returns AssetCode.unsafe("EGP")
            every { exchangeRatesRepository.findAll() } returns flowOf(listOf(rate("EGP", "USD", 0.02)))
            coEvery { transactionRepository.save(capture(saved)) } returns Unit
            coEvery { transactionRepository.findAll() } returns emptyList()
            every { timeProvider.utcNow() } returns now
            every { timeProvider.getZoneId() } returns ZoneOffset.UTC
            every { timeProvider.localDateNow() } returns LocalDate.of(2026, 11, 5)
            every { timeProvider.localTimeNow() } returns LocalTime.of(10, 0)
            every { sharedPrefs.getInt(SharedPrefs.START_DATE_OF_MONTH, 1) } returns 1
        }
    }

    private fun run(method: String, json: String = "{}"): Either<String, JsonElement> =
        runBlocking { processor.execute(method, CommandArgs(Json.parseToJsonElement(json).jsonObject)) }

    private fun ok(method: String, json: String = "{}"): JsonElement =
        run(method, json).fold({ error("$method failed: $it") }, { it })

    @Test
    fun `every command but help has a handler`() {
        processor.handled shouldBe Command.entries.filter { it != Command.Help }.toSet()
    }

    @Test
    fun `adds an account with a starting balance`() {
        val account = slot<Account>()
        coEvery { commands.accountRepository.save(capture(account)) } returns Unit

        ok("add_account", """{"name": "Telda", "balance": 1625, "color": "#00FF00"}""")

        account.captured.name.value shouldBe "Telda"
        account.captured.asset shouldBe AssetCode.unsafe("EGP")
        account.captured.includeInBalance shouldBe true
        account.captured.color.value shouldBe 0xFF00FF00.toInt()
        val adjustment = saved.single()
        adjustment.shouldBeInstanceOf<Income>()
        adjustment.value.amount.value shouldBe 1625.0
        adjustment.account shouldBe account.captured.id
        adjustment.title?.value shouldBe "Adjust balance"
    }

    @Test
    fun `refuses a duplicate account name`() {
        run("add_account", """{"name": "cash"}""").leftOrNull()!! shouldContain "already"
    }

    @Test
    fun `changes an account balance with an adjustment`() {
        coEvery { commands.transactionRepository.findAll() } returns listOf(expense(30.0, cash))

        ok("update_account", """{"account": "Cash", "name": "Wallet", "balance": -50}""")

        coVerify { commands.accountRepository.save(match { it.id == cash.id && it.name.value == "Wallet" }) }
        val adjustment = saved.single()
        adjustment.shouldBeInstanceOf<Expense>()
        adjustment.value.amount.value shouldBe 20.0
    }

    @Test
    fun `deletes an account with its transactions and planned payments`() {
        ok("delete_account", """{"account": "Dollars"}""")

        coVerify {
            commands.transactionRepository.deleteAllByAccountId(dollars.id)
            commands.ruleWriter.deletedByAccountId(dollars.id.value)
            commands.accountRepository.deleteById(dollars.id)
        }
    }

    @Test
    fun `adds, renames and deletes categories and tags`() {
        ok("add_category", """{"name": "Car"}""")
        coVerify { commands.categoryRepository.save(match { it.name.value == "Car" }) }
        run("add_category", """{"name": "food"}""").leftOrNull()!! shouldContain "already"

        ok("update_category", """{"category": "Food", "name": "Food & Drinks"}""")
        coVerify {
            commands.categoryRepository.save(match { it.id == food.id && it.name.value == "Food & Drinks" })
        }

        ok("delete_category", """{"category": "groceries"}""")
        coVerify { commands.categoryRepository.deleteById(groceries.id) }

        ok("add_tag", """{"name": "one-off"}""")
        coVerify { commands.tagRepository.save(match { it.name.value == "one-off" }) }
        ok("delete_tag", """{"tag": "Work"}""")
        coVerify { commands.tagRepository.deleteById(work.id) }
    }

    @Test
    fun `adds a budget for categories`() {
        val budget = slot<BudgetEntity>()
        coEvery { commands.budgetDao.findAll() } returns emptyList()
        coEvery { commands.budgetDao.findMaxOrderNum() } returns null
        coEvery { commands.budgetWriter.save(capture(budget)) } returns Unit
        coEvery { commands.transactionRepository.findAllBetween(any(), any()) } returns emptyList()

        ok("add_budget", """{"name": "Household", "amount": 20200, "categories": ["Food", "groceries"]}""")

        budget.captured.name shouldBe "Household"
        budget.captured.amount shouldBe 20200.0
        budget.captured.categoryIdsSerialized shouldBe "${food.id.value},${groceries.id.value}"
        budget.captured.accountIdsSerialized shouldBe null
    }

    @Test
    fun `lists budgets with what's spent this budget month`() {
        every { commands.sharedPrefs.getInt(SharedPrefs.START_DATE_OF_MONTH, 1) } returns 25
        coEvery { commands.budgetDao.findAll() } returns listOf(
            BudgetEntity(
                name = "Food",
                amount = 1000.0,
                categoryIdsSerialized = food.id.value.toString(),
                accountIdsSerialized = null,
                orderId = 1.0,
            ),
            BudgetEntity(
                name = "Total",
                amount = 5000.0,
                categoryIdsSerialized = null,
                accountIdsSerialized = null,
                orderId = 2.0,
            ),
        )
        coEvery { commands.transactionRepository.findAllBetween(any(), any()) } returns listOf(
            expense(300.0, cash, category = food),
            expense(2.0, dollars, category = food),
            expense(50.0, cash),
            expense(999.0, cash, category = food, settled = false),
        )

        val result = ok("list_budgets").jsonObject

        // Today is Nov 5 and the month starts on the 25th.
        result["from"]?.jsonPrimitive?.content shouldBe "2026-10-25"
        result["to"]?.jsonPrimitive?.content shouldBe "2026-11-24"
        val budgets = result["budgets"]!!.jsonArray.map { it.jsonObject }
        budgets[0]["spent"]?.jsonPrimitive?.content shouldBe "400.0"
        budgets[0]["left"]?.jsonPrimitive?.content shouldBe "600.0"
        budgets[0]["categories"]!!.jsonArray.map { it.jsonPrimitive.content } shouldBe listOf("Food")
        budgets[1]["spent"]?.jsonPrimitive?.content shouldBe "450.0"
        coVerify {
            commands.transactionRepository.findAllBetween(
                Instant.parse("2026-10-25T00:00:00Z"),
                Instant.parse("2026-11-24T23:59:59.999Z"),
            )
        }
    }

    @Test
    fun `updates a transaction`() {
        val existing = expense(30.0, cash, category = food, title = "Lunch")
        coEvery { commands.transactionRepository.findById(existing.id) } returns existing
        coEvery { commands.transactionRepository.findByIds(listOf(existing.id)) } returns listOf(existing)

        ok(
            "update_transaction",
            """{"id": "${existing.id.value}", "amount": 2, "currency": "USD", "category": "none",
            "title": "Dinner", "tags": ["work"]}""",
        )

        val updated = saved.single()
        updated.shouldBeInstanceOf<Expense>()
        updated.id shouldBe existing.id
        updated.value.amount.value shouldBe 100.0
        updated.category shouldBe null
        updated.title?.value shouldBe "Dinner"
        updated.time shouldBe existing.time
        coVerify { commands.tagRepository.associateTagToEntity(AssociationId(existing.id.value), work.id) }
    }

    @Test
    fun `adds a monthly planned payment with its upcoming transactions`() {
        val rule = slot<PlannedPaymentRuleEntity>()
        val planned = slot<List<Transaction>>()
        coEvery { commands.ruleWriter.save(capture(rule)) } returns Unit
        coEvery { commands.transactionRepository.findAllByRecurringRuleId(any()) } returns emptyList()
        coEvery { commands.transactionRepository.saveMany(capture(planned)) } returns Unit

        ok(
            "add_planned_payment",
            """{"amount": 16604, "account": "Cash", "category": "Food", "title": "Car installment",
            "start_date": "2026-11-15", "repeat": "month"}""",
        )

        rule.captured.oneTime shouldBe false
        rule.captured.intervalType shouldBe IntervalType.MONTH
        rule.captured.intervalN shouldBe 1
        rule.captured.type shouldBe TransactionType.EXPENSE
        rule.captured.categoryId shouldBe food.id.value
        planned.captured.size shouldBe 36
        planned.captured.all { !it.settled && it.metadata.recurringRuleId == rule.captured.id } shouldBe true
        planned.captured[1].time shouldBe Instant.parse("2026-12-15T10:00:00Z")
        coVerify { commands.transactionRepository.deletedByRecurringRuleIdAndNoDateTime(rule.captured.id) }
    }

    @Test
    fun `pays a one-time planned transaction`() {
        val ruleId = UUID.randomUUID()
        val due = expense(4000.0, cash, settled = false, recurringRuleId = ruleId)
        coEvery { commands.transactionRepository.findById(due.id) } returns due
        coEvery { commands.ruleDao.findById(ruleId) } returns PlannedPaymentRuleEntity(
            startDate = due.time,
            intervalN = 1,
            intervalType = IntervalType.MONTH,
            oneTime = true,
            type = TransactionType.EXPENSE,
            accountId = cash.id.value,
            amount = 4000.0,
            id = ruleId,
        )

        ok("pay_transaction", """{"id": "${due.id.value}"}""")

        val paid = saved.single()
        paid.settled shouldBe true
        paid.time shouldBe now
        paid.metadata.paidForDateTime shouldBe due.time
        coVerify { commands.ruleWriter.deleteById(ruleId) }
    }

    @Test
    fun `lends money from an account`() {
        val loan = slot<LoanEntity>()
        coEvery { commands.loanDao.findMaxOrderNum() } returns null
        coEvery { commands.loanWriter.save(capture(loan)) } returns Unit
        coEvery { commands.recordDao.findAllByLoanId(any()) } returns emptyList()

        val result = ok("add_loan", """{"name": "Omar", "type": "lend", "amount": 500, "account": "Cash"}""")

        loan.captured.type shouldBe LoanType.LEND
        loan.captured.accountId shouldBe cash.id.value
        val transaction = saved.single()
        transaction.shouldBeInstanceOf<Expense>()
        transaction.metadata.loanId shouldBe loan.captured.id
        coVerify { commands.categoryRepository.save(match { it.name.value == "Loans" }) }
        (result as JsonObject)["left"]?.jsonPrimitive?.content shouldBe "500.0"
    }

    @Test
    fun `records a loan payment`() {
        val loan = LoanEntity(name = "Bank", amount = 1000.0, type = LoanType.BORROW, accountId = cash.id.value)
        val record = slot<LoanRecordEntity>()
        coEvery { commands.loanDao.findAll() } returns listOf(loan)
        coEvery { commands.loanDao.findById(loan.id) } returns loan
        coEvery { commands.recordWriter.save(capture(record)) } returns Unit
        coEvery { commands.recordDao.findAllByLoanId(loan.id) } answers { listOf(record.captured) }

        val result = ok("add_loan_record", """{"loan": "bank", "amount": 300, "account": "Cash"}""").jsonObject

        record.captured.loanRecordType shouldBe LoanRecordType.DECREASE
        saved.single().shouldBeInstanceOf<Expense>()
        result["paid"]?.jsonPrimitive?.content shouldBe "300.0"
        result["left"]?.jsonPrimitive?.content shouldBe "700.0"
    }

    @Test
    fun `changes settings and exchange rates`() {
        ok("update_settings", """{"start_day_of_month": 25}""")
        verify { commands.sharedPrefs.putInt(SharedPrefs.START_DATE_OF_MONTH, 25) }
        run("update_settings", """{"start_day_of_month": 40}""").leftOrNull()!! shouldContain "1 to 31"

        val rate = slot<ExchangeRate>()
        coEvery { commands.exchangeRatesRepository.save(capture(rate)) } returns Unit
        ok("set_exchange_rate", """{"currency": "usd", "price": 50}""")
        rate.captured.currency shouldBe AssetCode.USD
        rate.captured.rate.value shouldBe 0.02
        rate.captured.manualOverride shouldBe true
    }

    @Test
    fun `totals per category`() {
        coEvery { commands.transactionRepository.findAllBetween(any(), any()) } returns listOf(
            expense(300.0, cash, category = food),
            expense(1.0, dollars, category = food),
            expense(500.0, cash, category = groceries),
            expense(20.0, cash),
        )

        val result = ok("category_totals").jsonObject

        result["from"]?.jsonPrimitive?.content shouldBe "2026-11-01"
        result["total"]?.jsonPrimitive?.content shouldBe "870.0"
        result["categories"]!!.jsonArray.map {
            it.jsonObject["category"]!!.jsonPrimitive.content to it.jsonObject["total"]!!.jsonPrimitive.content
        } shouldBe listOf("Groceries" to "500.0", "Food" to "350.0", "none" to "20.0")
    }

    @Suppress("LongParameterList")
    private fun expense(
        amount: Double,
        account: Account,
        category: Category? = null,
        settled: Boolean = true,
        title: String? = null,
        recurringRuleId: UUID? = null,
    ): Expense = Expense(
        id = TransactionId(UUID.randomUUID()),
        title = title?.let { NotBlankTrimmedString.unsafe(it) },
        description = null,
        category = category?.id,
        time = now.minusSeconds(3600),
        settled = settled,
        metadata = metadata.copy(recurringRuleId = recurringRuleId),
        tags = emptyList(),
        value = PositiveValue(PositiveDouble.unsafe(amount), account.asset),
        account = account.id,
    )
}
