package com.ivy.voicecommands

import arrow.core.Either
import com.ivy.data.model.primitive.NotBlankTrimmedString
import com.ivy.ui.testing.ComposeViewModelTest
import com.ivy.ui.testing.runTest
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import org.junit.Before
import org.junit.Test

class VoiceCommandsSettingsViewModelTest : ComposeViewModelTest() {

    private val repository = mockk<VoiceApiKeysRepository>()

    private lateinit var viewModel: VoiceCommandsSettingsViewModel

    @Before
    fun setup() {
        coEvery { repository.findKey(any()) } returns Either.Right(null)
        viewModel = VoiceCommandsSettingsViewModel(repository)
    }

    @Test
    fun `shows a masked hint for saved keys`() {
        // given
        coEvery {
            repository.findKey(VoiceApiProvider.OpenAI)
        } returns Either.Right(NotBlankTrimmedString.unsafe("sk-123abcd"))

        // then
        viewModel.runTest {
            apiKeys.first { it.provider == VoiceApiProvider.OpenAI }.savedKeyHint shouldBe "••••abcd"
            apiKeys.first { it.provider == VoiceApiProvider.Anthropic }.savedKeyHint shouldBe null
        }
    }

    @Test
    fun `saving a blank key shows an error`() {
        viewModel.runTest(
            events = listOf(
                VoiceCommandsSettingsEvent.OnInputChange(VoiceApiProvider.OpenAI, "   "),
                VoiceCommandsSettingsEvent.OnSaveClick(VoiceApiProvider.OpenAI),
            )
        ) {
            apiKeys.first { it.provider == VoiceApiProvider.OpenAI }.error shouldBe
                "API key can't be empty"
        }
        coVerify(exactly = 0) { repository.saveKey(any(), any()) }
    }

    @Test
    fun `saving a key trims it, stores it and clears the input`() {
        // given
        coEvery { repository.saveKey(any(), any()) } returns Either.Right(Unit)

        // then
        viewModel.runTest(
            events = listOf(
                VoiceCommandsSettingsEvent.OnInputChange(VoiceApiProvider.Anthropic, " sk-ant-9876 "),
                VoiceCommandsSettingsEvent.OnSaveClick(VoiceApiProvider.Anthropic),
            )
        ) {
            val apiKey = apiKeys.first { it.provider == VoiceApiProvider.Anthropic }
            apiKey.savedKeyHint shouldBe "••••9876"
            apiKey.input shouldBe ""
            apiKey.error shouldBe null
        }
        coVerify {
            repository.saveKey(
                VoiceApiProvider.Anthropic,
                NotBlankTrimmedString.unsafe("sk-ant-9876")
            )
        }
    }

    @Test
    fun `removing a key clears the saved hint`() {
        // given
        coEvery {
            repository.findKey(VoiceApiProvider.Google)
        } returns Either.Right(NotBlankTrimmedString.unsafe("google-key"))
        coEvery { repository.removeKey(VoiceApiProvider.Google) } returns Either.Right(Unit)

        // then
        viewModel.runTest(
            events = listOf(VoiceCommandsSettingsEvent.OnRemoveClick(VoiceApiProvider.Google))
        ) {
            apiKeys.first { it.provider == VoiceApiProvider.Google }.savedKeyHint shouldBe null
        }
    }

    @Test
    fun `a storage failure is shown as an error`() {
        // given
        coEvery { repository.saveKey(any(), any()) } returns Either.Left("Keystore unavailable")

        // then
        viewModel.runTest(
            events = listOf(
                VoiceCommandsSettingsEvent.OnInputChange(VoiceApiProvider.OpenAI, "sk-1"),
                VoiceCommandsSettingsEvent.OnSaveClick(VoiceApiProvider.OpenAI),
            )
        ) {
            val apiKey = apiKeys.first { it.provider == VoiceApiProvider.OpenAI }
            apiKey.error shouldBe "Keystore unavailable"
            apiKey.savedKeyHint shouldBe null
        }
    }
}
