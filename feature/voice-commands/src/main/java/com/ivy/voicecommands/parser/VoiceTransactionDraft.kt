package com.ivy.voicecommands.parser

import com.ivy.base.model.TransactionType
import com.ivy.data.model.Account
import com.ivy.data.model.AccountId
import com.ivy.data.model.Category
import com.ivy.data.model.CategoryId
import com.ivy.data.model.primitive.NotBlankTrimmedString
import com.ivy.data.model.primitive.PositiveDouble
import java.time.LocalDate

/**
 * A transaction understood from a voice command.
 * It's only a draft - the user confirms it in the edit transaction screen.
 */
data class VoiceTransactionDraft(
    val type: TransactionType,
    val amount: PositiveDouble,
    val title: NotBlankTrimmedString?,
    val category: CategoryId?,
    val account: AccountId?,
    /** Only for [TransactionType.TRANSFER]. */
    val toAccount: AccountId?,
    /** Null means "now". */
    val date: LocalDate?,
)

data class VoiceParserInput(
    val text: String,
    val categories: List<Category>,
    val accounts: List<Account>,
    val today: LocalDate,
)

sealed interface VoiceParseError {
    data object Blank : VoiceParseError
    data object NoAmount : VoiceParseError

    /** The AI provider failed and the offline parser couldn't understand the command either. */
    data class AiFailed(val message: String) : VoiceParseError
}
