package com.ivy.balance

import androidx.compose.runtime.Immutable
import com.ivy.legacy.data.model.TimePeriod
import com.ivy.legacy.domain.action.wallet.CurrencyBalance
import kotlinx.collections.immutable.ImmutableList

@Immutable
data class BalanceState(
    val period: TimePeriod,
    val baseCurrencyCode: String,
    val currentBalance: Double,
    val plannedPaymentsAmount: Double,
    val balanceAfterPlannedPayments: Double,
    /** Selected currency first, then the others; each in its own currency, not exchanged. */
    val balanceByCurrency: ImmutableList<CurrencyBalance>,
)
