package com.example.expensetracker.ui.theme

import androidx.compose.ui.graphics.Color

// Categorical (spend-by-category chart/legend/table dot). Fixed order, assigned by category
// *identity* (never by current-month rank) — validated against the dataviz skill's CVD/contrast
// checker on both the adjacent-pair check and the <=3-series all-pairs check. Categories beyond
// slot 8 cycle back starting at Series1, paired with a distinct icon so hue is never the sole
// identity signal.
val Series1 = Color(0xFF2A78D6) // blue — Bills & Utilities
val Series2 = Color(0xFFEB6834) // orange — Investments
val Series3 = Color(0xFF1BAF7A) // aqua — Unassigned
val Series4 = Color(0xFFEDA100) // yellow — Groceries
val Series5 = Color(0xFFE87BA4) // magenta — Entertainment
val Series6 = Color(0xFF008300) // green — Health
val Series7 = Color(0xFF4A3AA7) // violet — Food & Dining
val Series8 = Color(0xFFE34948) // red — Transport

// Status (budgets, bill due-dates). Reserved — never reused for a category. Always shipped with a
// text label/icon alongside the color, since warning/critical are sub-3:1 contrast by design.
val StatusGood = Color(0xFF0CA30C)
val StatusWarning = Color(0xFFFAB219)
val StatusCritical = Color(0xFFD03B3B)

// Chrome/ink — light mode only for v1.
val PagePlane = Color(0xFFF9F9F7)
val SurfacePlane = Color(0xFFFCFCFB)
val TextPrimary = Color(0xFF0B0B0B)
val TextSecondary = Color(0xFF52514E)
val TextMuted = Color(0xFF898781)
val Gridline = Color(0xFFE1E0D9)
val SuccessText = Color(0xFF006300)