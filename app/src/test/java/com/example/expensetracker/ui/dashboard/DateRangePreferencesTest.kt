package com.example.expensetracker.ui.dashboard

import com.example.expensetracker.data.remoterules.FakeSharedPreferences
import kotlinx.datetime.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DateRangePreferencesTest {

    private val prefs = FakeSharedPreferences()
    private val preferences = DateRangePreferences(prefs)

    private val anyStart = LocalDate(2026, 8, 25)
    private val anyEnd = LocalDate(2026, 9, 25)

    @Test
    fun `nothing pinned reads as no default`() {
        assertNull(preferences.load())
    }

    @Test
    fun `a pinned preset round-trips without dates`() {
        preferences.save(DatePreset.LAST_30_DAYS, anyStart, anyEnd)

        // The dates passed in are deliberately dropped: a preset is re-derived against today on
        // every launch, so storing the dates it happened to resolve to would make it go stale.
        assertEquals(DefaultDateRange(DatePreset.LAST_30_DAYS), preferences.load())
    }

    @Test
    fun `a pinned custom range round-trips with its exact dates`() {
        preferences.save(DatePreset.CUSTOM, anyStart, anyEnd)

        assertEquals(
            DefaultDateRange(DatePreset.CUSTOM, customStart = anyStart, customEnd = anyEnd),
            preferences.load(),
        )
    }

    @Test
    fun `pinning a preset over a custom range leaves no stale dates behind`() {
        preferences.save(DatePreset.CUSTOM, anyStart, anyEnd)
        preferences.save(DatePreset.THIS_MONTH, anyStart, anyEnd)

        assertEquals(DefaultDateRange(DatePreset.THIS_MONTH), preferences.load())
    }

    @Test
    fun `clearing removes the pin`() {
        preferences.save(DatePreset.CUSTOM, anyStart, anyEnd)
        preferences.clear()

        assertNull(preferences.load())
    }

    @Test
    fun `a preset name this version no longer knows is dropped rather than crashing`() {
        val stale = DateRangePreferences(
            FakeSharedPreferences(mapOf("dashboard_default_date_preset" to "LAST_FORTNIGHT")),
        )

        assertNull(stale.load())
    }

    @Test
    fun `a custom pin missing its dates is dropped`() {
        val corrupt = DateRangePreferences(
            FakeSharedPreferences(mapOf("dashboard_default_date_preset" to "CUSTOM")),
        )

        assertNull(corrupt.load())
    }

    @Test
    fun `dates before the epoch survive the round-trip`() {
        val start = LocalDate(1969, 12, 20)
        val end = LocalDate(1970, 1, 10)
        preferences.save(DatePreset.CUSTOM, start, end)

        assertEquals(
            DefaultDateRange(DatePreset.CUSTOM, customStart = start, customEnd = end),
            preferences.load(),
        )
    }
}
