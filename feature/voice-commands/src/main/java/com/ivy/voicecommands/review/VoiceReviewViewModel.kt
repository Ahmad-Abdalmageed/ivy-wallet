package com.ivy.voicecommands.review

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.viewModelScope
import com.ivy.base.model.TransactionType
import com.ivy.base.time.TimeProvider
import com.ivy.data.model.Account
import com.ivy.data.model.AccountId
import com.ivy.data.model.CategoryId
import com.ivy.data.model.primitive.NotBlankTrimmedString
import com.ivy.data.model.primitive.PositiveDouble
import com.ivy.data.repository.AccountRepository
import com.ivy.data.repository.CategoryRepository
import com.ivy.data.repository.CurrencyRepository
import com.ivy.data.repository.ExchangeRatesRepository
import com.ivy.ui.ComposeViewModel
import com.ivy.voicecommands.parser.LocalVoiceTransactionParser
import com.ivy.voicecommands.parser.NameMatcher
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant
import java.time.LocalDate
import javax.inject.Inject

@Stable
@HiltViewModel
class VoiceReviewViewModel @Inject constructor(
    pendingTransactions: PendingVoiceTransactions,
    private val accountRepository: AccountRepository,
    private val categoryRepository: CategoryRepository,
    private val currencyRepository: CurrencyRepository,
    private val exchangeRatesRepository: ExchangeRatesRepository,
    private val saver: VoiceTransactionsSaver,
    private val timeProvider: TimeProvider,
) : ComposeViewModel<VoiceReviewState, VoiceReviewEvent>() {

    private data class EditableItem(
        val checked: Boolean,
        val type: TransactionType,
        val title: NotBlankTrimmedString?,
        val category: CategoryId?,
        val amount: String,
        val account: AccountId?,
        val toAccount: AccountId?,
        /** Set when the user edits the received amount, otherwise it's converted. */
        val toAmount: String?,
        val date: LocalDate,
    )

    private val items = mutableStateListOf<EditableItem>()
    private var accounts by mutableStateOf<List<Account>>(emptyList())
    private var categoryNames by mutableStateOf<Map<CategoryId, String>>(emptyMap())
    private var baseCurrency by mutableStateOf("")
    private var converter by mutableStateOf<CurrencyConverter?>(null)
    private var saving by mutableStateOf(false)
    private var savedCount by mutableStateOf<Int?>(null)

    init {
        val today = timeProvider.localDateNow()
        items += pendingTransactions.drafts.map { draft ->
            EditableItem(
                checked = true,
                type = draft.type,
                title = draft.title,
                category = draft.category,
                amount = formatAmount(draft.amount.value),
                account = draft.account,
                toAccount = draft.toAccount,
                toAmount = null,
                date = draft.date ?: today,
            )
        }
        pendingTransactions.drafts = emptyList()
    }

    @Composable
    override fun uiState(): VoiceReviewState {
        LaunchedEffect(Unit) {
            load()
        }

        val reviewItems = items.mapIndexed(::toReviewItem)
        val checked = reviewItems.filter { it.checked }
        return VoiceReviewState(
            items = reviewItems.toImmutableList(),
            accounts = accounts.map(::toReviewAccount).toImmutableList(),
            checkedCount = checked.size,
            allChecked = checked.size == reviewItems.size,
            canSave = canSave(checked),
            saving = saving,
            savedCount = savedCount,
        )
    }

    override fun onEvent(event: VoiceReviewEvent) {
        when (event) {
            is VoiceReviewEvent.OnCheckedChange -> update(event.index) { copy(checked = event.checked) }
            is VoiceReviewEvent.OnAllCheckedChange -> items.indices.forEach { index ->
                update(index) { copy(checked = event.checked) }
            }

            is VoiceReviewEvent.OnTypeChange -> update(event.index) { copy(type = event.type, toAmount = null) }
            is VoiceReviewEvent.OnAmountChange -> update(event.index) { copy(amount = event.amount, toAmount = null) }
            is VoiceReviewEvent.OnToAmountChange -> update(event.index) { copy(toAmount = event.amount) }
            is VoiceReviewEvent.OnAccountChange -> update(event.index) {
                copy(account = event.account, toAmount = null)
            }

            is VoiceReviewEvent.OnToAccountChange -> update(event.index) {
                copy(toAccount = event.account, toAmount = null)
            }

            is VoiceReviewEvent.OnDateChange -> update(event.index) { copy(date = event.date) }
            VoiceReviewEvent.OnSaveClick -> onSaveClick()
        }
    }

    private suspend fun load() {
        accounts = accountRepository.findAll()
        categoryNames = categoryRepository.findAll().associate { it.id to it.name.value }
        val base = currencyRepository.getBaseCurrency().code
        val rates = exchangeRatesRepository.findAll().first()
            .filter { it.baseCurrency.code == base }
            .associate { it.currency.code to it.rate.value }
        baseCurrency = base
        converter = CurrencyConverter(baseCurrency = base, rates = rates)
    }

    private fun update(index: Int, transform: EditableItem.() -> EditableItem) {
        items.getOrNull(index)?.let { items[index] = it.transform() }
    }

    private fun toReviewItem(index: Int, item: EditableItem): VoiceReviewItem {
        val account = findAccount(item.account)
        val toAccount = findAccount(item.toAccount).takeIf { item.type == TransactionType.TRANSFER }
        val toAmount = toAmount(item, account, toAccount)
        return VoiceReviewItem(
            index = index,
            checked = item.checked,
            type = item.type,
            description = item.title?.value ?: item.category?.let(categoryNames::get),
            amount = item.amount,
            currency = account?.asset?.code ?: baseCurrency,
            account = account?.let(::toReviewAccount),
            toAccount = toAccount?.let(::toReviewAccount),
            toAmount = toAmount,
            date = item.date,
            error = validate(item, account, toAccount, toAmount),
        )
    }

    /**
     * The amount the receiving account gets, for transfers between different currencies.
     */
    private fun toAmount(item: EditableItem, account: Account?, toAccount: Account?): String? {
        if (account == null || toAccount == null || account.asset == toAccount.asset) return null
        return item.toAmount ?: parseAmount(item.amount)?.let { amount ->
            val converted = converter?.convert(
                amount = amount,
                from = account.asset.code,
                to = toAccount.asset.code,
            )
            formatAmount(converted ?: amount)
        }.orEmpty()
    }

    private fun validate(
        item: EditableItem,
        account: Account?,
        toAccount: Account?,
        toAmount: String?,
    ): ReviewError? {
        val transfer = item.type == TransactionType.TRANSFER
        return when {
            parseAmount(item.amount) == null -> ReviewError.Amount
            account == null -> ReviewError.Account
            transfer && toAccount == null -> ReviewError.ToAccount
            transfer && toAccount?.id == account.id -> ReviewError.SameAccounts
            toAmount != null && parseAmount(toAmount) == null -> ReviewError.ToAmount
            else -> null
        }
    }

    private fun canSave(checked: List<VoiceReviewItem>): Boolean =
        checked.isNotEmpty() && checked.all { it.error == null } && !saving && savedCount == null

    private fun onSaveClick() {
        val reviewItems = items.mapIndexed(::toReviewItem)
        if (!canSave(reviewItems.filter { it.checked })) return
        saving = true
        viewModelScope.launch {
            val transactions = items.zip(reviewItems)
                .filter { (item, _) -> item.checked }
                .mapNotNull { (item, reviewItem) -> toReviewedTransaction(item, reviewItem) }
            saver.save(transactions)
            savedCount = transactions.size
            saving = false
        }
    }

    private fun toReviewedTransaction(
        item: EditableItem,
        reviewItem: VoiceReviewItem,
    ): ReviewedTransaction? {
        val amount = parseAmount(item.amount)?.let { PositiveDouble.from(it).getOrNull() }
        val account = findAccount(item.account)
        return if (amount == null || account == null) {
            null
        } else {
            ReviewedTransaction(
                type = item.type,
                amount = amount,
                title = item.title,
                category = item.category.takeIf { item.type != TransactionType.TRANSFER },
                account = account,
                toAccount = findAccount(item.toAccount).takeIf { item.type == TransactionType.TRANSFER },
                toAmount = reviewItem.toAmount
                    ?.let(::parseAmount)
                    ?.let { PositiveDouble.from(it).getOrNull() },
                time = time(item.date),
            )
        }
    }

    /**
     * Now for today's transactions, the current time of the day for past dates.
     */
    private fun time(date: LocalDate): Instant = if (date == timeProvider.localDateNow()) {
        timeProvider.utcNow()
    } else {
        date.atTime(timeProvider.localTimeNow()).atZone(timeProvider.getZoneId()).toInstant()
    }

    private fun findAccount(id: AccountId?): Account? = id?.let { accounts.find { account -> account.id == it } }

    private fun toReviewAccount(account: Account): ReviewAccount = ReviewAccount(
        id = account.id,
        name = account.name.value,
        currency = account.asset.code,
    )

    private fun parseAmount(text: String): Double? =
        LocalVoiceTransactionParser.parseAmount(NameMatcher.normalize(text).replace(" ", ""))

    private fun formatAmount(amount: Double): String = BigDecimal(amount)
        .setScale(2, RoundingMode.HALF_UP)
        .stripTrailingZeros()
        .toPlainString()
}
