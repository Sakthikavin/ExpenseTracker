package com.example.expensetracker.export

import android.content.Context
import com.example.expensetracker.data.local.entity.CategoryEntity
import com.example.expensetracker.data.local.entity.TransactionEntity
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

/**
 * Writes transactions to a local CSV file under app-specific external storage — no email
 * round-trip, no server, per spec §1. The file lives at
 * Android/data/<applicationId>/files/exports/ and is reachable over USB/file manager.
 */
object CsvExporter {

    suspend fun export(context: Context, transactions: List<TransactionEntity>, categories: List<CategoryEntity>): File =
        withContext(Dispatchers.IO) {
            val categoriesById = categories.associateBy { it.id }
            val exportsDir = File(context.getExternalFilesDir(null), "exports").apply { mkdirs() }
            val file = File(exportsDir, "transactions_${System.currentTimeMillis()}.csv")

            file.bufferedWriter().use { writer ->
                writer.appendLine("date,direction,amount,merchant,account,category,note,tags,source")
                transactions.forEach { transaction ->
                    val date = transaction.occurredAt.toLocalDateTime(TimeZone.currentSystemDefault())
                    val amount = "%d.%02d".format(transaction.amountMinor / 100, transaction.amountMinor % 100)
                    val categoryName = transaction.categoryId?.let { categoriesById[it]?.name }.orEmpty()
                    writer.appendLine(
                        listOf(
                            date.toString(),
                            transaction.direction.name,
                            amount,
                            transaction.merchant,
                            transaction.accountLabel,
                            categoryName,
                            transaction.note,
                            transaction.tags.joinToString(";"),
                            transaction.source.name,
                        ).joinToString(",") { csvField(it) },
                    )
                }
            }
            file
        }

    private fun csvField(value: String): String =
        if (value.any { it == ',' || it == '"' || it == '\n' }) {
            "\"${value.replace("\"", "\"\"")}\""
        } else {
            value
        }
}