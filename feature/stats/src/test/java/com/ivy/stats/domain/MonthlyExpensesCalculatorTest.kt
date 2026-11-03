package com.ivy.stats.domain

import com.ivy.data.model.AccountId
import com.ivy.data.model.CategoryId
import com.ivy.data.model.TagId
import com.ivy.data.model.primitive.AssetCode
import com.ivy.domain.usecase.exchange.CurrencyConverter
import io.kotest.matchers.shouldBe
import org.junit.Test
import java.time.LocalDate
import java.time.YearMonth

class MonthlyExpensesCalculatorTest {
    private val calculator = MonthlyExpensesCalculator()
    private val cash = account("Cash", "EUR")
    private val bank = account("Bank", "USD")
    private val food = category("Food")
    private val rent = category("Rent")
    private val work = tag("Work")
    private val trip = tag("Trip")
    private val converter = CurrencyConverter(
        rates = listOf(rate("EUR", "USD", 2.0)),
        preferredBase = AssetCode.EUR,
    )
    private val period = StatsPeriod(from = YearMonth.of(2026, 8), to = YearMonth.of(2026, 10))
    private val everything = StatsFilter(
        accounts = setOf(cash.id, bank.id),
        categories = setOf(food.id, rent.id, null),
        tags = setOf(work.id, trip.id, null),
    )

    private val transactions = listOf(
        expense(10.0, cash, LocalDate.of(2026, 8, 1), category = food),
        expense(20.0, bank, LocalDate.of(2026, 8, 31), category = rent, tags = listOf(work)),
        expense(5.0, cash, LocalDate.of(2026, 10, 15), tags = listOf(trip, work)),
        // ignored: outside the period, not settled, not an expense
        expense(100.0, cash, LocalDate.of(2026, 7, 31)),
        expense(100.0, cash, LocalDate.of(2026, 10, 20), settled = false),
        income(100.0, cash, LocalDate.of(2026, 9, 1)),
    )

    private fun totals(filter: StatsFilter, currency: AssetCode = AssetCode.EUR): List<Double> =
        calculator.calculate(transactions, filter, period, currency, converter, Zone)
            .months.map { it.amount }

    @Test
    fun `totals every month of the period in the chosen currency`() {
        val result = calculator.calculate(
            transactions = transactions,
            filter = everything,
            period = period,
            currency = AssetCode.EUR,
            converter = converter,
            zone = Zone,
        )

        result.months.map { it.month } shouldBe listOf(
            YearMonth.of(2026, 8),
            YearMonth.of(2026, 9),
            YearMonth.of(2026, 10),
        )
        // 10 EUR + 20 USD (10 EUR)
        result.months.map { it.amount } shouldBe listOf(20.0, 0.0, 5.0)
        result.missingRates shouldBe emptySet()
    }

    @Test
    fun `converts to another currency`() {
        totals(everything, currency = AssetCode.USD) shouldBe listOf(40.0, 0.0, 10.0)
    }

    @Test
    fun `filters by account`() {
        totals(everything.copy(accounts = setOf(cash.id))) shouldBe listOf(10.0, 0.0, 5.0)
    }

    @Test
    fun `filters by category including uncategorized`() {
        totals(everything.copy(categories = setOf(food.id))) shouldBe listOf(10.0, 0.0, 0.0)
        totals(everything.copy(categories = setOf<CategoryId?>(null))) shouldBe listOf(0.0, 0.0, 5.0)
    }

    @Test
    fun `filters by tag including untagged`() {
        totals(everything.copy(tags = setOf(work.id))) shouldBe listOf(10.0, 0.0, 5.0)
        totals(everything.copy(tags = setOf(trip.id))) shouldBe listOf(0.0, 0.0, 5.0)
        totals(everything.copy(tags = setOf<TagId?>(null))) shouldBe listOf(10.0, 0.0, 0.0)
    }

    @Test
    fun `nothing selected`() {
        totals(everything.copy(accounts = emptySet<AccountId>())) shouldBe listOf(0.0, 0.0, 0.0)
    }

    @Test
    fun `reports currencies without exchange rate`() {
        val gold = account("Gold", "XAU")
        val result = calculator.calculate(
            transactions = transactions + expense(1.0, gold, LocalDate.of(2026, 9, 1)),
            filter = everything.copy(accounts = everything.accounts + gold.id),
            period = period,
            currency = AssetCode.EUR,
            converter = converter,
            zone = Zone,
        )

        result.missingRates shouldBe setOf(AssetCode.unsafe("XAU"))
        result.months[1].amount shouldBe 0.0
    }
}
