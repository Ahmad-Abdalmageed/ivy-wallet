package com.ivy.voicecommands.review

import androidx.compose.runtime.Immutable
import com.ivy.base.model.TransactionType
import com.ivy.data.model.AccountId
import kotlinx.collections.immutable.ImmutableList
import java.time.LocalDate

@Immutable
data class VoiceReviewState(
    val items: ImmutableList<VoiceReviewItem>,
    val accounts: ImmutableList<ReviewAccount>,
    val checkedCount: Int,
    val allChecked: Boolean,
    val canSave: Boolean,
    val saving: Boolean,
    /** Set once the checked transactions are saved, with how many were saved. */
    val savedCount: Int?,
)

@Immutable
data class VoiceReviewItem(
    val index: Int,
    val checked: Boolean,
    val type: TransactionType,
    /** The title, or the category when there's no title. */
    val description: String?,
    val amount: String,
    val currency: String,
    val account: ReviewAccount?,
    /** Only for transfers. */
    val toAccount: ReviewAccount?,
    /** Only for transfers between accounts in different currencies. */
    val toAmount: String?,
    val date: LocalDate,
    val error: ReviewError?,
)

// Refers to an existing account, it doesn't need an ID type of its own
@Suppress("DataClassTypedIDs")
@Immutable
data class ReviewAccount(
    val id: AccountId,
    val name: String,
    val currency: String,
)

enum class ReviewError {
    Amount,
    Account,
    ToAccount,
    SameAccounts,
    ToAmount,
}
