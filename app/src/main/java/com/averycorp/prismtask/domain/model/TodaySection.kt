package com.averycorp.prismtask.domain.model

/**
 * Catalog of all sections that can appear on the Today screen. Used by the
 * customization system in v1.3.0 (P11) to let users reorder and toggle
 * visibility of each section.
 *
 * Each section has:
 *  - [key]: persisted identifier
 *  - [defaultVisible]: included in the default layout
 *  - [requiresTier]: minimum user tier, or null for Free
 *  - [displayName]: label shown in Settings
 */
enum class TodaySectionId(
    val key: String,
    val defaultVisible: Boolean,
    val requiresTier: String?,
    val displayName: String
) {
    PROGRESS("progress", true, null, "Progress Header"),
    OVERDUE("overdue", true, null, "Overdue Tasks"),
    TODAY_TASKS("today_tasks", true, null, "Today's Tasks"),
    DAILY_ESSENTIALS("daily_essentials", true, null, "Daily Essentials"),
    CALENDAR_EVENTS("calendar_events", true, null, "Calendar Events"),
    PLANNED("planned", true, null, "Planned / Upcoming"),
    FLAGGED("flagged", false, null, "Flagged Tasks"),
    COMPLETED("completed", true, null, "Completed Today"),
    AI_BRIEFING("ai_briefing", true, "PRO", "AI Briefing");

    companion object {
        fun fromKey(key: String): TodaySectionId? = values().firstOrNull { it.key == key }

        /** Default ordered list of section keys for first launch. */
        val DEFAULT_ORDER: List<String> = values().map { it.key }
    }
}

/**
 * A resolved Today section with its current visibility and order, ready for
 * the renderer to iterate. [tierAllows] is populated by the caller based on
 * the current user's tier so gated sections can be silently dropped.
 */
data class TodaySection(
    val id: TodaySectionId,
    val visible: Boolean,
    val order: Int,
    val tierAllows: Boolean
) {
    val render: Boolean get() = visible && tierAllows
}

/**
 * Pure helper that composes the final ordered list of sections for rendering
 * given the user's persisted order, hidden-set, and current tier.
 */
object TodayLayoutResolver {
    /**
     * @param userOrder ordered list of section keys as persisted by the user
     *                  (may be empty -> use defaults)
     * @param hiddenKeys set of section keys the user has toggled off
     * @param currentTier e.g. "FREE" / "PRO" (case-insensitive)
     */
    fun resolve(
        userOrder: List<String>,
        hiddenKeys: Set<String>,
        currentTier: String
    ): List<TodaySection> {
        val order = if (userOrder.isEmpty()) TodaySectionId.DEFAULT_ORDER else userOrder
        val seen = mutableSetOf<String>()
        val ordered = mutableListOf<TodaySection>()

        order.forEachIndexed { index, key ->
            if (key in seen) return@forEachIndexed
            seen += key
            val id = TodaySectionId.fromKey(key) ?: return@forEachIndexed
            ordered.add(
                TodaySection(
                    id = id,
                    visible = key !in hiddenKeys,
                    order = index,
                    tierAllows = tierAllows(id.requiresTier, currentTier)
                )
            )
        }

        // Any section added after a user persisted their order is appended at
        // the end with its default visibility preserved.
        var nextOrder = ordered.size
        TodaySectionId.values().forEach { id ->
            if (id.key !in seen) {
                ordered.add(
                    TodaySection(
                        id = id,
                        visible = id.defaultVisible && id.key !in hiddenKeys,
                        order = nextOrder++,
                        tierAllows = tierAllows(id.requiresTier, currentTier)
                    )
                )
            }
        }
        return ordered
    }

    private fun tierAllows(requires: String?, current: String): Boolean {
        if (requires == null) return true
        val rank = mapOf("FREE" to 0, "PRO" to 1)
        val requiredRank = rank[requires.uppercase()] ?: 0
        val currentRank = rank[current.uppercase()] ?: 0
        return currentRank >= requiredRank
    }
}
