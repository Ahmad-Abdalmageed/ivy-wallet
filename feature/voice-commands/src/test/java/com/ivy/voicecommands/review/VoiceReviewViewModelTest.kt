package com.ivy.voicecommands.review

import com.ivy.base.model.TransactionType
import com.ivy.base.time.TimeProvider
import com.ivy.data.model.Account
import com.ivy.data.model.AccountId
import com.ivy.data.model.ExchangeRate
import com.ivy.data.model.primitive.AssetCode
import com.ivy.data.model.primitive.ColorInt
import com.ivy.data.model.primitive.NotBlankTrimmedString
import com.ivy.data.model.primitive.PositiveDouble
import com.ivy.data.repository.AccountRepository
import com.ivy.data.repository.CategoryRepository
import com.ivy.data.repository.CurrencyRepository
import com.ivy.data.repository.ExchangeRatesRepository
import com.ivy.ui.testing.ComposeViewModelTest
import com.ivy.ui.testing.runTest
import com.ivy.voicecommands.parser.Today
import com.ivy.voicecommands.parser.VoiceTransactionDraft
import com.ivy.voicecommands.parser.category
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.flow.flowOf
import org.junit.Before
import org.junit.Test
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneOffset
import java.util.UUID

class VoiceReviewViewModelTest : ComposeViewModelTest() {
    private val cash = account("Cash", "EGP")
    private val bank = account("Bank", "EGP")
    private val usd = account("USD Savings", "USD")
    private val coffee = category("Coffee")
    private val now = Instant.parse("2026-10-17T12:00:00Z")

    private val pending = PendingVoiceTransactions()
    private val accountRepository = mockk<AccountRepository>()
    private val categoryRepository = mockk<CategoryRepository>()
    private val currencyRepository = mockk<CurrencyRepository>()
    private val exchangeRatesRepository = mockk<ExchangeRatesRepository>()
    private val saver = mockk<VoiceTransactionsSaver>()
    private val timeProvider = mockk<TimeProvider>()
    private val saved = slot<List<ReviewedTransaction>>()

    private val coffeeDraft = draft(
        type = TransactionType.EXPENSE,
        amount = 45.0,
        account = cash.id,
    ).copy(category = coffee.id)
    private val transferDraft = draft(
        type = TransactionType.TRANSFER,
        amount = 530.0,
        account = cash.id,
        toAccount = usd.id,
    ).copy(date = Today.minusDays(1))
    private val incomeDraft = draft(type = TransactionType.INCOME, amount = 1000.0, account = null)

    @Before
    fun setup() {
        coEvery { accountRepository.findAll() } returns listOf(cash, bank, usd)
        coEvery { categoryRepository.findAll() } returns listOf(coffee)
        coEvery { currencyRepository.getBaseCurrency() } returns AssetCode.unsafe("EUR")
        every { exchangeRatesRepository.findAll() } returns flowOf(
            listOf(rate("USD", 1.1), rate("EGP", 53.0))
        )
        coEvery { saver.save(capture(saved)) } returns Unit
        every { timeProvider.localDateNow() } returns Today
        every { timeProvider.utcNow() } returns now
        every { timeProvider.localTimeNow() } returns LocalTime.of(12, 0)
        every { timeProvider.getZoneId() } returns ZoneOffset.UTC
    }

    private fun viewModel(vararg drafts: VoiceTransactionDraft): VoiceReviewViewModel {
        pending.drafts = drafts.toList()
        return VoiceReviewViewModel(
            pendingTransactions = pending,
            accountRepository = accountRepository,
            categoryRepository = categoryRepository,
            currencyRepository = currencyRepository,
            exchangeRatesRepository = exchangeRatesRepository,
            saver = saver,
            timeProvider = timeProvider,
        )
    }

    @Test
    fun `shows the transactions checked, with their accounts and currencies`() {
        viewModel(coffeeDraft, transferDraft).runTest {
            items.size shouldBe 2
            checkedCount shouldBe 2
            allChecked shouldBe true
            canSave shouldBe true

            items[0].description shouldBe "Coffee"
            items[0].amount shouldBe "45"
            items[0].currency shouldBe "EGP"
            items[0].account?.name shouldBe "Cash"
            items[0].date shouldBe Today
            items[0].toAmount shouldBe null

            items[1].type shouldBe TransactionType.TRANSFER
            items[1].toAccount?.name shouldBe "USD Savings"
            // 530 EGP = 10 EUR = 11 USD
            items[1].toAmount shouldBe "11"
            items[1].date shouldBe Today.minusDays(1)
        }
        pending.drafts shouldBe emptyList()
    }

    @Test
    fun `a transaction without an account can't be saved until one is chosen`() {
        val viewModel = viewModel(coffeeDraft, incomeDraft)

        viewModel.runTest {
            items[1].error shouldBe ReviewError.Account
            canSave shouldBe false
        }
        viewModel.runTest(
            events = listOf(VoiceReviewEvent.OnAccountChange(index = 1, account = bank.id))
        ) {
            items[1].error shouldBe null
            canSave shouldBe true
        }
    }

    @Test
    fun `unchecked transactions are ignored`() {
        viewModel(coffeeDraft, incomeDraft).runTest(
            events = listOf(VoiceReviewEvent.OnCheckedChange(index = 1, checked = false))
        ) {
            checkedCount shouldBe 1
            allChecked shouldBe false
            canSave shouldBe true
        }
    }

    @Test
    fun `invalid amount`() {
        viewModel(coffeeDraft).runTest(
            events = listOf(VoiceReviewEvent.OnAmountChange(index = 0, amount = "abc"))
        ) {
            items[0].error shouldBe ReviewError.Amount
            canSave shouldBe false
        }
    }

    @Test
    fun `transfer to the same account`() {
        viewModel(transferDraft).runTest(
            events = listOf(VoiceReviewEvent.OnToAccountChange(index = 0, account = cash.id))
        ) {
            items[0].error shouldBe ReviewError.SameAccounts
        }
    }

    @Test
    fun `edits the type, date and amount`() {
        viewModel(coffeeDraft).runTest(
            events = listOf(
                VoiceReviewEvent.OnTypeChange(index = 0, type = TransactionType.INCOME),
                VoiceReviewEvent.OnDateChange(index = 0, date = Today.minusDays(3)),
                VoiceReviewEvent.OnAmountChange(index = 0, amount = "٥٠٫٥"),
            )
        ) {
            items[0].type shouldBe TransactionType.INCOME
            items[0].date shouldBe Today.minusDays(3)
            items[0].error shouldBe null
        }
    }

    @Test
    fun `saves only the checked transactions`() {
        viewModel(coffeeDraft, transferDraft, incomeDraft).runTest(
            events = listOf(
                VoiceReviewEvent.OnCheckedChange(index = 2, checked = false),
                VoiceReviewEvent.OnToAmountChange(index = 1, amount = "10.5"),
                VoiceReviewEvent.OnSaveClick,
            )
        ) {
            savedCount shouldBe 2
        }

        coVerify(exactly = 1) { saver.save(any()) }
        val (coffeeTransaction, transfer) = saved.captured
        coffeeTransaction.type shouldBe TransactionType.EXPENSE
        coffeeTransaction.amount.value shouldBe 45.0
        coffeeTransaction.category shouldBe coffee.id
        coffeeTransaction.account shouldBe cash
        coffeeTransaction.time shouldBe now

        transfer.type shouldBe TransactionType.TRANSFER
        transfer.account shouldBe cash
        transfer.toAccount shouldBe usd
        transfer.toAmount?.value shouldBe 10.5
        transfer.time shouldBe Today.minusDays(1).atTime(12, 0).toInstant(ZoneOffset.UTC)
    }

    private fun draft(
        type: TransactionType,
        amount: Double,
        account: AccountId?,
        toAccount: AccountId? = null,
    ): VoiceTransactionDraft = VoiceTransactionDraft(
        type = type,
        amount = PositiveDouble.unsafe(amount),
        title = null,
        category = null,
        account = account,
        toAccount = toAccount,
        date = null,
    )

    private fun account(name: String, currency: String): Account = Account(
        id = AccountId(UUID.randomUUID()),
        name = NotBlankTrimmedString.unsafe(name),
        asset = AssetCode.unsafe(currency),
        color = ColorInt(0),
        icon = null,
        includeInBalance = true,
        orderNum = 0.0,
    )

    private fun rate(currency: String, rate: Double): ExchangeRate = ExchangeRate(
        baseCurrency = AssetCode.unsafe("EUR"),
        currency = AssetCode.unsafe(currency),
        rate = PositiveDouble.unsafe(rate),
        manualOverride = false,
    )
}
