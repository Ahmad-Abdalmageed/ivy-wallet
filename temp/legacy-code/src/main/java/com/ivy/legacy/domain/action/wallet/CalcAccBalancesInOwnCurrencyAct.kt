package com.ivy.legacy.domain.action.wallet

import com.ivy.data.model.Account
import com.ivy.data.model.AccountId
import com.ivy.data.model.primitive.AssetCode
import com.ivy.data.model.primitive.ColorInt
import com.ivy.data.model.primitive.IconAsset
import com.ivy.data.model.primitive.NotBlankTrimmedString
import com.ivy.frp.action.FPAction
import com.ivy.frp.action.thenFilter
import com.ivy.frp.action.thenMap
import com.ivy.frp.fixUnit
import com.ivy.wallet.domain.action.account.AccountsAct
import com.ivy.wallet.domain.action.account.CalcAccBalanceAct
import java.math.BigDecimal
import javax.inject.Inject

/**
 * The balance of every account included in the total balance, in the account's own currency
 * (no exchange), as (currency code, balance) pairs.
 */
class CalcAccBalancesInOwnCurrencyAct @Inject constructor(
    private val accountsAct: AccountsAct,
    private val calcAccBalanceAct: CalcAccBalanceAct,
) : FPAction<CalcAccBalancesInOwnCurrencyAct.Input, List<Pair<String, BigDecimal>>>() {

    override suspend fun Input.compose(): suspend () -> List<Pair<String, BigDecimal>> =
        recipe().fixUnit()

    private suspend fun Input.recipe(): suspend (Unit) -> List<Pair<String, BigDecimal>> =
        accountsAct thenFilter {
            it.includeInBalance
        } thenMap { account ->
            calcAccBalanceAct(
                CalcAccBalanceAct.Input(
                    account = Account(
                        id = AccountId(account.id),
                        name = NotBlankTrimmedString.from(account.name).getOrNull()
                            ?: error("account name cannot be blank"),
                        asset = AssetCode.from(account.currency ?: baseCurrency).getOrNull()
                            ?: error("account currency cannot be blank"),
                        color = ColorInt(account.color),
                        icon = account.icon?.let { IconAsset.from(it).getOrNull() },
                        includeInBalance = account.includeInBalance,
                        orderNum = account.orderNum,
                    ),
                )
            )
        } thenMap {
            it.account.asset.code to it.balance
        }

    data class Input(
        val baseCurrency: String,
    )
}
