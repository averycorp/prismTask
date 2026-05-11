package com.averycorp.prismtask.data.preferences

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

private val Context.builtInSyncDataStore: DataStore<Preferences> by preferencesDataStore(name = "built_in_sync_prefs")

/**
 * Guards the one-time built-in habit reconciliation passes so neither
 * the local drift cleanup nor the post-sync reconciliation re-runs after
 * it has already succeeded.
 */
@Singleton
class BuiltInSyncPreferences
@Inject
constructor(@ApplicationContext private val context: Context) {
    companion object {
        private val BUILT_INS_RECONCILED = booleanPreferencesKey("built_ins_reconciled")
        private val DRIFT_CLEANUP_DONE = booleanPreferencesKey("drift_cleanup_done")
        private val BUILT_IN_BACKFILL_DONE = booleanPreferencesKey("built_in_backfill_done")
        private val NEW_ENTITIES_BACKFILL_DONE = booleanPreferencesKey("new_entities_backfill_done")
        private val INITIAL_UPLOAD_DONE = booleanPreferencesKey("initial_upload_done")
        private val CLOUD_ID_RESTORE_DONE = booleanPreferencesKey("cloud_id_restore_done")
        private val LIFE_CATEGORY_BACKFILL_DONE = booleanPreferencesKey("life_category_backfill_done")
        private val BUILT_IN_TASK_TEMPLATES_RECONCILED =
            booleanPreferencesKey("built_in_task_templates_reconciled")
        private val TASK_TEMPLATE_BACKFILL_DONE =
            booleanPreferencesKey("task_template_backfill_done")
        private val AUTOMATION_DUP_BACKFILL_DONE =
            booleanPreferencesKey("automation_dup_backfill_done")

        // Per-family flags for the v1.4 "new entity" upload loops in
        // [com.averycorp.prismtask.data.remote.SyncService.maybeRunEntityBackfill].
        // Replaces [NEW_ENTITIES_BACKFILL_DONE] as the primary gate, with
        // a legacy-user fallback baked into each accessor (if the master
        // flag is already true, the per-family flag reads as true so
        // existing users don't re-run the loop after upgrade).
        private val COURSES_BACKFILL_DONE = booleanPreferencesKey("courses_backfill_done")
        private val COURSE_COMPLETIONS_BACKFILL_DONE =
            booleanPreferencesKey("course_completions_backfill_done")
        private val LEISURE_LOGS_BACKFILL_DONE =
            booleanPreferencesKey("leisure_logs_backfill_done")
        private val SELF_CARE_STEPS_BACKFILL_DONE =
            booleanPreferencesKey("self_care_steps_backfill_done")
        private val SELF_CARE_LOGS_BACKFILL_DONE =
            booleanPreferencesKey("self_care_logs_backfill_done")

        /**
         * Per-device set of dismissed built-in template updates. Each entry
         * is shaped `"$templateKey@$version"` (e.g. `"builtin_school@2"`).
         * The detector skips any (key, version) pair present in this set.
         * Intentionally not synced — see TEMPLATE_MIGRATION_DESIGN.md §7.
         */
        private val DISMISSED_BUILT_IN_UPDATES =
            stringSetPreferencesKey("dismissed_built_in_updates")
    }

    suspend fun isBuiltInsReconciled(): Boolean =
        context.builtInSyncDataStore.data.first()[BUILT_INS_RECONCILED] ?: false

    suspend fun setBuiltInsReconciled(done: Boolean) {
        context.builtInSyncDataStore.edit { it[BUILT_INS_RECONCILED] = done }
    }

    suspend fun isDriftCleanupDone(): Boolean =
        context.builtInSyncDataStore.data.first()[DRIFT_CLEANUP_DONE] ?: false

    suspend fun setDriftCleanupDone(done: Boolean) {
        context.builtInSyncDataStore.edit { it[DRIFT_CLEANUP_DONE] = done }
    }

    suspend fun isBuiltInBackfillDone(): Boolean =
        context.builtInSyncDataStore.data.first()[BUILT_IN_BACKFILL_DONE] ?: false

    suspend fun setBuiltInBackfillDone(done: Boolean) {
        context.builtInSyncDataStore.edit { it[BUILT_IN_BACKFILL_DONE] = done }
    }

    suspend fun isNewEntitiesBackfillDone(): Boolean =
        context.builtInSyncDataStore.data.first()[NEW_ENTITIES_BACKFILL_DONE] ?: false

    suspend fun setNewEntitiesBackfillDone(done: Boolean) {
        context.builtInSyncDataStore.edit { it[NEW_ENTITIES_BACKFILL_DONE] = done }
    }

    /**
     * Guard for [com.averycorp.prismtask.data.remote.SyncService.initialUpload].
     * Set to true only after the upload loop finishes successfully; stays false
     * on failure so a retry can happen on the next sign-in. Prevents the
     * duplication spiral where every sign-in re-uploaded every local row as a
     * brand-new Firestore doc.
     */
    suspend fun isInitialUploadDone(): Boolean =
        context.builtInSyncDataStore.data.first()[INITIAL_UPLOAD_DONE] ?: false

    suspend fun setInitialUploadDone(done: Boolean) {
        context.builtInSyncDataStore.edit { it[INITIAL_UPLOAD_DONE] = done }
    }

    /**
     * Guard for the one-shot `cloud_id` column backfill in
     * [com.averycorp.prismtask.data.remote.SyncService.restoreCloudIdFromMetadata].
     * Phase 2's Migration_51_52 populated `cloud_id` on every syncable entity
     * at upgrade time, but subsequent `pullRemoteChanges` calls nulled the
     * column because `SyncMapper.mapToX` didn't yet accept a `cloudId`
     * parameter. The Phase 2.5 patch adds that parameter AND this one-shot
     * restore pass that re-populates the column from `sync_metadata` on the
     * first boot after the patch lands. Set only on successful completion.
     */
    suspend fun isCloudIdRestoreDone(): Boolean =
        context.builtInSyncDataStore.data.first()[CLOUD_ID_RESTORE_DONE] ?: false

    suspend fun setCloudIdRestoreDone(done: Boolean) {
        context.builtInSyncDataStore.edit { it[CLOUD_ID_RESTORE_DONE] = done }
    }

    /**
     * Guard for the one-shot backfill that runs the life-category classifier
     * against every task row whose `life_category` is still NULL (legacy rows
     * from before the centralized resolver landed in [TaskRepository]). Set
     * only after the pass succeeds so a mid-run crash stays retryable.
     */
    suspend fun isLifeCategoryBackfillDone(): Boolean =
        context.builtInSyncDataStore.data.first()[LIFE_CATEGORY_BACKFILL_DONE] ?: false

    suspend fun setLifeCategoryBackfillDone(done: Boolean) {
        context.builtInSyncDataStore.edit { it[LIFE_CATEGORY_BACKFILL_DONE] = done }
    }

    /**
     * Guard for the post-sync built-in task-template reconciliation pass
     * (parity with [BUILT_INS_RECONCILED] but for `task_templates` — see
     * [com.averycorp.prismtask.data.remote.BuiltInTaskTemplateReconciler]).
     */
    suspend fun isBuiltInTaskTemplatesReconciled(): Boolean =
        context.builtInSyncDataStore.data.first()[BUILT_IN_TASK_TEMPLATES_RECONCILED] ?: false

    suspend fun setBuiltInTaskTemplatesReconciled(done: Boolean) {
        context.builtInSyncDataStore.edit { it[BUILT_IN_TASK_TEMPLATES_RECONCILED] = done }
    }

    /**
     * Guard for the one-shot `task_templates` name-based backfill that heals
     * rows pulled from Firestore with `template_key = NULL` and
     * `is_built_in = false`. See
     * [com.averycorp.prismtask.data.remote.BuiltInTaskTemplateBackfiller].
     *
     * The backfiller, on success, also flips
     * [BUILT_IN_TASK_TEMPLATES_RECONCILED] back to false so the reconciler
     * re-runs with the healed dataset on the next `fullSync`.
     */
    suspend fun isTaskTemplateBackfillDone(): Boolean =
        context.builtInSyncDataStore.data.first()[TASK_TEMPLATE_BACKFILL_DONE] ?: false

    suspend fun setTaskTemplateBackfillDone(done: Boolean) {
        context.builtInSyncDataStore.edit { it[TASK_TEMPLATE_BACKFILL_DONE] = done }
    }

    /**
     * Guard for the one-shot pass that collapses cross-device duplicates of
     * the same automation template. Bridges the small window between
     * SyncService routing for `automation_rule` (PR #1070, 2026-05-03) and
     * the `naturalKeyLookup` adoption fix that prevents new duplicates from
     * forming. See [com.averycorp.prismtask.data.remote.AutomationDuplicateBackfiller].
     */
    suspend fun isAutomationDupBackfillDone(): Boolean =
        context.builtInSyncDataStore.data.first()[AUTOMATION_DUP_BACKFILL_DONE] ?: false

    suspend fun setAutomationDupBackfillDone(done: Boolean) {
        context.builtInSyncDataStore.edit { it[AUTOMATION_DUP_BACKFILL_DONE] = done }
    }

    /**
     * Per-family backfill guards. Each accessor falls back to
     * [NEW_ENTITIES_BACKFILL_DONE] so existing users who ran the single
     * master flag don't re-run any family's loop after upgrade. Resetting
     * a per-family flag in isolation force-re-runs that family's loop —
     * useful after a targeted Firestore wipe of one subcollection.
     */
    suspend fun isCoursesBackfillDone(): Boolean = readWithLegacyFallback(COURSES_BACKFILL_DONE)

    suspend fun setCoursesBackfillDone(done: Boolean) {
        context.builtInSyncDataStore.edit { it[COURSES_BACKFILL_DONE] = done }
    }

    suspend fun isCourseCompletionsBackfillDone(): Boolean =
        readWithLegacyFallback(COURSE_COMPLETIONS_BACKFILL_DONE)

    suspend fun setCourseCompletionsBackfillDone(done: Boolean) {
        context.builtInSyncDataStore.edit { it[COURSE_COMPLETIONS_BACKFILL_DONE] = done }
    }

    suspend fun isLeisureLogsBackfillDone(): Boolean =
        readWithLegacyFallback(LEISURE_LOGS_BACKFILL_DONE)

    suspend fun setLeisureLogsBackfillDone(done: Boolean) {
        context.builtInSyncDataStore.edit { it[LEISURE_LOGS_BACKFILL_DONE] = done }
    }

    suspend fun isSelfCareStepsBackfillDone(): Boolean =
        readWithLegacyFallback(SELF_CARE_STEPS_BACKFILL_DONE)

    suspend fun setSelfCareStepsBackfillDone(done: Boolean) {
        context.builtInSyncDataStore.edit { it[SELF_CARE_STEPS_BACKFILL_DONE] = done }
    }

    suspend fun isSelfCareLogsBackfillDone(): Boolean =
        readWithLegacyFallback(SELF_CARE_LOGS_BACKFILL_DONE)

    suspend fun setSelfCareLogsBackfillDone(done: Boolean) {
        context.builtInSyncDataStore.edit { it[SELF_CARE_LOGS_BACKFILL_DONE] = done }
    }

    private suspend fun readWithLegacyFallback(
        key: androidx.datastore.preferences.core.Preferences.Key<Boolean>
    ): Boolean {
        val prefs = context.builtInSyncDataStore.data.first()
        return prefs[key] ?: prefs[NEW_ENTITIES_BACKFILL_DONE] ?: false
    }

    suspend fun isDismissed(templateKey: String, version: Int): Boolean {
        val token = "$templateKey@$version"
        return context.builtInSyncDataStore.data.first()[DISMISSED_BUILT_IN_UPDATES]
            ?.contains(token) == true
    }

    suspend fun setDismissed(templateKey: String, version: Int) {
        val token = "$templateKey@$version"
        context.builtInSyncDataStore.edit { prefs ->
            val current = prefs[DISMISSED_BUILT_IN_UPDATES] ?: emptySet()
            prefs[DISMISSED_BUILT_IN_UPDATES] = current + token
        }
    }

    suspend fun clearDismissals(templateKey: String) {
        val prefix = "$templateKey@"
        context.builtInSyncDataStore.edit { prefs ->
            val current = prefs[DISMISSED_BUILT_IN_UPDATES] ?: return@edit
            prefs[DISMISSED_BUILT_IN_UPDATES] = current.filterNot { it.startsWith(prefix) }.toSet()
        }
    }

    /**
     * Clear every preference key this class writes. Used by:
     *   - tests (replace `setXxx(false)` since the legacy-fallback readers
     *     distinguish "unset" from "explicitly false")
     *   - the account-deletion path (per-account reconciliation flags must
     *     not leak to whoever signs in next on the same device)
     */
    suspend fun clearAll() {
        context.builtInSyncDataStore.edit { it.clear() }
    }
}
