package com.averycorp.prismtask.domain.usecase

/**
 * A single nudge candidate produced by [SelfCareNudgeEngine] (v1.4.0 V2).
 *
 * The [id] is a stable key so the UI can persist "don't show this
 * consecutive time" state; the [kind] bucket lets the UI pick an icon.
 */
data class SelfCareNudge(val id: String, val kind: NudgeKind, val message: String)

enum class NudgeKind {
    REST_BREAK,
    BURNOUT_WARNING,
    MOVEMENT,
    WIND_DOWN
}

/**
 * Pure-function nudge selector (v1.4.0 V2).
 *
 * Consumes the burnout score, the current self-care ratio vs the target,
 * the time of day, and the last shown nudge id. Returns a nudge to show
 * right now, or `null` if nothing is warranted.
 *
 * The four nudge types rotate — if the same kind was just shown, the
 * engine picks a different one. This matches the vision deck's "rotate,
 * don't repeat the same one consecutively" requirement.
 */
class SelfCareNudgeEngine {
    fun select(
        burnoutScore: Int,
        selfCareRatio: Float,
        selfCareTarget: Float,
        hourOfDay: Int,
        lastShownId: String?
    ): SelfCareNudge? {
        val ratioBelowTarget = selfCareRatio < (selfCareTarget - LOW_SELF_CARE_BUFFER)
        val burnoutElevated = burnoutScore > BURNOUT_NUDGE_THRESHOLD
        if (!ratioBelowTarget && !burnoutElevated) return null

        val candidates = mutableListOf<SelfCareNudge>()

        // Always-eligible nudges when conditions hit.
        candidates.add(
            SelfCareNudge(
                id = "rest_break",
                kind = NudgeKind.REST_BREAK,
                message = "You haven't logged any self-care today — how about a 15-minute break?"
            )
        )
        if (burnoutElevated) {
            candidates.add(
                SelfCareNudge(
                    id = "burnout_warning",
                    kind = NudgeKind.BURNOUT_WARNING,
                    message = "Your burnout score is rising. Block 30 minutes for something you enjoy."
                )
            )
        }
        candidates.add(
            SelfCareNudge(
                id = "movement",
                kind = NudgeKind.MOVEMENT,
                message = "Movement reminder: a short walk can reset your focus."
            )
        )
        if (hourOfDay >= 18) {
            candidates.add(
                SelfCareNudge(
                    id = "wind_down",
                    kind = NudgeKind.WIND_DOWN,
                    message = "Wind-down suggestion: consider stopping work tasks for the evening."
                )
            )
        }

        // Rotate: skip any candidate with the same id as the last one shown.
        val filtered = candidates.filter { it.id != lastShownId }
        return (filtered.firstOrNull() ?: candidates.firstOrNull())
    }

    companion object {
        const val BURNOUT_NUDGE_THRESHOLD = 50
        const val LOW_SELF_CARE_BUFFER = 0.10f
    }
}
