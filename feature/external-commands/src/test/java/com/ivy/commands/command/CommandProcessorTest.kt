package com.ivy.commands.command

import arrow.core.Either
import com.ivy.data.model.Account
import com.ivy.data.model.Expense
import com.ivy.data.model.Income
import com.ivy.data.model.PositiveValue
import com.ivy.data.model.Transaction
import com.ivy.data.model.TransactionId
import com.ivy.data.model.TransactionMetadata
import com.ivy.data.model.Transfer
import com.ivy.data.model.primitive.AssetCode
import com.ivy.data.model.primitive.AssociationId
import com.ivy.data.model.primitive.PositiveDouble
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.slot
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
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

class CommandProcessorTest {
    private val commands = TestCommands()
    private val accountRepository = commands.accountRepository
    private val categoryRepository = commands.categoryRepository
    private val tagRepository = commands.tagRepository
    private val transactionRepository = commands.transactionRepository
    private val exchangeRatesRepository = commands.exchangeRatesRepository
    private val currencyRepository = commands.currencyRepository
    private val timeProvider = commands.timeProvider

    private val cash = account("Cash", "EGP")
    private val bank = account("Bank Misr", "EGP")
    private val dollars = account("Dollars", "USD")
    private val food = category("Food")
    private val work = tag("Work")
    private val now = Instant.parse("2026-10-30T10:15:00Z")
    private val saved = slot<Transaction>()

    private val metadata = TransactionMetadata(
        recurringRuleId = null,
        paidForDateTime = null,
        loanId = null,
        loanRecordId = null,
    )

    private val processor = commands.processor

    @Before
    fun setup() {
        coEvery { accountRepository.findAll() } returns listOf(cash, bank, dollars)
        coEvery { categoryRepository.findAll() } returns listOf(food)
        coEvery { tagRepository.findAll() } returns listOf(work)
        coEvery { currencyRepository.getBaseCurrency() } returns AssetCode.unsafe("EGP")
        every { exchangeRatesRepository.findAll() } returns flowOf(listOf(rate("EGP", "USD", 0.02)))
        coEvery { transactionRepository.save(capture(saved)) } returns Unit
        every { timeProvider.utcNow() } returns now
        every { timeProvider.getZoneId() } returns ZoneOffset.UTC
        every { timeProvider.localDateNow() } returns LocalDate.of(2026, 10, 30)
        every { timeProvider.localTimeNow() } returns LocalTime.of(10, 15)
    }

    private fun args(json: String): CommandArgs = CommandArgs(Json.parseToJsonElement(json).jsonObject)

    private fun run(method: String, json: String = "{}"): Either<String, JsonElement> =
        runBlocking { processor.execute(method, args(json)) }

    @Test
    fun `adds an expense with category, tags and date`() = runTest {
        val result = processor.execute(
            "add_transaction",
            args(
                """{"amount": "25.5", "account": "cash", "category": "FOOD", "title": "Lunch",
                "tags": ["work"], "date": "2026-10-28"}"""
            ),
        ).getOrNull()!!.jsonObject

        val expense = saved.captured
        expense.shouldBeInstanceOf<Expense>()
        expense.value.amount.value shouldBe 25.5
        expense.value.asset shouldBe AssetCode.unsafe("EGP")
        expense.account shouldBe cash.id
        expense.category shouldBe food.id
        expense.title?.value shouldBe "Lunch"
        expense.settled shouldBe true
        expense.time shouldBe Instant.parse("2026-10-28T10:15:00Z")
        expense.tags shouldBe listOf(work.id)
        coVerify { tagRepository.associateTagToEntity(AssociationId(expense.id.value), work.id) }

        result["type"]?.jsonPrimitive?.content shouldBe "expense"
        result["account"]?.jsonPrimitive?.content shouldBe "Cash"
        result["category"]?.jsonPrimitive?.content shouldBe "Food"
        result["date"]?.jsonPrimitive?.content shouldBe "2026-10-28T10:15"
    }

    @Test
    fun `adds an income now`() = runTest {
        processor.execute(
            "add_transaction",
            args("""{"type": "Income", "amount": 1000, "account": "Bank Misr"}"""),
        )

        val income = saved.captured
        income.shouldBeInstanceOf<Income>()
        income.account shouldBe bank.id
        income.time shouldBe now
    }

    @Test
    fun `converts an amount in another currency`() = runTest {
        processor.execute("add_transaction", args("""{"amount": 2, "currency": "usd", "account": "Cash"}"""))

        (saved.captured as Expense).value.amount.value shouldBe 100.0
    }

    @Test
    fun `adds a transfer between currencies`() = runTest {
        processor.execute(
            "add_transaction",
            args("""{"type": "transfer", "amount": 500, "account": "Cash", "to_account": "Dollars"}"""),
        )

        val transfer = saved.captured
        transfer.shouldBeInstanceOf<Transfer>()
        transfer.fromAccount shouldBe cash.id
        transfer.toAccount shouldBe dollars.id
        transfer.toValue.amount.value shouldBe 10.0
        transfer.toValue.asset shouldBe AssetCode.USD
    }

    @Test
    fun `transfer with the received amount`() = runTest {
        processor.execute(
            "add_transaction",
            args(
                """{"type": "transfer", "amount": 500, "account": "Cash", "to_account": "Dollars",
                "to_amount": 9.5}"""
            ),
        )

        (saved.captured as Transfer).toValue.amount.value shouldBe 9.5
    }

    @Test
    fun `explains what's wrong`() {
        run("add_transaction", """{"account": "Cash"}""").leftOrNull()!! shouldContain "Missing 'amount'"
        run("add_transaction", """{"amount": -5, "account": "Cash"}""").leftOrNull()!! shouldContain
            "positive"
        run("add_transaction", """{"amount": 5}""").leftOrNull()!! shouldContain
            "Accounts: Cash, Bank Misr, Dollars"
        run("add_transaction", """{"amount": 5, "account": "Wallet"}""").leftOrNull()!! shouldContain
            "Unknown account 'Wallet'"
        run("add_transaction", """{"amount": 5, "account": "Cash", "date": "last week"}""")
            .leftOrNull()!! shouldContain "Invalid date"
        run("add_transaction", """{"type": "transfer", "amount": 5, "account": "Cash", "to_account": "cash"}""")
            .leftOrNull()!! shouldContain "must be different"
        run("fly").leftOrNull()!! shouldContain "Unknown command 'fly'"
        coVerify(exactly = 0) { transactionRepository.save(any()) }
    }

    @Test
    fun `lists accounts with balances`() {
        coEvery { transactionRepository.findAll() } returns listOf(
            income(100.0, cash),
            expense(30.0, cash),
            expense(5.0, cash, settled = false),
        )

        val accounts = run("list_accounts").getOrNull()!!.jsonArray

        accounts.size shouldBe 3
        accounts[0].jsonObject["name"]?.jsonPrimitive?.content shouldBe "Cash"
        accounts[0].jsonObject["currency"]?.jsonPrimitive?.content shouldBe "EGP"
        accounts[0].jsonObject["balance"]?.jsonPrimitive?.content shouldBe "70.0"
    }

    @Test
    fun `lists transactions with totals`() {
        coEvery { transactionRepository.findAllBetween(any(), any()) } returns listOf(
            expense(30.0, cash),
            expense(1.0, dollars),
            income(100.0, cash),
        )
        coEvery { transactionRepository.findAllDueToBetween(any(), any()) } returns listOf(
            expense(7.0, cash, settled = false),
        )

        val result = run(
            "list_transactions",
            """{"from": "2026-10-01", "to": "today", "type": "expense", "limit": 1}""",
        ).getOrNull()!!.jsonObject

        // Two paid expenses and a planned one, which isn't in the totals.
        result["count"]?.jsonPrimitive?.content shouldBe "3"
        // 30 EGP + 1 USD (50 EGP)
        result["total_expenses"]?.jsonPrimitive?.content shouldBe "80.0"
        (result["transactions"] as JsonArray).size shouldBe 1
        coVerify {
            transactionRepository.findAllBetween(
                Instant.parse("2026-10-01T00:00:00Z"),
                Instant.parse("2026-10-30T23:59:59.999Z"),
            )
        }
    }

    @Test
    fun `help lists every command`() {
        val commands = run("help").getOrNull()!!.jsonArray
            .map { (it as JsonObject)["command"]?.jsonPrimitive?.content }

        commands shouldBe Command.entries.map { it.method }
    }

    private fun expense(amount: Double, account: Account, settled: Boolean = true): Expense =
        Expense(
            id = TransactionId(UUID.randomUUID()),
            title = null,
            description = null,
            category = null,
            time = now,
            settled = settled,
            metadata = metadata,
            tags = emptyList(),
            value = PositiveValue(
                PositiveDouble.unsafe(amount),
                account.asset,
            ),
            account = account.id,
        )

    private fun income(amount: Double, account: Account): Income = Income(
        id = TransactionId(UUID.randomUUID()),
        title = null,
        description = null,
        category = null,
        time = now,
        settled = true,
        metadata = metadata,
        tags = emptyList(),
        value = PositiveValue(
            PositiveDouble.unsafe(amount),
            account.asset,
        ),
        account = account.id,
    )
}
