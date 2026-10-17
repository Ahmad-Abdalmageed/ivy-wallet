package com.ivy.voicecommands.review

import com.ivy.base.model.TransactionType
import com.ivy.data.model.AccountId
import java.time.LocalDate

sealed interface VoiceReviewEvent {
    data class OnCheckedChange(val index: Int, val checked: Boolean) : VoiceReviewEvent
    data class OnAllCheckedChange(val checked: Boolean) : VoiceReviewEvent
    data class OnTypeChange(val index: Int, val type: TransactionType) : VoiceReviewEvent
    data class OnAmountChange(val index: Int, val amount: String) : VoiceReviewEvent
    data class OnToAmountChange(val index: Int, val amount: String) : VoiceReviewEvent
    data class OnAccountChange(val index: Int, val account: AccountId) : VoiceReviewEvent
    data class OnToAccountChange(val index: Int, val account: AccountId) : VoiceReviewEvent
    data class OnDateChange(val index: Int, val date: LocalDate) : VoiceReviewEvent
    data object OnSaveClick : VoiceReviewEvent
}
