package com.example.expensetracker.data.remoterules

import com.example.expensetracker.data.local.entity.Direction

/**
 * Mirrors one rule inside the published `rules/current` document (REQUIREMENTS.md §9).
 *
 * `fieldMap` values are capture-group **numbers**, counting every group left-to-right including
 * unnamed ones — not named-group lookups.
 */
data class RemoteRule(
    val id: String,
    val senders: List<String>,
    val direction: Direction,
    val pattern: String,
    val fieldMap: Map<String, Int>,
    val priority: Int,
)

/** The published rule set as a whole — `rules/current` (REQUIREMENTS.md §9). */
data class RemoteRuleSet(
    val version: Int,
    val updatedAt: String,
    val discardSenders: List<String>,
    val rules: List<RemoteRule>,
)
