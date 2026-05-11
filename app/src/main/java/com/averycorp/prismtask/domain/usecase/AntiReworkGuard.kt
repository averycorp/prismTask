package com.averycorp.prismtask.domain.usecase

import com.averycorp.prismtask.data.local.entity.TaskEntity
import com.averycorp.prismtask.data.preferences.NdPreferences

/**
 * Anti-Rework Guard logic for Focus & Release Mode. Evaluates whether a user
 * should be warned, blocked, or allowed to re-edit a completed task.
 */
object AntiReworkGuard {
    /**
     * Evaluate what should happen when the user tries to edit a completed task.
     */
    fun evaluate(task: TaskEntity, ndPrefs: NdPreferences): ReworkDecision {
        if (!ndPrefs.focusReleaseModeEnabled) return ReworkDecision.Allow
        if (!ndPrefs.antiReworkEnabled) return ReworkDecision.Allow
        if (!task.isCompleted) return ReworkDecision.Allow

        // Revision-locked tasks require explicit unlock
        if (task.revisionLocked) return ReworkDecision.RevisionLocked

        // Cooling-off period check
        if (ndPrefs.coolingOffEnabled && task.completedAt != null) {
            val coolingOffMs = ndPrefs.coolingOffMinutes * 60 * 1000L
            val elapsed = System.currentTimeMillis() - task.completedAt
            if (elapsed < coolingOffMs) {
                val remainingMinutes = ((coolingOffMs - elapsed) / 60_000).toInt().coerceAtLeast(1)
                return ReworkDecision.CoolingOff(
                    remainingMinutes = remainingMinutes,
                    minutesAgo = ((System.currentTimeMillis() - task.completedAt) / 60_000).toInt()
                )
            }
        }

        // Revision counter check
        if (ndPrefs.revisionCounterEnabled) {
            val maxRevisions = task.maxRevisionsOverride ?: ndPrefs.maxRevisions
            if (task.revisionCount >= maxRevisions) {
                return ReworkDecision.MaxRevisionsReached(
                    revisionCount = task.revisionCount,
                    maxRevisions = maxRevisions
                )
            }
        }

        // Soft warning check
        if (ndPrefs.softWarningEnabled) {
            return ReworkDecision.SoftWarning(
                revisionCount = task.revisionCount,
                adhdModeActive = ndPrefs.adhdModeEnabled
            )
        }

        return ReworkDecision.Allow
    }
}

sealed class ReworkDecision {
    /** No guard active — allow editing freely. */
    data object Allow : ReworkDecision()

    /** Soft warning: "You already finished this — sure?" */
    data class SoftWarning(val revisionCount: Int, val adhdModeActive: Boolean) : ReworkDecision()

    /** Task is in cooling-off period — can't edit yet. */
    data class CoolingOff(val remainingMinutes: Int, val minutesAgo: Int) : ReworkDecision()

    /** Task has reached the max revision limit. */
    data class MaxRevisionsReached(val revisionCount: Int, val maxRevisions: Int) : ReworkDecision()

    /** Task is revision-locked — requires explicit unlock. */
    data object RevisionLocked : ReworkDecision()
}
