package com.example.expensetracker.ui.dashboard

import android.content.SharedPreferences
import androidx.core.content.edit
import kotlinx.datetime.LocalDate

/**
 * A date range the user pinned as the one the dashboard opens on.
 *
 * A pinned preset carries no dates — it is re-derived against today on every launch, so "This
 * month" means the month you are actually in. A pinned [DatePreset.CUSTOM] range carries literal
 * dates, because the point of pinning a custom range is to get that exact range back rather than a
 * rolling window of the same length. That also means it can fall wholly into the past, which the
 * dashboard warns about instead of silently showing an empty period.
 */
data class DefaultDateRange(
    val preset: DatePreset,
    /** Both set when [preset] is [DatePreset.CUSTOM], both null otherwise. */
    val customStart: LocalDate? = null,
    val customEnd: LocalDate? = null,
)

/**
 * Reads and writes the pinned default range in the shared "settings" preferences.
 *
 * Writes are deliberate — pinning is an explicit action in the preset sheet, so merely browsing
 * other periods during a session never moves the default.
 */
class DateRangePreferences(private val prefs: SharedPreferences) {

    /** Null when nothing is pinned; the dashboard then falls back to the current calendar month. */
    fun load(): DefaultDateRange? {
        val presetName = prefs.getString(PREF_PRESET, null) ?: return null
        // An unknown name means a preset was renamed or removed between app versions. Dropping the
        // pin is better than crashing on a value we can no longer interpret.
        val preset = DatePreset.entries.firstOrNull { it.name == presetName } ?: return null
        if (preset != DatePreset.CUSTOM) return DefaultDateRange(preset)

        val startEpochDay = prefs.getLong(PREF_CUSTOM_START, NO_DATE)
        val endEpochDay = prefs.getLong(PREF_CUSTOM_END, NO_DATE)
        if (startEpochDay == NO_DATE || endEpochDay == NO_DATE) return null
        return DefaultDateRange(
            preset = DatePreset.CUSTOM,
            customStart = LocalDate.fromEpochDays(startEpochDay.toInt()),
            customEnd = LocalDate.fromEpochDays(endEpochDay.toInt()),
        )
    }

    /** Pins [preset]; [start] and [end] are stored only for [DatePreset.CUSTOM]. */
    fun save(preset: DatePreset, start: LocalDate, end: LocalDate) {
        prefs.edit {
            putString(PREF_PRESET, preset.name)
            if (preset == DatePreset.CUSTOM) {
                putLong(PREF_CUSTOM_START, start.toEpochDays().toLong())
                putLong(PREF_CUSTOM_END, end.toEpochDays().toLong())
            } else {
                remove(PREF_CUSTOM_START)
                remove(PREF_CUSTOM_END)
            }
        }
    }

    fun clear() {
        prefs.edit {
            remove(PREF_PRESET)
            remove(PREF_CUSTOM_START)
            remove(PREF_CUSTOM_END)
        }
    }

    private companion object {
        const val PREF_PRESET = "dashboard_default_date_preset"
        const val PREF_CUSTOM_START = "dashboard_default_range_start_epoch_day"
        const val PREF_CUSTOM_END = "dashboard_default_range_end_epoch_day"
        const val NO_DATE = Long.MIN_VALUE
    }
}
