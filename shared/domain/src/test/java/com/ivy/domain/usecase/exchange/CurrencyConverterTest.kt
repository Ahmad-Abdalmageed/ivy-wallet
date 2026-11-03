package com.ivy.domain.usecase.exchange

import com.ivy.data.model.ExchangeRate
import com.ivy.data.model.primitive.AssetCode
import com.ivy.data.model.primitive.PositiveDouble
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.shouldBe
import org.junit.Test

class CurrencyConverterTest {
    private val eur = AssetCode.EUR
    private val usd = AssetCode.USD
    private val egp = AssetCode.unsafe("EGP")

    // 1 EUR = 1.1 USD = 53 EGP
    private val converter = CurrencyConverter(
        rates = listOf(rate("EUR", "USD", 1.1), rate("EUR", "EGP", 53.0)),
        preferredBase = eur,
    )

    @Test
    fun `same currency`() {
        converter.convert(10.0, egp, egp) shouldBe 10.0
    }

    @Test
    fun `from and to the base currency`() {
        converter.convert(10.0, eur, usd)!! shouldBe (11.0 plusOrMinus 0.0001)
        converter.convert(53.0, egp, eur)!! shouldBe (1.0 plusOrMinus 0.0001)
    }

    @Test
    fun `between two other currencies`() {
        converter.convert(530.0, egp, usd)!! shouldBe (11.0 plusOrMinus 0.0001)
    }

    @Test
    fun `uses the rates of another base currency`() {
        val converter = CurrencyConverter(
            rates = listOf(rate("EUR", "USD", 1.1), rate("EUR", "EGP", 53.0)),
            preferredBase = usd,
        )

        converter.convert(1.1, usd, egp)!! shouldBe (53.0 plusOrMinus 0.0001)
    }

    @Test
    fun `unknown rate`() {
        converter.convert(1.0, AssetCode.unsafe("BTC"), eur) shouldBe null
    }

    private fun rate(base: String, currency: String, rate: Double): ExchangeRate = ExchangeRate(
        baseCurrency = AssetCode.unsafe(base),
        currency = AssetCode.unsafe(currency),
        rate = PositiveDouble.unsafe(rate),
        manualOverride = false,
    )
}
