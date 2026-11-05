package com.ivy.commands.command

import arrow.core.raise.Raise
import arrow.core.raise.ensure
import com.ivy.base.legacy.SharedPrefs
import com.ivy.data.model.ExchangeRate
import com.ivy.data.model.primitive.PositiveDouble
import com.ivy.domain.usecase.exchange.SyncExchangeRatesUseCase
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import javax.inject.Inject

/**
 * The base currency, the start day of the budget month and the exchange rates.
 */
class SettingsCommands @Inject constructor(
    deps: CommandDeps,
    private val syncExchangeRates: SyncExchangeRatesUseCase,
) : CommandGroup(deps) {
    override val commands = setOf(
        Command.GetSettings,
        Command.UpdateSettings,
        Command.ListExchangeRates,
        Command.SetExchangeRate,
        Command.DeleteExchangeRate,
    )

    override suspend fun Raise<String>.execute(command: Command, args: CommandArgs): JsonElement =
        when (command) {
            Command.GetSettings -> settings()
            Command.UpdateSettings -> updateSettings(args)
            Command.ListExchangeRates -> exchangeRates()
            Command.SetExchangeRate -> setExchangeRate(args)
            else -> {
                val base = deps.currencyRepository.getBaseCurrency()
                val currency = currency(required(args, "currency"))
                deps.exchangeRatesRepository.deleteByBaseCurrencyAndCurrency(base, currency)
                buildJsonObject { put("deleted", "${base.code} → ${currency.code}") }
            }
        }

    private suspend fun settings(): JsonObject {
        val (from, to) = budgetMonth()
        return buildJsonObject {
            put("base_currency", deps.currencyRepository.getBaseCurrency().code)
            put("start_day_of_month", startDayOfMonth())
            put("budget_month_from", from.toString())
            put("budget_month_to", to.toString())
        }
    }

    private suspend fun Raise<String>.updateSettings(args: CommandArgs): JsonElement {
        val startDay = args.int("start_day_of_month")
        val baseCurrency = args.string("base_currency")?.let { currency(it) }
        ensure(startDay != null || baseCurrency != null) { "Give 'base_currency' or 'start_day_of_month'." }
        if (startDay != null) {
            ensure(startDay in 1..MAX_DAY) { "'start_day_of_month' must be from 1 to $MAX_DAY." }
            deps.sharedPrefs.putInt(SharedPrefs.START_DATE_OF_MONTH, startDay)
        }
        var synced: Boolean? = null
        if (baseCurrency != null) {
            deps.currencyRepository.setBaseBaseCurrency(baseCurrency)
            synced = runCatching { syncExchangeRates.sync(baseCurrency).isRight() }.getOrDefault(false)
        }
        return buildJsonObject {
            settings().forEach { (key, value) -> put(key, value) }
            synced?.let { put("exchange_rates_synced", it) }
        }
    }

    private suspend fun exchangeRates(): JsonElement {
        val base = deps.currencyRepository.getBaseCurrency()
        val rates = deps.exchangeRatesRepository.findAll().first()
            .filter { it.baseCurrency == base }
            .sortedBy { it.currency.code }
        return buildJsonObject {
            put("base_currency", base.code)
            putJsonArray("rates") {
                rates.forEach { rate ->
                    addJsonObject {
                        put("currency", rate.currency.code)
                        put("rate", rate.rate.value)
                        put("price", 1 / rate.rate.value)
                        put("manual", rate.manualOverride)
                    }
                }
            }
        }
    }

    private suspend fun Raise<String>.setExchangeRate(args: CommandArgs): JsonElement {
        val base = deps.currencyRepository.getBaseCurrency()
        val currency = currency(required(args, "currency"))
        ensure(currency != base) { "${currency.code} is the base currency." }
        val rate = args.double("rate")?.let { positive(it, "rate") }
            ?: args.double("price")?.let { PositiveDouble.from(1 / positive(it, "price").value).bind() }
            ?: raise("Give 'rate' or 'price'.")
        val exchangeRate = ExchangeRate(
            baseCurrency = base,
            currency = currency,
            rate = rate,
            manualOverride = true,
        )
        deps.exchangeRatesRepository.save(exchangeRate)
        return buildJsonObject {
            put("base_currency", base.code)
            put("currency", currency.code)
            put("rate", rate.value)
            put("price", 1 / rate.value)
            put("manual", true)
        }
    }

    companion object {
        private const val MAX_DAY = 31
    }
}
