package com.ivy.commands.command

import com.ivy.base.legacy.SharedPrefs
import com.ivy.base.time.TimeProvider
import com.ivy.data.db.dao.read.BudgetDao
import com.ivy.data.db.dao.read.LoanDao
import com.ivy.data.db.dao.read.LoanRecordDao
import com.ivy.data.db.dao.read.PlannedPaymentRuleDao
import com.ivy.data.db.dao.write.WriteBudgetDao
import com.ivy.data.db.dao.write.WriteLoanDao
import com.ivy.data.db.dao.write.WriteLoanRecordDao
import com.ivy.data.db.dao.write.WritePlannedPaymentRuleDao
import com.ivy.data.repository.AccountRepository
import com.ivy.data.repository.CategoryRepository
import com.ivy.data.repository.CurrencyRepository
import com.ivy.data.repository.ExchangeRatesRepository
import com.ivy.data.repository.TagRepository
import com.ivy.data.repository.TransactionRepository
import com.ivy.domain.usecase.exchange.SyncExchangeRatesUseCase
import io.mockk.mockk

/**
 * A [CommandProcessor] with every dependency mocked.
 */
class TestCommands {
    val accountRepository = mockk<AccountRepository>(relaxUnitFun = true)
    val categoryRepository = mockk<CategoryRepository>(relaxUnitFun = true)
    val tagRepository = mockk<TagRepository>(relaxUnitFun = true)
    val transactionRepository = mockk<TransactionRepository>(relaxUnitFun = true)
    val exchangeRatesRepository = mockk<ExchangeRatesRepository>(relaxUnitFun = true)
    val currencyRepository = mockk<CurrencyRepository>(relaxUnitFun = true)
    val timeProvider = mockk<TimeProvider>()
    val sharedPrefs = mockk<SharedPrefs>(relaxUnitFun = true)
    val budgetDao = mockk<BudgetDao>()
    val budgetWriter = mockk<WriteBudgetDao>(relaxUnitFun = true)
    val ruleDao = mockk<PlannedPaymentRuleDao>()
    val ruleWriter = mockk<WritePlannedPaymentRuleDao>(relaxUnitFun = true)
    val loanDao = mockk<LoanDao>()
    val loanWriter = mockk<WriteLoanDao>(relaxUnitFun = true)
    val recordDao = mockk<LoanRecordDao>()
    val recordWriter = mockk<WriteLoanRecordDao>(relaxUnitFun = true)
    val syncExchangeRates = mockk<SyncExchangeRatesUseCase>()

    private val deps = CommandDeps(
        accountRepository = accountRepository,
        categoryRepository = categoryRepository,
        tagRepository = tagRepository,
        exchangeRatesRepository = exchangeRatesRepository,
        currencyRepository = currencyRepository,
        timeProvider = timeProvider,
        sharedPrefs = sharedPrefs,
    )

    val processor = CommandProcessor(
        transactions = TransactionCommands(deps, transactionRepository),
        accounts = AccountCommands(deps, transactionRepository, ruleWriter),
        categories = CategoryCommands(deps),
        budgets = BudgetCommands(deps, budgetDao, budgetWriter, transactionRepository),
        plannedPayments = PlannedPaymentCommands(deps, ruleDao, ruleWriter, transactionRepository),
        loans = LoanCommands(deps, loanDao, loanWriter, recordDao, recordWriter, transactionRepository),
        settings = SettingsCommands(deps, syncExchangeRates),
    )
}
