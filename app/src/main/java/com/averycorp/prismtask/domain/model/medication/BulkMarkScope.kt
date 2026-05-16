package com.averycorp.prismtask.domain.model.medication

/**
 * Selection scope for bulk-marking medications to a single tier in one
 * action. Tier-scope is intentionally not modeled here — under the
 * uniform-setter interpretation it collapses onto [FULL_DAY], so the
 * first ship offers two scopes and the dialog stays narrower.
 */
enum class BulkMarkScope {
    /**
     * Mark every medication linked to one slot today. Caller supplies
     * the slot id; the bulk action fans out to one mutation per
     * medication in the slot, all sharing one batch_id.
     */
    SLOT,

    /**
     * Mark every medication across every active slot today. Slot id is
     * ignored. Used for "mark today complete" / "mark today skipped"
     * style actions.
     */
    FULL_DAY
}
