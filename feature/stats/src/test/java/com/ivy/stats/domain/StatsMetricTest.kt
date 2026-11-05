package com.ivy.stats.domain

import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.shouldBe
import org.junit.Test
import java.time.YearMonth

class StatsMetricTest {
    private val october = MonthTotal(month = YearMonth.of(2026, 10), expenses = 58.87, income = 1308.06)

    @Test
    fun `expenses and income`() {
        StatsMetric.Expenses.valueOf(october) shouldBe 58.87
        StatsMetric.Income.valueOf(october) shouldBe 1308.06
    }

    @Test
    fun `cashflow as a share of income`() {
        StatsMetric.Cashflow.valueOf(october)!! shouldBe (0.955 plusOrMinus 0.001)
        cashflowShare(income = 100.0, expenses = 120.0) shouldBe -0.2
    }

    @Test
    fun `no cashflow share without income`() {
        StatsMetric.Cashflow.valueOf(october.copy(income = 0.0)) shouldBe null
    }
}
