package com.averycorp.prismtask.domain.model

/**
 * Leisure Budget v2.0 categories.
 *
 * Spec-locked to four buckets (Q5 lock — all enabled by default). The
 * Room/Postgres rows store the [name] string with a CHECK constraint
 * rather than the enum directly so we can rename buckets in a future
 * version without a destructive enum-type migration.
 */
enum class LeisureCategory(val emoji: String, val label: String) {
    PHYSICAL(emoji = "🏃", label = "Physical"),
    SOCIAL(emoji = "👥", label = "Social"),
    CREATIVE(emoji = "🎨", label = "Creative"),
    PASSIVE(emoji = "🛋️", label = "Passive");

    companion object {
        val DEFAULT_ENABLED: Set<LeisureCategory> = values().toSet()

        fun fromStringOrNull(raw: String?): LeisureCategory? =
            values().firstOrNull { it.name.equals(raw, ignoreCase = true) }
    }
}

/**
 * Where a leisure session originated.
 *
 * TIMER: foreground service posted on natural completion or manual stop.
 * MANUAL: user logged from the "Log past activity" modal.
 */
enum class LeisureSessionSource { TIMER, MANUAL }

/**
 * Enforcement-mode strictness, ordered from least to most aggressive.
 *
 * SOFT is the universal default; MEDIUM and HARD are Pro-only (gated
 * server-side by `require_leisure_enforcement_choice`).
 */
enum class LeisureEnforcementMode { SOFT, MEDIUM, HARD }
