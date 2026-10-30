package com.ivy.stats.domain

import io.kotest.matchers.shouldBe
import org.junit.Test
import java.time.Instant
import java.time.YearMonth
import java.time.ZoneOffset

class StatsPeriodTest {
    private val october = YearMonth.of(2026, 10)

    @Test
    fun presets() {
        PeriodPreset.Last3Months.period(october) shouldBe StatsPeriod(YearMonth.of(2026, 8), october)
        PeriodPreset.Last12Months.period(october) shouldBe StatsPeriod(YearMonth.of(2025, 11), october)
        PeriodPreset.ThisYear.period(october) shouldBe StatsPeriod(YearMonth.of(2026, 1), october)
        PeriodPreset.LastYear.period(october) shouldBe
            StatsPeriod(YearMonth.of(2025, 1), YearMonth.of(2025, 12))
        PeriodPreset.Custom.period(october) shouldBe null
    }

    @Test
    fun `months and range`() {
        val period = StatsPeriod(YearMonth.of(2025, 12), YearMonth.of(2026, 2))

        period.months() shouldBe listOf(
            YearMonth.of(2025, 12),
            YearMonth.of(2026, 1),
            YearMonth.of(2026, 2),
        )
        period.start(ZoneOffset.UTC) shouldBe Instant.parse("2025-12-01T00:00:00Z")
        period.end(ZoneOffset.UTC) shouldBe Instant.parse("2026-02-28T23:59:59.999Z")
    }
}
