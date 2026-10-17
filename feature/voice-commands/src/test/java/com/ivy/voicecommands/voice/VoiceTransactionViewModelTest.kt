package com.ivy.voicecommands.voice

import arrow.core.Either
import com.ivy.base.model.TransactionType
import com.ivy.base.time.TimeProvider
import com.ivy.data.model.primitive.NotBlankTrimmedString
import com.ivy.data.model.primitive.PositiveDouble
import com.ivy.navigation.EditTransactionScreen
import com.ivy.navigation.Navigation
import com.ivy.ui.testing.ComposeViewModelTest
import com.ivy.ui.testing.runTest
import com.ivy.voicecommands.parser.VoiceParseError
import com.ivy.voicecommands.parser.VoiceParseResult
import com.ivy.voicecommands.parser.VoiceTransactionDraft
import com.ivy.voicecommands.parser.VoiceTransactionParser
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import org.junit.Before
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneOffset

class VoiceTransactionViewModelTest : ComposeViewModelTest() {
    private val parser = mockk<VoiceTransactionParser>()
    private val timeProvider = mockk<TimeProvider>()
    private lateinit var navigation: Navigation
    private lateinit var viewModel: VoiceTransactionViewModel

    private val draft = VoiceTransactionDraft(
        type = TransactionType.EXPENSE,
        amount = PositiveDouble.unsafe(25.0),
        title = NotBlankTrimmedString.unsafe("Coffee"),
        category = null,
        account = null,
        toAccount = null,
        date = LocalDate.of(2026, 10, 16),
    )

    @Before
    fun setup() {
        every { timeProvider.localTimeNow() } returns LocalTime.of(9, 30)
        every { timeProvider.getZoneId() } returns ZoneOffset.UTC
        navigation = Navigation()
        viewModel = VoiceTransactionViewModel(parser, navigation, timeProvider)
    }

    @Test
    fun `opens the pre-filled edit transaction screen`() {
        coEvery { parser.parse("spent 25 on coffee") } returns Either.Right(
            VoiceParseResult(draft = draft, aiError = null)
        )

        viewModel.runTest(
            events = listOf(VoiceTransactionEvent.OnSpeechResult("spent 25 on coffee"))
        ) {
            processing shouldBe false
            message shouldBe null
        }
        navigation.currentScreen shouldBe EditTransactionScreen(
            initialTransactionId = null,
            type = TransactionType.EXPENSE,
            amount = 25.0,
            title = "Coffee",
            dateTime = LocalDate.of(2026, 10, 16).atTime(9, 30).toInstant(ZoneOffset.UTC),
        )
    }

    @Test
    fun `shows the AI error before opening the screen`() {
        coEvery { parser.parse(any()) } returns Either.Right(
            VoiceParseResult(draft = draft, aiError = "Invalid API key")
        )

        viewModel.runTest(
            events = listOf(VoiceTransactionEvent.OnSpeechResult("spent 25 on coffee"))
        ) {
            message shouldBe VoiceTransactionMessage.UsedOfflineParser("Invalid API key")
        }
        navigation.currentScreen shouldBe null

        viewModel.onEvent(VoiceTransactionEvent.OnMessageShown)
        (navigation.currentScreen as? EditTransactionScreen)?.amount shouldBe 25.0
    }

    @Test
    fun `no amount`() {
        coEvery { parser.parse(any()) } returns Either.Left(VoiceParseError.NoAmount)

        viewModel.runTest(
            events = listOf(VoiceTransactionEvent.OnSpeechResult("coffee"))
        ) {
            message shouldBe VoiceTransactionMessage.NoAmount
        }
        navigation.currentScreen shouldBe null
    }

    @Test
    fun `speech recognition unavailable`() {
        viewModel.runTest(
            events = listOf(VoiceTransactionEvent.OnSpeechUnavailable)
        ) {
            message shouldBe VoiceTransactionMessage.SpeechUnavailable
        }
    }
}
