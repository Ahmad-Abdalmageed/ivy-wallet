package com.ivy.voicecommands.parser

import io.kotest.matchers.shouldBe
import org.junit.Test

class NameMatcherTest {
    private val names = listOf(
        "💵 Cash",
        "Bank Misr",
        "Bank Alahly",
        "Saving",
        "CIB Credit Card",
        "Vodafone Cash",
        "Main Account",
    )

    private fun mentioned(text: String): List<String> = NameMatcher.mentioned(text, names) { it }

    @Test
    fun `ignores case, emojis and punctuation`() {
        mentioned("paid with CASH!") shouldBe listOf("💵 Cash")
    }

    @Test
    fun `plural matches singular`() {
        mentioned("to my savings") shouldBe listOf("Saving")
    }

    @Test
    fun `small misspelling of a long word`() {
        mentioned("from vodaphone") shouldBe listOf("Vodafone Cash")
    }

    @Test
    fun `spelled out acronym`() {
        mentioned("from c i b card") shouldBe listOf("CIB Credit Card")
    }

    @Test
    fun `part of a name`() {
        mentioned("using misr") shouldBe listOf("Bank Misr")
    }

    @Test
    fun `full name wins over a partial one at the same place`() {
        mentioned("cash") shouldBe listOf("💵 Cash")
        mentioned("vodafone cash") shouldBe listOf("Vodafone Cash")
    }

    @Test
    fun `ambiguous partial name matches nothing`() {
        mentioned("from the bank") shouldBe emptyList()
    }

    @Test
    fun `generic words are ignored`() {
        mentioned("from main") shouldBe listOf("Main Account")
        mentioned("from my account") shouldBe emptyList()
    }

    @Test
    fun `ordered by position`() {
        mentioned("from bank misr to cash") shouldBe listOf("Bank Misr", "💵 Cash")
    }

    @Test
    fun `short words aren't fuzzy matched`() {
        NameMatcher.mentioned("good coffee", listOf("Food")) { it } shouldBe emptyList()
    }

    @Test
    fun `best match for an LLM answer`() {
        NameMatcher.bestMatch("Bank Misr account", names) { it } shouldBe "Bank Misr"
        NameMatcher.bestMatch("CIB card", names) { it } shouldBe "CIB Credit Card"
        NameMatcher.bestMatch("Revolut", names) { it } shouldBe null
    }
}
