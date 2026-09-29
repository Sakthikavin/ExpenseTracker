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

/**
 * A message-level "this isn't a transaction" pattern (IGNORE_RULES.md §2) — a declined-payment
 * alert, say. Narrower than `discardSenders`, which silences everything from a sender.
 *
 * It only answers "is this noise?", so it has no `fieldMap`, `direction` or `priority`.
 */
data class RemoteIgnoreRule(
    val id: String,
    val senders: List<String>,
    val pattern: String,
    val reason: String,
)

/** The published rule set as a whole — `rules/current` (REQUIREMENTS.md §9). */
data class RemoteRuleSet(
    val version: Int,
    val updatedAt: String,
    val discardSenders: List<String>,
    val rules: List<RemoteRule>,
    /** Absent from every rule set published before ignore rules existed, hence the default. */
    val ignoreRules: List<RemoteIgnoreRule> = emptyList(),
)
