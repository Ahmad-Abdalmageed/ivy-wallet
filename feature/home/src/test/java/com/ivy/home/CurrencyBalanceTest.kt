package com.ivy.home

import io.kotest.matchers.shouldBe
import org.junit.Test
import java.math.BigDecimal

class CurrencyBalanceTest {
    @Test
    fun `sums each currency on its own without exchanging`() {
        balancesByCurrency(
            accountBalances = listOf(
                "USD" to BigDecimal("600"),
                "EGP" to BigDecimal("50"),
                "USD" to BigDecimal("400"),
            ),
            selectedCurrency = "USD",
        ) shouldBe listOf(
            CurrencyBalance("USD", BigDecimal("1000")),
            CurrencyBalance("EGP", BigDecimal("50")),
        )
    }

    @Test
    fun `selected currency first, others alphabetically`() {
        balancesByCurrency(
            accountBalances = listOf(
                "USD" to BigDecimal("1"),
                "EUR" to BigDecimal("2"),
                "EGP" to BigDecimal("3"),
            ),
            selectedCurrency = "EUR",
        ).map { it.currency } shouldBe listOf("EUR", "EGP", "USD")
    }

    @Test
    fun `selected currency shown as zero when no account holds it`() {
        balancesByCurrency(
            accountBalances = listOf("EGP" to BigDecimal("50")),
            selectedCurrency = "USD",
        ) shouldBe listOf(
            CurrencyBalance("USD", BigDecimal.ZERO),
            CurrencyBalance("EGP", BigDecimal("50")),
        )
    }

    @Test
    fun `other currencies that add up to zero are left out`() {
        balancesByCurrency(
            accountBalances = listOf(
                "USD" to BigDecimal("10"),
                "EGP" to BigDecimal("25.50"),
                "EGP" to BigDecimal("-25.5"),
            ),
            selectedCurrency = "USD",
        ) shouldBe listOf(CurrencyBalance("USD", BigDecimal("10")))
    }

    @Test
    fun `no accounts gives the selected currency at zero`() {
        balancesByCurrency(
            accountBalances = emptyList(),
            selectedCurrency = "USD",
        ) shouldBe listOf(CurrencyBalance("USD", BigDecimal.ZERO))
    }
}
