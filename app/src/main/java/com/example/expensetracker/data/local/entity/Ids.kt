package com.example.expensetracker.data.local.entity

/**
 * Household and user IDs are threaded through every entity from day one even though v1 is
 * single-user, so that family sharing in v2 is additive rather than a schema migration.
 */
object LocalIds {
    const val DEFAULT_HOUSEHOLD_ID: Long = 1L
    const val DEFAULT_USER_ID: Long = 1L
}