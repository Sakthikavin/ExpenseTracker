package com.example.expensetracker.data.local

import androidx.room.TypeConverter
import com.example.expensetracker.data.local.entity.BillRecurrence
import com.example.expensetracker.data.local.entity.BudgetPeriod
import com.example.expensetracker.data.local.entity.Direction
import com.example.expensetracker.data.local.entity.ParseStatus
import com.example.expensetracker.data.local.entity.TransactionSource
import kotlinx.datetime.Instant

class Converters {

    @TypeConverter
    fun instantToEpochMillis(value: Instant?): Long? = value?.toEpochMilliseconds()

    @TypeConverter
    fun epochMillisToInstant(value: Long?): Instant? = value?.let(Instant::fromEpochMilliseconds)

    @TypeConverter
    fun tagsToString(tags: List<String>): String = tags.joinToString(",")

    @TypeConverter
    fun stringToTags(value: String): List<String> =
        if (value.isEmpty()) emptyList() else value.split(",")

    @TypeConverter
    fun fieldMapToString(fieldMap: Map<String, Int>): String =
        fieldMap.entries.joinToString(",") { (key, group) -> "$key:$group" }

    @TypeConverter
    fun stringToFieldMap(value: String): Map<String, Int> =
        if (value.isEmpty()) {
            emptyMap()
        } else {
            value.split(",").associate { entry ->
                val (key, group) = entry.split(":")
                key to group.toInt()
            }
        }

    @TypeConverter
    fun directionToString(direction: Direction): String = direction.name

    @TypeConverter
    fun stringToDirection(value: String): Direction = Direction.valueOf(value)

    @TypeConverter
    fun sourceToString(source: TransactionSource): String = source.name

    @TypeConverter
    fun stringToSource(value: String): TransactionSource = TransactionSource.valueOf(value)

    @TypeConverter
    fun parseStatusToString(status: ParseStatus): String = status.name

    @TypeConverter
    fun stringToParseStatus(value: String): ParseStatus = ParseStatus.valueOf(value)

    @TypeConverter
    fun recurrenceToString(recurrence: BillRecurrence): String = recurrence.name

    @TypeConverter
    fun stringToRecurrence(value: String): BillRecurrence = BillRecurrence.valueOf(value)

    @TypeConverter
    fun periodToString(period: BudgetPeriod): String = period.name

    @TypeConverter
    fun stringToPeriod(value: String): BudgetPeriod = BudgetPeriod.valueOf(value)
}