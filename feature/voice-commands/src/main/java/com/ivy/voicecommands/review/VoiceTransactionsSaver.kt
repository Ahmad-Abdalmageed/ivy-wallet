package com.ivy.voicecommands.review

import com.ivy.base.legacy.refreshWidget
import com.ivy.base.model.TransactionType
import com.ivy.base.time.TimeProvider
import com.ivy.data.model.Account
import com.ivy.data.model.CategoryId
import com.ivy.data.model.Expense
import com.ivy.data.model.Income
import com.ivy.data.model.PositiveValue
import com.ivy.data.model.Transaction
import com.ivy.data.model.TransactionId
import com.ivy.data.model.TransactionMetadata
import com.ivy.data.model.Transfer
import com.ivy.data.model.primitive.NotBlankTrimmedString
import com.ivy.data.model.primitive.PositiveDouble
import com.ivy.data.repository.TransactionRepository
import com.ivy.widget.balance.WalletBalanceWidgetReceiver
import java.time.Instant
import java.util.UUID
import javax.inject.Inject

/**
 * A transaction confirmed in the voice command review screen.
 */
data class ReviewedTransaction(
    val type: TransactionType,
    val amount: PositiveDouble,
    val title: NotBlankTrimmedString?,
    val category: CategoryId?,
    val account: Account,
    /** Only for [TransactionType.TRANSFER]. */
    val toAccount: Account?,
    /** Only for [TransactionType.TRANSFER], in the currency of [toAccount]. */
    val toAmount: PositiveDouble?,
    val time: Instant,
)

class VoiceTransactionsSaver @Inject constructor(
    private val transactionRepository: TransactionRepository,
    private val timeProvider: TimeProvider,
) {
    suspend fun save(transactions: List<ReviewedTransaction>) {
        transactionRepository.saveMany(transactions.map { it.toDomain() })
        refreshWidget(WalletBalanceWidgetReceiver::class.java)
    }

    private fun ReviewedTransaction.toDomain(): Transaction {
        val id = TransactionId(UUID.randomUUID())
        val settled = !time.isAfter(timeProvider.utcNow())
        val metadata = TransactionMetadata(
            recurringRuleId = null,
            paidForDateTime = null,
            loanId = null,
            loanRecordId = null,
        )
        val value = PositiveValue(amount = amount, asset = account.asset)
        return when (type) {
            TransactionType.EXPENSE -> Expense(
                id = id,
                title = title,
                description = null,
                category = category,
                time = time,
                settled = settled,
                metadata = metadata,
                tags = emptyList(),
                value = value,
                account = account.id,
            )

            TransactionType.INCOME -> Income(
                id = id,
                title = title,
                description = null,
                category = category,
                time = time,
                settled = settled,
                metadata = metadata,
                tags = emptyList(),
                value = value,
                account = account.id,
            )

            TransactionType.TRANSFER -> {
                val receiver = requireNotNull(toAccount) { "A transfer needs a receiving account" }
                Transfer(
                    id = id,
                    title = title,
                    description = null,
                    category = null,
                    time = time,
                    settled = settled,
                    metadata = metadata,
                    tags = emptyList(),
                    fromAccount = account.id,
                    fromValue = value,
                    toAccount = receiver.id,
                    toValue = PositiveValue(amount = toAmount ?: amount, asset = receiver.asset),
                )
            }
        }
    }
}
