package com.ivy.voicecommands.parser

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import com.ivy.base.time.TimeProvider
import com.ivy.data.repository.AccountRepository
import com.ivy.data.repository.CategoryRepository
import com.ivy.voicecommands.VoiceApiKeysRepository
import timber.log.Timber
import javax.inject.Inject

data class VoiceParseResult(
    val draft: VoiceTransactionDraft,
    /** Set when the AI provider failed and the offline parser was used instead. */
    val aiError: String?,
)

/**
 * Turns the text of a voice command into a [VoiceTransactionDraft].
 * Uses the AI provider configured in Settings -> Voice commands and falls back to
 * the offline [LocalVoiceTransactionParser] when there's none or when it fails.
 */
class VoiceTransactionParser @Inject constructor(
    private val apiKeysRepository: VoiceApiKeysRepository,
    private val categoryRepository: CategoryRepository,
    private val accountRepository: AccountRepository,
    private val timeProvider: TimeProvider,
    private val llmParser: LlmVoiceTransactionParser,
    private val localParser: LocalVoiceTransactionParser,
) {
    suspend fun parse(text: String): Either<VoiceParseError, VoiceParseResult> {
        if (text.isBlank()) return VoiceParseError.Blank.left()

        val input = VoiceParserInput(
            text = text,
            categories = categoryRepository.findAll(),
            accounts = accountRepository.findAll(),
            today = timeProvider.localDateNow(),
        )

        val aiResult = apiKeysRepository.findActiveConfig().fold(
            ifLeft = { it.left() },
            ifRight = { config -> config?.let { llmParser.parse(it, input) } },
        )
        val aiDraft = aiResult?.getOrNull()
        return if (aiDraft != null) {
            VoiceParseResult(draft = aiDraft, aiError = null).right()
        } else {
            val aiError = aiResult?.leftOrNull()
            if (aiError != null) {
                Timber.w("Voice command AI parsing failed, using the offline parser: $aiError")
            }
            localParser.parse(input)
                .map { draft -> VoiceParseResult(draft = draft, aiError = aiError) }
                .mapLeft { error -> aiError?.let(VoiceParseError::AiFailed) ?: error }
        }
    }
}
