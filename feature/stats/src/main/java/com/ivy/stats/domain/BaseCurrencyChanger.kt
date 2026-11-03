package com.ivy.stats.domain

import com.ivy.data.db.dao.read.SettingsDao
import com.ivy.data.db.dao.write.WriteSettingsDao
import com.ivy.data.model.primitive.AssetCode
import com.ivy.domain.usecase.exchange.SyncExchangeRatesUseCase
import javax.inject.Inject

/**
 * Changes the app's base currency the same way Settings does.
 */
class BaseCurrencyChanger @Inject constructor(
    private val settingsDao: SettingsDao,
    private val settingsWriter: WriteSettingsDao,
    private val syncExchangeRatesUseCase: SyncExchangeRatesUseCase,
) {
    suspend fun change(currency: AssetCode) {
        val settings = settingsDao.findFirstOrNull() ?: return
        settingsWriter.save(settings.copy(currency = currency.code))
        syncExchangeRatesUseCase.sync(currency)
    }
}
