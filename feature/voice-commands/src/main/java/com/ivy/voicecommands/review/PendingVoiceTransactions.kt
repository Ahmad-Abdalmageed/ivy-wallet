package com.ivy.voicecommands.review

import com.ivy.voicecommands.parser.VoiceTransactionDraft
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Hands the transactions of a voice command over to [VoiceReviewViewModel].
 */
@Singleton
class PendingVoiceTransactions @Inject constructor() {
    var drafts: List<VoiceTransactionDraft> = emptyList()
}
