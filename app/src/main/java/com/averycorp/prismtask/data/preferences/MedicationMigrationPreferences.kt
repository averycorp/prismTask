package com.averycorp.prismtask.data.preferences

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

private val Context.medicationMigrationDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "medication_migration_prefs"
)

/**
 * One-shot guard flags for the v53 → v54 medication-top-level migration
 * follow-up passes. Each flag is set only after the corresponding pass
 * succeeds so a mid-run crash stays retryable on the next app start.
 *
 * See `docs/SPEC_MEDICATIONS_TOP_LEVEL.md` §4.4.
 */
@Singleton
class MedicationMigrationPreferences
@Inject
constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        private val SCHEDULE_PRESERVED = booleanPreferencesKey("schedule_preserved")
        private val DOSE_BACKFILL_DONE = booleanPreferencesKey("dose_backfill_done")
        private val RECONCILIATION_DONE = booleanPreferencesKey("reconciliation_done")
        private val MIGRATION_PUSHED_TO_CLOUD = booleanPreferencesKey("migration_pushed_to_cloud")
        private val SOURCE_DATA_PURGED_PHASE_2 = booleanPreferencesKey("source_data_purged_phase_2")
        private val V54_APPLIED_AT_MS = longPreferencesKey("v54_applied_at_ms")
        private val TIER_STATE_DOC_ID_BACKFILL_DONE =
            booleanPreferencesKey("tier_state_doc_id_backfill_done")
    }

    /**
     * True once `MedicationMigrationRunner.preserveScheduleIfNeeded` has
     * written the user's pre-migration global schedule onto every row in
     * `medications`.
     */
    suspend fun isSchedulePreserved(): Boolean =
        context.medicationMigrationDataStore.data.first()[SCHEDULE_PRESERVED] ?: false

    suspend fun setSchedulePreserved(done: Boolean) {
        context.medicationMigrationDataStore.edit { it[SCHEDULE_PRESERVED] = done }
    }

    /**
     * True once `MedicationMigrationRunner.backfillDosesIfNeeded` has
     * parsed every legacy `self_care_logs` row (where
     * `routine_type='medication'`) into `medication_doses` rows.
     */
    suspend fun isDoseBackfillDone(): Boolean =
        context.medicationMigrationDataStore.data.first()[DOSE_BACKFILL_DONE] ?: false

    suspend fun setDoseBackfillDone(done: Boolean) {
        context.medicationMigrationDataStore.edit { it[DOSE_BACKFILL_DONE] = done }
    }

    /**
     * True once `BuiltInMedicationReconciler.reconcileAfterSyncIfNeeded`
     * has deduped any cross-device cloud-pulled medications with the
     * locally-migrated ones.
     */
    suspend fun isReconciliationDone(): Boolean =
        context.medicationMigrationDataStore.data.first()[RECONCILIATION_DONE] ?: false

    suspend fun setReconciliationDone(done: Boolean) {
        context.medicationMigrationDataStore.edit { it[RECONCILIATION_DONE] = done }
    }

    /**
     * True once every post-migration `medication` + `medication_dose` row
     * has been pushed to Firestore as a fresh cloud document.
     */
    suspend fun isMigrationPushedToCloud(): Boolean =
        context.medicationMigrationDataStore.data.first()[MIGRATION_PUSHED_TO_CLOUD] ?: false

    suspend fun setMigrationPushedToCloud(done: Boolean) {
        context.medicationMigrationDataStore.edit { it[MIGRATION_PUSHED_TO_CLOUD] = done }
    }

    /**
     * Reserved for the future Phase 2 cleanup migration (v54 → v55) that
     * drops the quarantine staging tables + source rows after the 2-week
     * convergence window.
     */
    suspend fun isSourceDataPurgedPhase2(): Boolean =
        context.medicationMigrationDataStore.data.first()[SOURCE_DATA_PURGED_PHASE_2] ?: false

    suspend fun setSourceDataPurgedPhase2(done: Boolean) {
        context.medicationMigrationDataStore.edit { it[SOURCE_DATA_PURGED_PHASE_2] = done }
    }

    /**
     * Wall-clock timestamp (ms-since-epoch) of the first successful
     * post-v54 launch. Drives `db_post_v54_install`'s `shim_age_days`
     * parameter. `null` until the first post-v54 launch writes it.
     *
     * Stored here (rather than directly in the SQL migration) because
     * Room migrations cannot reach DataStore, and because `now`
     * captured during MIGRATION_53_54 would frame "shim age" as
     * "minutes since the install upgrade ran", which is the
     * meaningful clock for the beta safety net.
     */
    suspend fun getV54AppliedAtMs(): Long? =
        context.medicationMigrationDataStore.data.first()[V54_APPLIED_AT_MS]

    suspend fun setV54AppliedAtMsIfMissing(nowMs: Long) {
        context.medicationMigrationDataStore.edit { prefs ->
            if (!prefs.contains(V54_APPLIED_AT_MS)) {
                prefs[V54_APPLIED_AT_MS] = nowMs
            }
        }
    }

    /**
     * True once the parity Batch 5 PR-8 tier-state doc-id backfill
     * has rewritten every existing Firestore tier-state doc to the
     * deterministic `${medCloudId}__${logDate}__${slotCloudId}` form.
     * Guarded so the per-row scan doesn't re-run on every sync.
     *
     * Set only after the loop completes successfully — partial-failure
     * stays retryable on the next app start because each
     * `setDoc(..., merge = true)` call is idempotent.
     */
    suspend fun isTierStateDocIdBackfillDone(): Boolean =
        context.medicationMigrationDataStore.data.first()[TIER_STATE_DOC_ID_BACKFILL_DONE]
            ?: false

    suspend fun setTierStateDocIdBackfillDone(done: Boolean) {
        context.medicationMigrationDataStore.edit {
            it[TIER_STATE_DOC_ID_BACKFILL_DONE] = done
        }
    }
}
