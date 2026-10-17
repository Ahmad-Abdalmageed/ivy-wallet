package com.ivy.voicecommands.review

import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.shouldBe
import org.junit.Test

class CurrencyConverterTest {
    // 1 EUR = 1.1 USD = 53 EGP
    private val converter = CurrencyConverter(
        baseCurrency = "EUR",
        rates = mapOf("USD" to 1.1, "EGP" to 53.0),
    )

    @Test
    fun `same currency`() {
        converter.convert(100.0, from = "USD", to = "USD") shouldBe 100.0
    }

    @Test
    fun `from and to the base currency`() {
        converter.convert(10.0, from = "EUR", to = "EGP") shouldBe (530.0 plusOrMinus 0.001)
        converter.convert(530.0, from = "EGP", to = "EUR") shouldBe (10.0 plusOrMinus 0.001)
    }

    @Test
    fun `between two other currencies`() {
        converter.convert(1.1, from = "USD", to = "EGP") shouldBe (53.0 plusOrMinus 0.001)
    }

    @Test
    fun `missing rate`() {
        converter.convert(10.0, from = "USD", to = "GBP") shouldBe null
    }
}
