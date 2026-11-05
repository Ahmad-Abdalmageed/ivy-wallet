package com.ivy.home

import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.shouldBe
import org.junit.Test
import java.util.Locale

class CashflowShareTest {
    @Test
    fun `share of the income that is left`() {
        cashflowShareOfIncome(income = 1308.06, expenses = 58.87)!! shouldBe (0.955 plusOrMinus 0.001)
    }

    @Test
    fun `negative when spending more than the income`() {
        cashflowShareOfIncome(income = 100.0, expenses = 120.0) shouldBe -0.2
    }

    @Test
    fun `no share without income`() {
        cashflowShareOfIncome(income = 0.0, expenses = 50.0) shouldBe null
    }

    @Test
    fun `formats a percentage with one decimal`() {
        formatPercent(0.95499, Locale.US) shouldBe "95.5%"
        formatPercent(-0.2, Locale.US) shouldBe "-20%"
    }
}
