package com.ivy.commands.ui

import android.content.Context
import com.ivy.commands.ExternalCommandsSettings
import com.ivy.navigation.Navigation
import com.ivy.ui.testing.ComposeViewModelTest
import com.ivy.ui.testing.runTest
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import org.junit.Before
import org.junit.Test

class ExternalCommandsViewModelTest : ComposeViewModelTest() {
    private val settings = mockk<ExternalCommandsSettings>(relaxUnitFun = true)
    private val context = mockk<Context>()
    private lateinit var viewModel: ExternalCommandsViewModel

    @Before
    fun setup() {
        every { context.packageName } returns "com.ivy.wallet"
        coEvery { settings.isEnabled() } returns false
        coEvery { settings.accessKey() } returns "abc123"
        coEvery { settings.regenerateAccessKey() } returns "new456"
        viewModel = ExternalCommandsViewModel(settings, Navigation(), context)
    }

    @Test
    fun `shows the settings`() {
        viewModel.runTest {
            enabled shouldBe false
            accessKey shouldBe "abc123"
            keyVisible shouldBe false
            authority shouldBe "com.ivy.wallet.commands"
            exampleCommand shouldBe "adb shell content call --uri content://com.ivy.wallet.commands " +
                "--method list_accounts --arg '{\"key\":\"abc123\"}'"
        }
    }

    @Test
    fun `turns the commands on`() {
        viewModel.runTest(events = listOf(ExternalCommandsEvent.OnEnabledChange(true))) {
            enabled shouldBe true
        }
        coVerify { settings.setEnabled(true) }
    }

    @Test
    fun `new key`() {
        viewModel.runTest(events = listOf(ExternalCommandsEvent.OnNewKeyClick)) {
            accessKey shouldBe "new456"
            keyVisible shouldBe true
        }
    }
}
