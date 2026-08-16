package com.example.expensetracker.ui.dashboard

import androidx.compose.ui.graphics.Color

/**
 * Fixed light-only palette matching the approved Dashboard redesign mockup. Scoped to this screen
 * only — every other screen keeps the app's Material3 theme, so this deliberately does not follow
 * system dark mode (the mockup itself has no dark variant).
 */
object DashboardPalette {
    val PagePlane = Color(0xFFF9F9F7)
    val Surface1 = Color(0xFFFCFCFB)
    val TextPrimary = Color(0xFF0B0B0B)
    val TextSecondary = Color(0xFF52514E)
    val TextMuted = Color(0xFF898781)
    val Gridline = Color(0xFFE1E0D9)
    val Border = Color(0x1A0B0B0B)
    val SuccessText = Color(0xFF006300)

    val StatusGood = Color(0xFF0CA30C)
    val StatusWarning = Color(0xFFFAB219)
    val StatusCritical = Color(0xFFD03B3B)

    val GoodPillBg = Color(0xFFE6F6E6)
    val GoodPillText = Color(0xFF0A6B0A)
    val WarningPillBg = Color(0xFFFFF2D6)
    val WarningPillText = Color(0xFF8A5A00)
    val CriticalPillBg = Color(0xFFFBE3E2)
    val CriticalPillText = Color(0xFF9C2323)

    val ReviewIconBg = Color(0xFFFFF4DE)
}

enum class BudgetStatus { GOOD, WARNING, CRITICAL }

/** Same ≥100% / ≥80% thresholds `BudgetsScreen.kt` uses inline, duplicated here on purpose so that
 * screen stays untouched by this redesign. */
fun budgetStatus(spentMinor: Long, limitMinor: Long): BudgetStatus = when {
    limitMinor <= 0 -> BudgetStatus.GOOD
    spentMinor >= limitMinor -> BudgetStatus.CRITICAL
    spentMinor >= (limitMinor * 0.8).toLong() -> BudgetStatus.WARNING
    else -> BudgetStatus.GOOD
}
