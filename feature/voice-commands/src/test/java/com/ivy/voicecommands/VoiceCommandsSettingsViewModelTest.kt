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
        coEvery { repository.findModel(any()) } returns Either.Right(null)
        coEvery { repository.findActiveProvider() } returns Either.Right(null)
        coEvery { repository.findLanguage() } returns Either.Right(VoiceLanguage.Device)
        coEvery { repository.saveLanguage(any()) } returns Either.Right(Unit)
        coEvery { repository.saveKey(any(), any()) } returns Either.Right(Unit)
        coEvery { repository.saveModel(any(), any()) } returns Either.Right(Unit)
        coEvery { repository.saveActiveProvider(any()) } returns Either.Right(Unit)
        viewModel = VoiceCommandsSettingsViewModel(repository)
    }

    private fun VoiceCommandsSettingsState.of(provider: VoiceApiProvider): ApiKeyState =
        apiKeys.first { it.provider == provider }

    @Test
    fun `shows saved keys masked, models and the active provider`() {
        // given
        coEvery {
            repository.findKey(VoiceApiProvider.OpenAI)
        } returns Either.Right(NotBlankTrimmedString.unsafe("sk-123abcd"))
        coEvery {
            repository.findModel(VoiceApiProvider.OpenAI)
        } returns Either.Right(NotBlankTrimmedString.unsafe("gpt-5.4-nano"))
        coEvery { repository.findActiveProvider() } returns Either.Right(VoiceApiProvider.OpenAI)

        // then
        viewModel.runTest {
            val openAi = of(VoiceApiProvider.OpenAI)
            openAi.savedKeyHint shouldBe "••••abcd"
            openAi.model shouldBe "gpt-5.4-nano"
            openAi.active shouldBe true
            of(VoiceApiProvider.Google).savedKeyHint shouldBe null
            of(VoiceApiProvider.Google).hasFreeTier shouldBe true
            of(VoiceApiProvider.Google).active shouldBe false
        }
    }

    @Test
    fun `shows and changes the voice language`() {
        coEvery { repository.findLanguage() } returns Either.Right(VoiceLanguage.English)

        viewModel.runTest(
            events = listOf(VoiceCommandsSettingsEvent.OnLanguageSelect(VoiceLanguage.ArabicEgypt))
        ) {
            language shouldBe VoiceLanguage.ArabicEgypt
            languages shouldBe VoiceLanguage.entries
        }
        coVerify { repository.saveLanguage(VoiceLanguage.ArabicEgypt) }
    }

    @Test
    fun `saving a blank key shows an error`() {
        viewModel.runTest(
            events = listOf(
                VoiceCommandsSettingsEvent.OnInputChange(VoiceApiProvider.OpenAI, "   "),
                VoiceCommandsSettingsEvent.OnSaveClick(VoiceApiProvider.OpenAI),
            )
        ) {
            of(VoiceApiProvider.OpenAI).error shouldBe "API key can't be empty"
        }
        coVerify(exactly = 0) { repository.saveKey(any(), any()) }
    }

    @Test
    fun `saving the first key trims it and makes the provider active`() {
        viewModel.runTest(
            events = listOf(
                VoiceCommandsSettingsEvent.OnInputChange(VoiceApiProvider.Anthropic, " sk-ant-9876 "),
                VoiceCommandsSettingsEvent.OnModelChange(VoiceApiProvider.Anthropic, "claude-haiku-5-5"),
                VoiceCommandsSettingsEvent.OnSaveClick(VoiceApiProvider.Anthropic),
            )
        ) {
            val apiKey = of(VoiceApiProvider.Anthropic)
            apiKey.savedKeyHint shouldBe "••••9876"
            apiKey.input shouldBe ""
            apiKey.active shouldBe true
            apiKey.error shouldBe null
        }
        coVerify {
            repository.saveKey(VoiceApiProvider.Anthropic, NotBlankTrimmedString.unsafe("sk-ant-9876"))
            repository.saveModel(
                VoiceApiProvider.Anthropic,
                NotBlankTrimmedString.unsafe("claude-haiku-5-5")
            )
            repository.saveActiveProvider(VoiceApiProvider.Anthropic)
        }
    }

    @Test
    fun `a blank model uses the default one`() {
        coEvery {
            repository.findKey(VoiceApiProvider.Google)
        } returns Either.Right(NotBlankTrimmedString.unsafe("google-key"))

        viewModel.runTest(
            events = listOf(
                VoiceCommandsSettingsEvent.OnModelChange(VoiceApiProvider.Google, " "),
                VoiceCommandsSettingsEvent.OnSaveClick(VoiceApiProvider.Google),
            )
        ) {
            of(VoiceApiProvider.Google).defaultModel shouldBe "gemini-flash-latest"
        }
        coVerify { repository.saveModel(VoiceApiProvider.Google, null) }
    }

    @Test
    fun `use switches the active provider`() {
        coEvery {
            repository.findKey(VoiceApiProvider.Groq)
        } returns Either.Right(NotBlankTrimmedString.unsafe("groq-key"))
        coEvery { repository.findActiveProvider() } returns Either.Right(VoiceApiProvider.OpenAI)

        viewModel.runTest(
            events = listOf(VoiceCommandsSettingsEvent.OnUseClick(VoiceApiProvider.Groq))
        ) {
            of(VoiceApiProvider.Groq).active shouldBe true
            of(VoiceApiProvider.OpenAI).active shouldBe false
        }
    }

    @Test
    fun `removing the active key clears the active provider`() {
        coEvery {
            repository.findKey(VoiceApiProvider.Google)
        } returns Either.Right(NotBlankTrimmedString.unsafe("google-key"))
        coEvery { repository.findActiveProvider() } returns Either.Right(VoiceApiProvider.Google)
        coEvery { repository.removeKey(VoiceApiProvider.Google) } returns Either.Right(Unit)

        viewModel.runTest(
            events = listOf(VoiceCommandsSettingsEvent.OnRemoveClick(VoiceApiProvider.Google))
        ) {
            of(VoiceApiProvider.Google).savedKeyHint shouldBe null
            of(VoiceApiProvider.Google).active shouldBe false
        }
        coVerify { repository.saveActiveProvider(null) }
    }

    @Test
    fun `a storage failure is shown as an error`() {
        coEvery { repository.saveKey(any(), any()) } returns Either.Left("Keystore unavailable")

        viewModel.runTest(
            events = listOf(
                VoiceCommandsSettingsEvent.OnInputChange(VoiceApiProvider.OpenAI, "sk-1"),
                VoiceCommandsSettingsEvent.OnSaveClick(VoiceApiProvider.OpenAI),
            )
        ) {
            val apiKey = of(VoiceApiProvider.OpenAI)
            apiKey.error shouldBe "Keystore unavailable"
            apiKey.savedKeyHint shouldBe null
        }
    }
}
